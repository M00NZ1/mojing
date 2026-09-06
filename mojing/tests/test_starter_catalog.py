import json
from pathlib import Path

import pytest
from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import (AppSettingModel, CharacterModel, EncyclopediaEntryModel, SessionWorldModel,
    ChatSessionModel, SessionParticipantModel, WorldEncyclopediaModel, WorldTemplateModel)
from backend.app.routes import sessions, characters, encyclopedia, worlds
from backend.app.schemas import SessionCreate
from backend.app.services import starter_catalog_service as catalog
from backend.app.services.legacy_import import _merge_starter_characters, _seed_builtin_world_templates
from backend.app.services.encyclopedia_seed import seed_legacy_encyclopedias
from backend.app.services.world_package_service import build_world_template_bundle


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'catalog.db'}")
    @event.listens_for(engine, 'connect')
    def foreign_keys(connection, _): connection.execute('PRAGMA foreign_keys=ON')
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        yield db
    engine.dispose()


def old_catalog(db):
    _merge_starter_characters(db)
    _seed_builtin_world_templates(db)
    seed_legacy_encyclopedias(db)


def visible(db, kind):
    model = catalog.KINDS[kind]
    return list(db.scalars(select(model).where(model.id.not_in(catalog.hidden_catalog_ids(db, kind)))))


def original_rows(db):
    return {model.__tablename__: {row.id: {column.name: getattr(row, column.name) for column in model.__table__.columns}
        for row in db.scalars(select(model))} for model in (*catalog.KINDS.values(), EncyclopediaEntryModel)}


def test_fresh_catalog_parity_and_one_time_install(db):
    android = Path(__file__).resolve().parents[1] / 'android/app/src/main/res/raw/seed_data.json'
    assert json.loads(catalog.CATALOG_PATH.read_text(encoding='utf-8')) == json.loads(android.read_text(encoding='utf-8'))
    catalog.install_starter_catalog(db)
    assert [row.name for row in visible(db, 'characters')] == ['沈照', '林汐']
    assert len(visible(db, 'worlds')) == len(visible(db, 'encyclopedias')) == 1
    assert len(list(db.scalars(select(EncyclopediaEntryModel)))) == 9
    before = original_rows(db)
    catalog.install_starter_catalog(db)
    assert original_rows(db) == before
    assert catalog.catalog_status(db)['available']
    assert not any(catalog.catalog_status(db)['retired'].values())


def test_unchanged_old_samples_are_hidden_without_mutating_any_original_content(db):
    old_catalog(db)
    before = original_rows(db)
    catalog.install_starter_catalog(db)
    after = original_rows(db)
    for table, rows in before.items():
        assert {key: after[table][key] for key in rows} == rows
    assert len(visible(db, 'characters')) == 2
    assert len(visible(db, 'worlds')) == len(visible(db, 'encyclopedias')) == 1
    assert all(catalog.catalog_status(db)['retired'].values())
    assert len(characters.list_characters(db)) == 2
    assert len(encyclopedia.list_encyclopedias(db)) == 1
    assert len(worlds.list_world_templates(db=db)) == 1
    assert build_world_template_bundle(db).package_count == 1
    catalog.restore_retired_catalog(db)
    assert not any(catalog.catalog_status(db)['retired'].values())
    assert len(visible(db, 'characters')) == len(before['characters']) + 2
    catalog.install_starter_catalog(db)
    assert not catalog.hidden_catalog_ids(db, 'characters')


