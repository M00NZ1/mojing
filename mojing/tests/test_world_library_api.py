import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select, event
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.models import (WorldTemplateModel, WorldLoreEntryModel, WorldEncyclopediaModel,
    EncyclopediaEntryModel, LegacyWorldMappingModel, LegacyLoreMappingModel, UnifiedWorldProfileModel,
    ChatSessionModel, SessionWorldModel, AppSettingModel)
from backend.app.routes.worlds import router as worlds_router
from backend.app.routes.encyclopedia import router as encyclopedia_router
from backend.app.services.world_package_service import build_world_template_package, import_world_template_package


@pytest.fixture
def local_app(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'world-library.sqlite3'}", connect_args={"check_same_thread": False})
    @event.listens_for(engine, "connect")
    def enable_fk(connection, _record):
        connection.execute("PRAGMA foreign_keys=ON")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    app = FastAPI()
    app.include_router(worlds_router, prefix="/api")
    app.include_router(encyclopedia_router, prefix="/api")
    def override_db():
        with factory() as db:
            yield db
    app.dependency_overrides[get_db] = override_db
    with factory() as db:
        world = WorldTemplateModel(template_id="mist", label="雾港", summary="原摘要", world_prompt="午夜钟声", cover_image_path="/missing-cover.png")
        db.add(world)
        db.flush()
        db.add(WorldLoreEntryModel(world_template_id=world.id, title="旧码头", entry_type="地点", keywords_json=["码头"], content="河口旧码头", is_core=True))
        db.commit()
    with TestClient(app, raise_server_exceptions=False) as client:
        yield client, factory
    engine.dispose()


def transfer(client):
    source = client.get('/api/worlds/library').json()['legacy_templates'][0]
    return client.post('/api/worlds/templates/mist/promote', json=source)


def test_library_read_is_pure_and_transfer_is_idempotent(local_app):
    client, factory = local_app
    initial = client.get('/api/worlds/library').json()
    with factory() as db:
        assert db.scalar(select(UnifiedWorldProfileModel)) is None
    payload = initial['legacy_templates'][0]
    first = client.post('/api/worlds/templates/mist/promote', json=payload)
    assert first.status_code == 200, first.text
    world_id = first.json()['encyclopedia_id']
    with factory() as db:
        canonical = db.get(WorldEncyclopediaModel, world_id)
        assert canonical.cover_image_path == '/missing-cover.png'
        canonical.name = '重新命名的雾港'
        db.commit()
    second = client.post('/api/worlds/templates/mist/promote', json=payload)
    assert second.status_code == 200 and second.json()['encyclopedia_id'] == world_id
    assert second.json()['name'] == '重新命名的雾港'
    final = client.get('/api/worlds/library').json()
    assert len(final['worlds']) == 1 and final['legacy_templates'] == []
    with factory() as db:
        entry = db.scalar(select(EncyclopediaEntryModel))
        assert entry.entry_type == 'location'
        assert entry.meta_json['legacy_entry_type'] == '地点'
        assert db.scalar(select(WorldLoreEntryModel.content)) == '河口旧码头'


def test_stale_source_does_not_create_a_world(local_app):
    client, factory = local_app
    result = client.post('/api/worlds/templates/mist/promote', json={"updated_at": "stale"})
    assert result.status_code == 409
    with factory() as db:
        assert db.scalar(select(WorldEncyclopediaModel)) is None
        assert db.scalar(select(LegacyWorldMappingModel)) is None


def test_lore_edit_invalidates_the_confirmed_source(local_app):
    client, factory = local_app
    source = client.get('/api/worlds/library').json()['legacy_templates'][0]
    with factory() as db:
        db.scalar(select(WorldLoreEntryModel)).content = '其他页面刚修改的内容'
        db.commit()
    assert client.post('/api/worlds/templates/mist/promote', json=source).status_code == 409
    assert transfer(client).status_code == 200


