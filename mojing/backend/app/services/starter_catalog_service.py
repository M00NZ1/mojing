"""One-time starter installation; retired samples remain recoverable in-place."""
import json
from pathlib import Path

from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session

from ..database import Base
from ..models import (AppSettingModel, CharacterModel, EncyclopediaEntryModel, EntryRelationModel,
    SessionWorldModel, TimelineEventModel, WorldEncyclopediaModel, WorldTemplateModel)

CATALOG_KEY = "builtin_catalog_v2"
CATALOG_PATH = Path(__file__).resolve().parents[3] / "data/builtin_pack/starter_catalog.json"
KINDS = {"characters": CharacterModel, "worlds": WorldTemplateModel, "encyclopedias": WorldEncyclopediaModel}


def _timestamp(value):
    # SQLite reloads our UTC timestamps without their original timezone marker.
    return value.replace(tzinfo=None).isoformat()


def _setting(db):
    return db.scalar(select(AppSettingModel).where(AppSettingModel.key == CATALOG_KEY))


def hidden_catalog_ids(db, kind):
    row = _setting(db)
    data = row.value_json if row else {}
    if not isinstance(data, dict) or data.get("version") != 1:
        return []
    retired = data.get("retired", {})
    identity_map = data.get("retired_identity", {})
    if not isinstance(retired, dict) or not isinstance(identity_map, dict):
        return []
    raw_ids = retired.get(kind, [])
    identities = identity_map.get(kind, {})
    if not isinstance(raw_ids, list) or not isinstance(identities, dict):
        return []
    ids = [item for item in raw_ids if isinstance(item, int) and item > 0]
    model = KINDS[kind]
    rows = db.execute(select(model.id, model.created_at, model.updated_at).where(model.id.in_(ids)))
    # Editing a retired row or reusing a deleted ID must not hide user content.
    return [item.id for item in rows if identities.get(str(item.id)) == [_timestamp(item.created_at), _timestamp(item.updated_at)]]


def _signature(row, exclude=()):
    return {column.name: getattr(row, column.name) for column in row.__table__.columns
            if column.name not in {"id", "created_at", "updated_at", *exclude}}


def _referenced(db, model, row_id, ignored=()):
    for table in Base.metadata.tables.values():
        if table.name in ignored:
            continue
        for column in table.columns:
            if any(fk.column.table.name == model.__tablename__ for fk in column.foreign_keys):
                if db.scalar(select(column).where(column == row_id).limit(1)) is not None:
                    return True
            elif model is CharacterModel and column.name == "character_id":
                if db.scalar(select(column).where(column == row_id).limit(1)) is not None:
                    return True
    return False


def _retire_unchanged_catalog(db):
    # Reconstruct the exact prior release only in a disposable, in-memory DB.
    # Never run its overwrite/reseed routines against the actual user database.
    from .legacy_import import _merge_starter_characters, _seed_builtin_world_templates
    from .encyclopedia_seed import seed_legacy_encyclopedias
    retired = {kind: [] for kind in KINDS}
    if not any(db.scalar(select(model.id).limit(1)) is not None for model in KINDS.values()):
        return retired
    reference_engine = create_engine("sqlite://")
    try:
        Base.metadata.create_all(reference_engine)
        with Session(reference_engine) as reference:
            _merge_starter_characters(reference)
            _seed_builtin_world_templates(reference)
            seed_legacy_encyclopedias(reference)
            for kind, model in KINDS.items():
                identity = "template_id" if kind == "worlds" else "name"
                for original in reference.scalars(select(model)):
                    current = db.scalar(select(model).where(getattr(model, identity) == getattr(original, identity)))
                    if current is None or _signature(current) != _signature(original):
                        continue
                    ignored = ("encyclopedia_entries",) if kind == "encyclopedias" else ()
                    if _referenced(db, model, current.id, ignored):
                        continue
                    if kind == "worlds" and db.scalar(select(SessionWorldModel.id).where(SessionWorldModel.template_id == current.template_id).limit(1)):
                        continue
                    if kind == "worlds" and db.scalar(select(AppSettingModel.id).where(
                        func.json_extract(AppSettingModel.value_json, "$.default_world_template_id") == current.template_id).limit(1)):
                        continue
                    if kind == "encyclopedias":
                        expected = list(reference.scalars(select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == original.id)))
                        count = db.scalar(select(func.count()).select_from(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == current.id))
                        if count != len(expected):
                            continue
                        entries = list(db.scalars(select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == current.id)))
                        encode = lambda entry: json.dumps(_signature(entry, ("encyclopedia_id",)), sort_keys=True, ensure_ascii=False, default=str)
                        if sorted(map(encode, entries)) != sorted(map(encode, expected)):
                            continue
                        if any(_referenced(db, EncyclopediaEntryModel, entry.id) for entry in entries):
                            continue
                    retired[kind].append(current.id)
    finally:
        reference_engine.dispose()
    return retired


def _unique(db, model, field, value):
    candidate = value
    suffix = 1
    while db.scalar(select(model.id).where(getattr(model, field) == candidate)) is not None:
        suffix += 1
        candidate = f"{value}_{suffix}"
    return candidate


