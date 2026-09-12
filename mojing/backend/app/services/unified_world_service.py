from __future__ import annotations

import hashlib
import json
import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import (EncyclopediaEntryModel, LegacyLoreMappingModel,
    LegacyWorldMappingModel, UnifiedWorldProfileModel, WorldEncyclopediaModel,
    WorldLoreEntryModel, WorldTemplateModel)


_ENTRY_TYPES = {"character", "location", "faction", "event", "item", "skill", "profession", "concept", "species", "world", "timeline", "other"}
_LEGACY_ENTRY_TYPES = {
    "人物": "character", "地点": "location", "势力": "faction", "事件": "event",
    "物品": "item", "技能/法术": "skill", "职业/等级": "profession", "概念术语": "concept",
    "种族": "species", "世界观总览": "world", "时间线": "timeline", "设定": "concept",
}


def _entry_type(value: str) -> str:
    return value if value in _ENTRY_TYPES else _LEGACY_ENTRY_TYPES.get(value, "other")


def _hash(value: object) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _template_semantics(template: WorldTemplateModel) -> dict:
    return {"label": template.label, "category": template.category, "summary": template.summary,
            "gameplay_mode": template.gameplay_mode, "world_prompt": template.world_prompt,
            "cover_image_path": template.cover_image_path,
            "suggested_choices_json": list(template.suggested_choices_json or []),
            "anti_cheat_prompt": template.anti_cheat_prompt, "is_builtin": bool(template.is_builtin)}


def _lore_semantics(lore: WorldLoreEntryModel) -> dict:
    return {"title": lore.title, "entry_type": lore.entry_type, "keywords": list(lore.keywords_json or []),
            "content": lore.content, "sort_order": lore.sort_order, "is_core": bool(lore.is_core)}


def _source_payload(db: Session, template: WorldTemplateModel) -> dict:
    rows = db.scalars(select(WorldLoreEntryModel).where(WorldLoreEntryModel.world_template_id == template.id)
                      .order_by(WorldLoreEntryModel.sort_order, WorldLoreEntryModel.id)).all()
    return {"template": _template_semantics(template), "lore": [_lore_semantics(row) for row in rows]}


def legacy_world_revision(db: Session, template: WorldTemplateModel) -> str:
    return _hash(_source_payload(db, template))


def _encyclopedia_matches_template(db: Session, encyclopedia: WorldEncyclopediaModel, template: WorldTemplateModel) -> bool:
    if (encyclopedia.name != template.label or encyclopedia.cover_image_path != template.cover_image_path
            or encyclopedia.is_official != bool(template.is_builtin)
            or encyclopedia.description != template.summary
            or encyclopedia.world_prompt != template.world_prompt
            or encyclopedia.gameplay_mode != template.gameplay_mode
            or encyclopedia.anti_cheat_prompt != template.anti_cheat_prompt):
        return False
    profile = db.get(UnifiedWorldProfileModel, encyclopedia.id)
    return profile is None or (profile.category == (template.category or "通用") and
                               list(profile.suggested_choices_json or []) == list(template.suggested_choices_json or []))


def ensure_world_profile(db: Session, encyclopedia: WorldEncyclopediaModel) -> WorldEncyclopediaModel:
    if encyclopedia.id is None:
        db.flush()
    if encyclopedia.id is None:
        raise ValueError("encyclopedia must be persisted before creating a unified profile")
    if db.get(UnifiedWorldProfileModel, encyclopedia.id) is None:
        db.add(UnifiedWorldProfileModel(encyclopedia_id=encyclopedia.id, world_key=str(uuid.uuid4()),
                                        category="通用", suggested_choices_json=[], version=1))
        db.flush()
    return encyclopedia


def backfill_world_profiles(db: Session) -> None:
    missing = db.scalars(select(WorldEncyclopediaModel).where(
        WorldEncyclopediaModel.id.not_in(select(UnifiedWorldProfileModel.encyclopedia_id))))
    for world in missing:
        ensure_world_profile(db, world)
    db.flush()


def _canonical_name(db: Session, template: WorldTemplateModel, source_hash: str) -> str:
    base = template.label.strip() or template.template_id
    if db.scalar(select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.name == base)) is None:
        return base
    candidate = f"{base} · {source_hash[:8]}"
    if db.scalar(select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.name == candidate)) is None:
        return candidate
    index = 2
    while db.scalar(select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.name == f"{candidate}-{index}")) is not None:
        index += 1
    return f"{candidate}-{index}"


def _entry_matches_lore(entry: EncyclopediaEntryModel, lore: WorldLoreEntryModel) -> bool:
    meta = entry.meta_json or {}
    return (meta.get("source") == "legacy_world_lore"
            and meta.get("source_hash") == _hash(_lore_semantics(lore))
            and meta.get("trigger_keywords") == list(lore.keywords_json or [])
            and meta.get("activation_mode") == ("constant" if lore.is_core else "normal")
            and not any(key in meta for key in ("trigger_regex", "min_trust_level", "priority"))
            and entry.title == lore.title and entry.entry_type == _entry_type(lore.entry_type) and entry.summary == ""
            and entry.content == lore.content
            and entry.tags == json.dumps(list(lore.keywords_json or []), ensure_ascii=False)
            and entry.sort_order == lore.sort_order and entry.is_featured == bool(lore.is_core))