def test_new_world_has_identity_and_same_name_create_does_not_overwrite(local_app):
    client, factory = local_app
    first = client.post('/api/encyclopedia', json={'name': '我的世界', 'description': '原始描述'})
    assert first.status_code == 200
    second = client.post('/api/encyclopedia', json={'name': '我的世界', 'description': '不能覆盖'})
    assert second.status_code == 409
    worlds = client.get('/api/worlds/library').json()['worlds']
    assert len(worlds) == 1 and len(worlds[0]['world_key']) == 36
    with factory() as db:
        assert db.get(WorldEncyclopediaModel, first.json()['id']).description == '原始描述'


def test_referenced_world_and_default_template_cannot_be_deleted(local_app):
    client, factory = local_app
    with factory() as db:
        enc = WorldEncyclopediaModel(name='会话资料')
        session = ChatSessionModel(title='已有对话')
        db.add_all([enc, session])
        db.flush()
        db.add(SessionWorldModel(session_id=session.id, encyclopedia_id=enc.id, template_id='custom'))
        db.add(AppSettingModel(key='fixture-default', value_json={'default_world_template_id': 'mist'}))
        db.commit()
        enc_id = enc.id
    assert client.delete('/api/worlds/templates/mist').status_code == 409
    assert client.delete(f'/api/encyclopedia/{enc_id}').status_code == 409


def test_same_name_world_is_never_overwritten(local_app):
    client, factory = local_app
    with factory() as db:
        existing = WorldEncyclopediaModel(name='雾港', description='用户自己的内容')
        db.add(existing)
        db.commit()
        existing_id = existing.id
    response = transfer(client)
    assert response.status_code == 200, response.text
    assert response.json()['encyclopedia_id'] != existing_id
    with factory() as db:
        assert db.get(WorldEncyclopediaModel, existing_id).description == '用户自己的内容'


def test_failure_rolls_back_canonical_and_mapping(local_app):
    client, factory = local_app
    with factory() as db:
        db.connection().exec_driver_sql("CREATE TRIGGER reject_mapping BEFORE INSERT ON legacy_world_mappings BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        db.commit()
    assert transfer(client).status_code == 500
    with factory() as db:
        assert db.scalar(select(WorldEncyclopediaModel)) is None
        assert db.scalar(select(UnifiedWorldProfileModel)) is None
        assert db.scalar(select(WorldTemplateModel.label)) == '雾港'
        assert db.scalar(select(WorldLoreEntryModel.content)) == '河口旧码头'


def test_old_write_paths_are_closed_and_entry_delete_keeps_source_identity(local_app):
    client, factory = local_app
    response = transfer(client)
    assert response.status_code == 200, response.text
    world_id = response.json()['encyclopedia_id']
    assert client.delete('/api/worlds/templates/mist').status_code == 409
    assert client.post('/api/worlds/templates/mist/lore', json={'title': '新条目', 'content': '正文'}).status_code == 409
    assert client.delete(f'/api/encyclopedia/{world_id}').status_code == 409
    with factory() as db:
        source = db.scalar(select(WorldLoreEntryModel))
        old_entry_id = db.scalar(select(EncyclopediaEntryModel.id))
        source_id = source.id
        package = build_world_template_package(db, 'mist').model_dump(mode='json')
        with pytest.raises(ValueError, match='已归入世界'):
            import_world_template_package(db, package_json=package, override_existing=True)
    assert client.delete(f'/api/worlds/lore/{source_id}').status_code == 409
    assert client.delete(f'/api/encyclopedia/entries/{old_entry_id}').status_code == 200
    with factory() as db:
        assert db.get(LegacyLoreMappingModel, source_id).encyclopedia_entry_id is None
        assert db.get(WorldLoreEntryModel, source_id) is not None
        db.add(EncyclopediaEntryModel(id=old_entry_id, encyclopedia_id=world_id, title='新条目', content='其他内容'))
        db.commit()
        assert db.get(LegacyLoreMappingModel, source_id).encyclopedia_entry_id is None