def test_changed_or_referenced_samples_remain_visible(db):
    old_catalog(db)
    character = db.scalar(select(CharacterModel))
    character.api_key = 'test-only-configured-key'
    encyclopedia = db.scalar(select(WorldEncyclopediaModel))
    entry = db.scalar(select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia.id))
    entry.content = '玩家的修改必须保留'
    world = db.scalar(select(WorldTemplateModel))
    session = ChatSessionModel(title='已有故事')
    db.add(session)
    db.flush()
    db.add(SessionWorldModel(session_id=session.id, template_id=world.template_id))
    db.commit()
    catalog.install_starter_catalog(db)
    assert character.id not in catalog.hidden_catalog_ids(db, 'characters')
    assert encyclopedia.id not in catalog.hidden_catalog_ids(db, 'encyclopedias')
    assert world.id not in catalog.hidden_catalog_ids(db, 'worlds')
    assert entry.content == '玩家的修改必须保留'
    assert 'test-only-configured-key' not in json.dumps(catalog.catalog_status(db))


def test_install_failure_rolls_back_new_catalog_and_retirement_marker(db):
    old_catalog(db)
    before = original_rows(db)
    def fail_marker(connection, cursor, statement, parameters, context, executemany):
        if statement.startswith('INSERT INTO app_settings'):
            raise RuntimeError('injected final marker failure')
    event.listen(db.bind, 'before_cursor_execute', fail_marker)
    try:
        with pytest.raises(RuntimeError, match='marker failure'):
            catalog.install_starter_catalog(db)
    finally:
        event.remove(db.bind, 'before_cursor_execute', fail_marker)
    assert original_rows(db) == before
    assert db.scalar(select(AppSettingModel)) is None
    catalog.install_starter_catalog(db)
    assert catalog.catalog_status(db)['available']


def test_user_name_collisions_are_not_overwritten_and_deleted_samples_do_not_return(db):
    user = CharacterModel(name='沈照', persona_prompt='用户自己的角色')
    db.add(user)
    db.commit()
    catalog.install_starter_catalog(db)
    status = catalog.catalog_status(db)
    assert user.persona_prompt == '用户自己的角色'
    assert status['characters'][0]['name'] != user.name
    db.delete(db.get(CharacterModel, status['characters'][0]['id']))
    db.commit()
    catalog.install_starter_catalog(db)
    assert not catalog.catalog_status(db)['available']


def test_starter_launch_uses_world_encyclopedia_and_both_characters(db, monkeypatch):
    catalog.install_starter_catalog(db)
    status = catalog.catalog_status(db)
    monkeypatch.setattr(sessions, 'get_local_config', lambda _: {})
    result = sessions.create_session(SessionCreate(title='雾港来信', template_id=status['template_id'],
        encyclopedia_id=status['encyclopedia_id'], initial_character_ids=[row['id'] for row in status['characters']]), db)
    session_id = result.id if hasattr(result, 'id') else result['id']
    world = db.scalar(select(SessionWorldModel).where(SessionWorldModel.session_id == session_id))
    assert world.encyclopedia_id == status['encyclopedia_id']
    assert '白鹭号' in world.world_prompt
    assert len(list(db.scalars(select(SessionParticipantModel).where(SessionParticipantModel.session_id == session_id)))) == 2


def test_configured_world_and_later_edits_are_not_hidden(db):
    old_catalog(db)
    world = db.scalar(select(WorldTemplateModel))
    db.add(AppSettingModel(key='local_config', value_json={'default_world_template_id': world.template_id}))
    db.commit()
    catalog.install_starter_catalog(db)
    assert world.id not in catalog.hidden_catalog_ids(db, 'worlds')
    retired_id = catalog.hidden_catalog_ids(db, 'characters')[0]
    retired = db.get(CharacterModel, retired_id)
    retired.persona_prompt = '之后由玩家重新编辑'
    db.commit()
    assert retired_id not in catalog.hidden_catalog_ids(db, 'characters')


def test_reused_character_id_cannot_attach_an_unrelated_role_to_starter(db):
    catalog.install_starter_catalog(db)
    status = catalog.catalog_status(db)
    removed_id = max(item['id'] for item in status['characters'])
    db.delete(db.get(CharacterModel, removed_id))
    db.commit()
    replacement = CharacterModel(name='玩家新角色')
    db.add(replacement)
    db.commit()
    assert replacement.id == removed_id
    assert not catalog.catalog_status(db)['available']
