import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import (
    ChatSessionModel, SessionWorldModel, WorldTemplateModel,
    WorldEncyclopediaModel, WorldLoreEntryModel,
)
from backend.app.services.chat_service import (
    _get_effective_world_prompt, _get_encyclopedia_hits, _get_lore_hits,
)
from backend.app.services.story_simulation_service import _load_context
from backend.app.services.unified_world_service import promote_legacy_world


@pytest.fixture
def local_db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'world-context.sqlite3'}")
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        yield db
    engine.dispose()


def seed(db):
    template = WorldTemplateModel(template_id="mist-harbor", label="雾港", world_prompt="午夜的钟倒着走。")
    db.add(template)
    db.flush()
    db.add(WorldLoreEntryModel(world_template_id=template.id, title="钟楼", content="钟楼在港口中央。", keywords_json=["暗号"], is_core=False))
    db.add(WorldLoreEntryModel(world_template_id=template.id, title="宵禁", content="午夜后不得出门。", is_core=True))
    db.flush()
    return template


def session_world(db, template, encyclopedia_id):
    session = ChatSessionModel(title="旧故事")
    db.add(session)
    db.flush()
    world = SessionWorldModel(session_id=session.id, template_id=template.template_id,
                              encyclopedia_id=encyclopedia_id, world_prompt=template.world_prompt)
    db.add(world)
    db.flush()
    return world


def test_mapped_sources_use_entries_once_and_keep_snapshot(local_db):
    db = local_db
    template = seed(db)
    canonical = promote_legacy_world(db, template)
    world = session_world(db, template, canonical.id)
    before = {column.name: getattr(world, column.name) for column in world.__table__.columns}
    legacy_hits, _ = _get_lore_hits(db, world, "暗号")
    entries, _ = _get_encyclopedia_hits(db, world, "暗号")
    assert legacy_hits == []
    assert {hit["title"] for hit in entries} == {"钟楼", "宵禁"}
    assert _load_context(db, template.template_id, canonical.id, []).count(template.world_prompt) == 1
    assert _get_effective_world_prompt(db, world).count(template.world_prompt) == 1
    assert {column.name: getattr(world, column.name) for column in world.__table__.columns} == before


def test_old_composite_and_template_only_sessions_keep_sources(local_db):
    db = local_db
    template = seed(db)
    promote_legacy_world(db, template)
    other = WorldEncyclopediaModel(name="北境", world_prompt="北境全年积雪。")
    db.add(other)
    db.flush()
    world = session_world(db, template, other.id)
    hits, _ = _get_lore_hits(db, world, "暗号")
    assert {hit["title"] for hit in hits} == {"钟楼", "宵禁"}
    context = _load_context(db, template.template_id, other.id, [])
    assert template.world_prompt in context and other.world_prompt in context
    world.encyclopedia_id = None
    assert _get_lore_hits(db, world, "暗号")[0]
    assert template.world_prompt in _load_context(db, template.template_id, None, [])


def test_distinct_session_background_is_not_discarded(local_db):
    db = local_db
    template = seed(db)
    canonical = promote_legacy_world(db, template)
    world = session_world(db, template, canonical.id)
    world.world_prompt = "当前正在远海航行。"
    text = _get_effective_world_prompt(db, world)
    assert template.world_prompt in text
    assert world.world_prompt in text
    assert db.scalar(select(WorldTemplateModel.world_prompt)) == "午夜的钟倒着走。"


def test_migrated_lore_keeps_keyword_gate_and_recursive_hits(local_db):
    db = local_db
    template = seed(db)
    db.add(WorldLoreEntryModel(world_template_id=template.id, title="港务局", keywords_json=["港口"], content="二层藏着一枚银铃。"))
    db.add(WorldLoreEntryModel(world_template_id=template.id, title="银铃", keywords_json=["银铃"], content="银铃属于船长。"))
    db.flush()
    world = session_world(db, template, None)
    queries = ["暗号", "港口", "钟楼", "毫不相关"]
    expected = {query: [(hit["title"], hit["score"], hit["content"]) for hit in _get_lore_hits(db, world, query)[0]] for query in queries}
    canonical = promote_legacy_world(db, template)
    world.encyclopedia_id = canonical.id
    for query in queries:
        hits, _ = _get_encyclopedia_hits(db, world, query, 1200)
        assert [(hit["title"], hit["score"], hit["content"]) for hit in hits] == expected[query]
    assert "钟楼" not in {hit[0] for hit in expected["钟楼"]}
    assert "银铃" in {hit[0] for hit in expected["港口"]}