def install_starter_catalog(db: Session):
    if _setting(db) is not None:
        return
    try:
        data = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        if len(data["characters"]) != 2 or len(data["templates"]) != 1 or not data.get("encyclopedia"):
            raise ValueError("内置目录格式无效，原有资料未改变")
        retired = _retire_unchanged_catalog(db)
        identities = {kind: {str(row.id): [_timestamp(row.created_at), _timestamp(row.updated_at)]
            for row in db.scalars(select(model).where(model.id.in_(retired[kind])))} for kind, model in KINDS.items()}
        spec = data["encyclopedia"]
        encyclopedia = WorldEncyclopediaModel(name=_unique(db, WorldEncyclopediaModel, "name", spec["name"]),
            description=spec["description"], world_prompt=spec["worldPrompt"], gameplay_mode=spec["gameplayMode"],
            genre_tags=spec["genreTags"], anti_cheat_prompt=data["templates"][0]["antiCheatPrompt"], is_official=True)
        db.add(encyclopedia)
        db.flush()
        characters = []
        for item in data["characters"]:
            row = CharacterModel(name=_unique(db, CharacterModel, "name", item["name"]), persona_prompt=item["personaPrompt"],
                temperature=item["temperature"], max_tokens=item["maxTokens"], avatar_color=item["avatarColor"],
                api_base_url="", model_name="")
            db.add(row)
            db.flush()
            characters.append(row)
        entries = {}
        specs = [*spec["entries"], *[{"title": row.name, "entryType": "character", "summary": "雾港的居民", "content": row.persona_prompt, "tags": row.name} for row in characters]]
        for order, item in enumerate(specs):
            row = EncyclopediaEntryModel(encyclopedia_id=encyclopedia.id, title=item["title"], entry_type=item["entryType"],
                summary=item.get("summary", ""), content=item["content"], tags=item.get("tags", ""), sort_order=order,
                confidence=item.get("confidence", "confirmed"), meta_json=item.get("meta") or {})
            db.add(row)
            db.flush()
            entries[item["title"]] = row.id
        # Canonical character titles also resolve when a user already owns that name.
        for item, row in zip(data["characters"], characters):
            entries[item["name"]] = entries[row.name]
        for item in spec.get("relations", []):
            db.add(EntryRelationModel(encyclopedia_id=encyclopedia.id, from_entry_id=entries[item["fromTitle"]],
                to_entry_id=entries[item["toTitle"]], relation_type=item["relationType"], label=item["label"]))
        for item in spec.get("timelineEvents", []):
            db.add(TimelineEventModel(encyclopedia_id=encyclopedia.id, title=item["title"], time_label=item["eventTime"],
                time_order=item["sortOrder"], summary=item["description"], content=item["description"]))
        item = data["templates"][0]
        world = WorldTemplateModel(template_id=_unique(db, WorldTemplateModel, "template_id", item["templateId"]),
            label=item["label"], category=item["category"], summary=item["summary"], gameplay_mode=item["gameplayMode"],
            world_prompt=item["worldPrompt"], anti_cheat_prompt=item["antiCheatPrompt"], is_builtin=True)
        db.add(world)
        db.flush()
        db.add(AppSettingModel(key=CATALOG_KEY, value_json={"version": 1, "retired": retired, "retired_identity": identities,
            "encyclopedia_id": encyclopedia.id, "world_id": world.id, "character_ids": [row.id for row in characters],
            "installed_identity": {"world": _timestamp(world.created_at), "encyclopedia": _timestamp(encyclopedia.created_at),
                "characters": {str(row.id): _timestamp(row.created_at) for row in characters}}}))
        db.commit()
    except Exception:
        db.rollback()
        raise


def catalog_status(db):
    row = _setting(db)
    data = row.value_json if row and isinstance(row.value_json, dict) else {}
    if data.get("version") != 1:
        return {"available": False, "retired": {}}
    keys = [data.get("world_id"), data.get("encyclopedia_id")]
    character_ids = data.get("character_ids")
    if not isinstance(character_ids, list) or len(character_ids) != 2 or not all(isinstance(key, int) and key > 0 for key in [*keys, *character_ids]):
        return {"available": False, "retired": {}}
    world = db.get(WorldTemplateModel, data.get("world_id"))
    encyclopedia = db.get(WorldEncyclopediaModel, data.get("encyclopedia_id"))
    characters = [db.get(CharacterModel, key) for key in data.get("character_ids", [])]
    retired = {}
    for kind, model in KINDS.items():
        names = list(db.scalars(select(model.label if kind == "worlds" else model.name).where(model.id.in_(hidden_catalog_ids(db, kind)))))
        retired[kind] = names
    available = bool(world and encyclopedia and len(characters) == 2 and all(characters))
    identity = data.get("installed_identity", {})
    if available:
        available = isinstance(identity, dict) and isinstance(identity.get("characters"), dict) and (identity.get("world") == _timestamp(world.created_at)
            and identity.get("encyclopedia") == _timestamp(encyclopedia.created_at)
            and all(identity.get("characters", {}).get(str(item.id)) == _timestamp(item.created_at) for item in characters))
    return {"available": available, "retired": retired,
        "title": world.label if world else "雾港来信", "summary": world.summary if world else "",
        "template_id": world.template_id if world else None, "encyclopedia_id": encyclopedia.id if encyclopedia else None,
        "characters": [{"id": item.id, "name": item.name} for item in characters if item]}


def restore_retired_catalog(db):
    row = _setting(db)
    if row and isinstance(row.value_json, dict) and row.value_json.get("version") == 1:
        row.value_json = {**row.value_json, "retired": {}}
        db.commit()
    return catalog_status(db)
