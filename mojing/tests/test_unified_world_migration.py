import importlib.util
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect, select
from sqlalchemy.orm import Session

from backend.app.models import (
    ChatSessionModel,
    EncyclopediaEntryModel,
    LegacyLoreMappingModel,
    LegacyWorldMappingModel,
    UnifiedWorldProfileModel,
    WorldEncyclopediaModel,
    WorldLoreEntryModel,
    WorldTemplateModel,
)
from backend.app.services.unified_world_service import ensure_world_profile, list_unified_worlds, mapped_world_id, promote_legacy_world


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'world.sqlite3'}")
    from backend.app.database import Base
    Base.metadata.create_all(engine)
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        yield session
    engine.dispose()


def template(template_id, label="同名世界", prompt="旧世界"):
    return WorldTemplateModel(template_id=template_id, label=label, category="悬疑", summary="摘要", world_prompt=prompt, gameplay_mode="自由剧情", suggested_choices_json=["调查"], anti_cheat_prompt="规则")


def test_same_name_different_content_gets_deterministic_suffix_and_mapping(db):
    first, second = template("world-one"), template("world-two", prompt="另一世界")
    db.add_all([first, second])
    db.commit()
    canonical_one = promote_legacy_world(db, first)
    db.commit()
    canonical_two = promote_legacy_world(db, second)
    db.commit()
    assert canonical_one.name != canonical_two.name
    assert mapped_world_id(db, "world-one") == canonical_one.id
    assert mapped_world_id(db, "world-two") == canonical_two.id
    assert len(list_unified_worlds(db)) == 2


def test_repeated_promote_and_edited_canonical_do_not_overwrite(db):
    world = template("repeat-world")
    lore = WorldLoreEntryModel(world_template_id=1, title="旧条目", entry_type="faction", keywords_json=["港口", "钟楼"], content="原始内容", sort_order=2, is_core=True)
    db.add(world)
    db.flush()
    lore.world_template_id = world.id
    db.add(lore)
    db.flush()
    canonical = promote_legacy_world(db, world)
    db.commit()
    entry = db.scalar(select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == canonical.id))
    canonical.description = "用户编辑后的摘要"
    entry.content = "用户编辑后的正文"
    db.commit()
    assert promote_legacy_world(db, world).id == canonical.id
    db.commit()
    assert db.get(WorldEncyclopediaModel, canonical.id).description == "用户编辑后的摘要"
    assert db.get(EncyclopediaEntryModel, entry.id).content == "用户编辑后的正文"
    assert len(db.scalars(select(LegacyLoreMappingModel)).all()) == 1


def test_lore_preserves_fields_and_source_metadata(db):
    world = template("lore-world")
    db.add(world)
    db.flush()
    db.add(WorldLoreEntryModel(world_template_id=world.id, title="核心势力", entry_type="faction", keywords_json=["甲", "乙"], content="完整资料", sort_order=4, is_core=True))
    db.flush()
    canonical = promote_legacy_world(db, world)
    db.commit()
    entry = db.scalar(select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == canonical.id))
    assert entry.entry_type == "faction" and entry.content == "完整资料"
    assert entry.tags == '["甲", "乙"]' and entry.is_featured == 1
    assert entry.meta_json["trigger_keywords"] == ["甲", "乙"]
    assert entry.meta_json["activation_mode"] == "constant"
    assert entry.meta_json["source_trust_level"] == "manual"


def test_explicit_target_requires_matching_public_semantics(db):
    world = template("target-world")
    target = WorldEncyclopediaModel(name="目标", description="不同摘要", world_prompt="不同", gameplay_mode="自由剧情", anti_cheat_prompt="规则")
    db.add_all([world, target])
    db.commit()
    with pytest.raises(ValueError):
        promote_legacy_world(db, world, target)
    db.rollback()
    assert db.scalar(select(LegacyWorldMappingModel)) is None


def test_old_template_and_session_are_unchanged_and_profile_is_flush_only(db):
    world = template("safe-world")
    db.add(world)
    db.add(ChatSessionModel(title="旧会话"))
    db.flush()
    db.commit()
    before = (world.id, world.updated_at, world.world_prompt)
    canonical = promote_legacy_world(db, world)
    assert (world.id, world.updated_at, world.world_prompt) == before
    assert db.scalar(select(ChatSessionModel).where(ChatSessionModel.title == "旧会话")) is not None
    assert db.get(UnifiedWorldProfileModel, canonical.id) is not None
    db.rollback()
    assert db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == "safe-world")) is not None
    assert db.scalar(select(LegacyWorldMappingModel)) is None
    assert db.scalar(select(WorldEncyclopediaModel)) is None


def test_mapping_insert_failure_rolls_back_new_canonical(db):
    world = template("rollback-world")
    db.add(world)
    db.commit()
    db.connection().exec_driver_sql("CREATE TRIGGER reject_legacy_mapping BEFORE INSERT ON legacy_world_mappings BEGIN SELECT RAISE(ABORT, 'fixture'); END")
    with pytest.raises(Exception):
        promote_legacy_world(db, world)
    db.rollback()
    assert db.scalar(select(WorldEncyclopediaModel)) is None
    assert db.scalar(select(UnifiedWorldProfileModel)) is None


def test_migration_is_repeatable_and_downgrade_preserves_side_tables(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'migration.sqlite3'}")
    from backend.app.database import Base
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.add(WorldEncyclopediaModel(name="升级前的世界", world_prompt="原始世界背景"))
        db.commit()
    migration_path = Path(__file__).resolve().parents[1] / "backend" / "alembic" / "versions" / "20260913_0015_unified_world.py"
    spec = importlib.util.spec_from_file_location("unified_world_migration", migration_path)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    with engine.begin() as connection:
        for table_name in ("legacy_lore_mappings", "legacy_world_mappings", "unified_world_profiles"):
            connection.exec_driver_sql(f"DROP TABLE IF EXISTS {table_name}")
        context = MigrationContext.configure(connection)
        with Operations.context(context):
            migration.upgrade()
            first_key = connection.exec_driver_sql("SELECT world_key FROM unified_world_profiles").scalar_one()
            migration.upgrade()
            migration.downgrade()
        assert connection.exec_driver_sql("SELECT world_key FROM unified_world_profiles").scalar_one() == first_key
        assert connection.exec_driver_sql("SELECT world_prompt FROM world_encyclopedias").scalar_one() == "原始世界背景"
        names = set(inspect(connection).get_table_names())
    engine.dispose()
    assert {"unified_world_profiles", "legacy_world_mappings", "legacy_lore_mappings"} <= names


def test_identical_world_reuses_identity_and_entries(db):
    first, second = template("duplicate-one"), template("duplicate-two")
    db.add_all([first, second])
    db.flush()
    for world in (first, second):
        db.add(WorldLoreEntryModel(world_template_id=world.id, title="钟楼", keywords_json=["钟楼"], content="相同资料", is_core=True))
    db.flush()
    canonical = promote_legacy_world(db, first)
    db.flush()
    key = db.get(UnifiedWorldProfileModel, canonical.id).world_key
    duplicate = promote_legacy_world(db, second)
    db.commit()
    assert duplicate.id == canonical.id
    assert db.get(UnifiedWorldProfileModel, canonical.id).world_key == key
    assert len(db.scalars(select(EncyclopediaEntryModel)).all()) == 1
    assert len(db.scalars(select(LegacyLoreMappingModel)).all()) == 2