def _identical_world(db: Session, template: WorldTemplateModel) -> WorldEncyclopediaModel | None:
    """Automatic reuse requires an exact world and entry-set match, never a name match."""
    candidates = db.scalars(select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.name == template.label)).all()
    lore = db.scalars(select(WorldLoreEntryModel).where(WorldLoreEntryModel.world_template_id == template.id)).all()
    expected = sorted(_hash(_lore_semantics(item) | {"entry_type": _entry_type(item.entry_type)}) for item in lore)
    for candidate in candidates:
        if not _encyclopedia_matches_template(db, candidate, template) or candidate.genre_tags or candidate.narrator_config_json:
            continue
        profile = db.get(UnifiedWorldProfileModel, candidate.id)
        if profile is None and ((template.category or "通用") != "通用" or template.suggested_choices_json):
            continue
        entries = db.scalars(select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == candidate.id)).all()
        actual = []
        for entry in entries:
            meta = entry.meta_json or {}
            if (meta.get("source") != "legacy_world_lore"
                    or entry.summary or entry.cover_image_path or entry.related_entries or entry.confidence != "confirmed"
                    or any(key in meta for key in ("trigger_regex", "min_trust_level", "priority"))):
                break
            keywords = meta.get("trigger_keywords", [])
            if not isinstance(keywords, list) or entry.tags != json.dumps(keywords, ensure_ascii=False):
                break
            if meta.get("activation_mode", "normal") != ("constant" if entry.is_featured else "normal"):
                break
            actual.append(_hash({"title": entry.title, "entry_type": entry.entry_type, "keywords": keywords,
                                 "content": entry.content, "sort_order": entry.sort_order, "is_core": bool(entry.is_featured)}))
        else:
            if sorted(actual) == expected:
                return candidate
    return None


def _copy_lore(db: Session, template: WorldTemplateModel, encyclopedia: WorldEncyclopediaModel) -> None:
    rows = db.scalars(select(WorldLoreEntryModel).where(WorldLoreEntryModel.world_template_id == template.id)
                      .order_by(WorldLoreEntryModel.sort_order, WorldLoreEntryModel.id)).all()
    entries = db.scalars(select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia.id)).all()
    for lore in rows:
        if db.get(LegacyLoreMappingModel, lore.id) is not None:
            continue
        source_hash = _hash(_lore_semantics(lore))
        entry = next((item for item in entries if _entry_matches_lore(item, lore)), None)
        if entry is None:
            entry = EncyclopediaEntryModel(encyclopedia_id=encyclopedia.id, title=lore.title,
                entry_type=_entry_type(lore.entry_type), summary="", content=lore.content,
                tags=json.dumps(list(lore.keywords_json or []), ensure_ascii=False), sort_order=lore.sort_order,
                is_featured=lore.is_core, meta_json={"source": "legacy_world_lore", "world_template_id": template.id,
                "lore_entry_id": lore.id, "legacy_entry_type": lore.entry_type, "keywords": list(lore.keywords_json or []),
                "trigger_keywords": list(lore.keywords_json or []),
                "activation_mode": "constant" if lore.is_core else "normal", "source_trust_level": "manual",
                "source_hash": source_hash, "migration_version": 1})
            db.add(entry)
            db.flush()
            entries.append(entry)
        db.add(LegacyLoreMappingModel(lore_entry_id=lore.id, encyclopedia_entry_id=entry.id,
                                      source_hash=source_hash, migration_version=1))


def promote_legacy_world(db: Session, template: WorldTemplateModel,
                         target_encyclopedia: WorldEncyclopediaModel | None = None) -> WorldEncyclopediaModel:
    mapping = db.get(LegacyWorldMappingModel, template.id)
    if mapping is not None:
        canonical = db.get(WorldEncyclopediaModel, mapping.encyclopedia_id)
        if canonical is None:
            raise ValueError("legacy world mapping points to a missing encyclopedia")
        if target_encyclopedia is not None and target_encyclopedia.id != canonical.id:
            raise ValueError("explicit target encyclopedia conflicts with existing mapping")
        ensure_world_profile(db, canonical)
        return canonical
    source_hash = _hash(_source_payload(db, template))
    if target_encyclopedia is not None and not _encyclopedia_matches_template(db, target_encyclopedia, template):
        raise ValueError("explicit target encyclopedia does not match shared world semantics")
    canonical = target_encyclopedia or _identical_world(db, template)
    created = canonical is None
    if canonical is None:
        canonical = WorldEncyclopediaModel(name=_canonical_name(db, template, source_hash), description=template.summary,
            cover_image_path=template.cover_image_path, is_official=bool(template.is_builtin),
            world_prompt=template.world_prompt, gameplay_mode=template.gameplay_mode,
            anti_cheat_prompt=template.anti_cheat_prompt, created_at=template.created_at, updated_at=template.updated_at)
        db.add(canonical)
        db.flush()
    profile = db.get(UnifiedWorldProfileModel, canonical.id)
    ensure_world_profile(db, canonical)
    if created or profile is None:
        profile = db.get(UnifiedWorldProfileModel, canonical.id)
        profile.category = template.category or "通用"
        profile.suggested_choices_json = list(template.suggested_choices_json or [])
    db.add(LegacyWorldMappingModel(world_template_id=template.id, encyclopedia_id=canonical.id,
                                   source_hash=source_hash, migration_version=1))
    db.flush()
    _copy_lore(db, template, canonical)
    return canonical


def mapped_world_id(db: Session, template_id: str) -> int | None:
    return db.scalar(select(LegacyWorldMappingModel.encyclopedia_id)
                     .join(WorldTemplateModel, WorldTemplateModel.id == LegacyWorldMappingModel.world_template_id)
                     .join(WorldEncyclopediaModel, WorldEncyclopediaModel.id == LegacyWorldMappingModel.encyclopedia_id)
                     .where(WorldTemplateModel.template_id == template_id))


def list_unified_worlds(db: Session) -> list[WorldEncyclopediaModel]:
    return list(db.scalars(select(WorldEncyclopediaModel).join(UnifiedWorldProfileModel)
                           .order_by(WorldEncyclopediaModel.name)).all())
