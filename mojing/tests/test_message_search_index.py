import sqlite3

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.models import ChatSessionModel, MessageModel, SessionBranchModel
from backend.app.services import message_search_service as search
from backend.app.services.chat_service import search_session_messages


@pytest.fixture
def store(tmp_path):
    path = tmp_path / 'search.db'
    engine = create_engine(f'sqlite:///{path}', connect_args={'check_same_thread': False})
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    with factory() as db:
        db.add_all([ChatSessionModel(id=1, title='雾港'), ChatSessionModel(id=2, title='别的会话')])
        db.commit()
    yield factory, path
    engine.dispose()


def add(db, content, session_id=1, **kwargs):
    row = MessageModel(session_id=session_id, content=content, **kwargs)
    db.add(row)
    db.flush()
    return row.id


def complete(db, keyword, **kwargs):
    for _ in range(100):
        result = search.search_message_page(db, 1, keyword, **kwargs)
        if result['index']['ready']:
            return result
    pytest.fail('index did not finish')


def ids(result):
    return [row['id'] for row in result['items']]


@pytest.mark.parametrize('keyword', ['沈', '雾港', '雾港，沈照', 'openai', '100%', 'a_b', '"', 'StraSSe', '🕯️'])
def test_literal_substring_search_and_unicode(store, keyword):
    factory, _ = store
    with factory() as db:
        expected = add(db, '雾港，沈照留下了 OpenAI 100% a_b " Straße 🕯️ 的线索。')
        add(db, '雾与港之间不相连；沈？' if keyword != '沈' else '完全无关')
        add(db, '雾港，沈照 OpenAI 100% a_b " Straße 🕯️', session_id=2)
        db.commit()
        assert ids(complete(db, keyword)) == [expected]
        if keyword in ['100%', 'a_b']:
            assert [row['id'] for row in search_session_messages(db, 1, keyword)] == [expected]


def test_candidates_are_verified_as_contiguous_phrases(store):
    factory, _ = store
    with factory() as db:
        add(db, '甲乙，乙丙，丙丁')
        expected = add(db, '真正的甲乙丙丁')
        db.commit()
        assert ids(complete(db, '甲乙丙丁')) == [expected]


def test_bounded_backfill_resume_and_cursor_pagination(store):
    factory, _ = store
    with factory() as db:
        db.add_all(MessageModel(session_id=1, content=f'雾港证词 {i}') for i in range(463))
        db.commit()
        first = search.search_message_page(db, 1, '雾港')
        assert first['index'] == {'ready': False, 'indexed_count': search.BATCH_SIZE}
        assert ids(first) == list(range(463, 438, -1))
    with factory() as db:
        ready = complete(db, '雾港')
        assert ready['index'] == {'ready': True, 'indexed_count': 463}
        assert ready['total_count'] == 463
        found = ids(ready)
        while ready['next_cursor']:
            ready = search.search_message_page(db, 1, '雾港', before_id=ready['next_cursor'])
            assert ready['total_count'] == 463
            found.extend(ids(ready))
        assert found == list(range(463, 0, -1))
        assert db.scalar(text('SELECT count(*) FROM mojing_message_search_state')) == 1


def test_native_capture_insert_edit_delete_rollback_and_old_client(store):
    factory, path = store
    with factory() as db:
        original = add(db, '旧灯塔')
        db.commit()
        complete(db, '灯塔')
    # Raw SQLite without any application UDF: old clients can still write.
    with sqlite3.connect(path) as raw:
        raw.execute('UPDATE messages SET content=? WHERE id=?', ('新的信封', original))
    with factory() as db:
        assert ids(complete(db, '旧灯塔')) == []
        assert ids(complete(db, '信封')) == [original]
        created = add(db, '信封里的新消息')
        db.commit()
        assert ids(complete(db, '信封')) == [created, original]
        db.execute(text('UPDATE messages SET content=:body WHERE id=:id'), {'body': '不应保存', 'id': original})
        db.rollback()
        assert ids(complete(db, '信封')) == [created, original]
        db.execute(text('DELETE FROM messages WHERE id=:id'), {'id': original})
        db.commit()
        assert ids(complete(db, '信封')) == [created]


def test_branch_edits_share_visibility_owner(store):
    factory, _ = store
    with factory() as db:
        first = add(db, '线索：前文')
        replaced = add(db, '线索：旧话')
        later = add(db, '线索：主线后来')
        db.add(SessionBranchModel(session_id=1, branch_id='A', source_message_id=replaced, parent_branch_id='main'))
        edited = add(db, '线索：修订', branch_id='A', regenerated_from_message_id=replaced)
        db.commit()
        branch_result = complete(db, '线索', branch_id='A')
        assert ids(branch_result) == [edited, first]
        assert branch_result['total_count'] == 2
        main_result = complete(db, '线索')
        assert ids(main_result) == [later, replaced, first]
        assert main_result['total_count'] == 3
        with pytest.raises(ValueError):
            complete(db, '线索', branch_id='missing')


def test_failed_index_batch_preserves_source_cursor_and_can_retry(store, monkeypatch):
    factory, _ = store
    with factory() as db:
        original = add(db, '灯塔')
        db.commit()
        search.ensure_message_search_index(db)
        real_terms = search.search_terms
        monkeypatch.setattr(search, 'search_terms', lambda _: (_ for _ in ()).throw(RuntimeError('index failure')))
        with pytest.raises(RuntimeError):
            complete(db, '灯塔')
        assert db.get(MessageModel, original).content == '灯塔'
        assert db.scalar(text('SELECT count(*) FROM mojing_message_search_state')) == 0
        # A broken indexer cannot prevent new source writes.
        added = add(db, '灯塔的新信')
        db.commit()
        monkeypatch.setattr(search, 'search_terms', real_terms)
        assert ids(complete(db, '灯塔')) == [added, original]


@pytest.mark.parametrize('stored_terms', [False, True])
def test_rebuild_missing_index_and_optional_downgrade_keep_originals(store, monkeypatch, stored_terms):
    factory, path = store
    if stored_terms:
        monkeypatch.setattr(search.sqlite3, 'sqlite_version_info', (3, 42, 0))
    with factory() as db:
        original = add(db, '原始历史')
        db.commit()
        assert ids(complete(db, '历史')) == [original]
        before = list(db.execute(select(MessageModel.id, MessageModel.content)))
        search.rebuild_message_search_index(db)
        search.rebuild_message_search_index(db)
        assert ids(complete(db, '历史')) == [original]
        db.execute(text('DROP TABLE mojing_message_search_fts'))
        db.commit()
        assert ids(complete(db, '历史')) == [original]
        assert list(db.execute(select(MessageModel.id, MessageModel.content))) == before
        search.disable_message_search_triggers_for_downgrade(db)
    with sqlite3.connect(path) as raw:
        raw.execute('UPDATE messages SET content=? WHERE id=?', ('回退后的新原文', original))
    with factory() as db:
        assert ids(complete(db, '新原文')) == [original]
        assert ids(complete(db, '历史')) == []


def test_future_version_is_not_overwritten(store):
    factory, _ = store
    with factory() as db:
        search.ensure_message_search_index(db)
        db.execute(text('UPDATE mojing_message_search_meta SET version=999'))
        db.commit()
        for operation in (search.ensure_message_search_index, search.rebuild_message_search_index):
            with pytest.raises(ValueError):
                operation(db)
        assert db.scalar(text('SELECT version FROM mojing_message_search_meta')) == 999


def test_pending_imports_can_pause_and_resume_without_reindexing_old_rows(store):
    factory, _ = store
    with factory() as db:
        add(db, '旧日')
        db.commit()
        complete(db, '旧日')
        db.add_all(MessageModel(session_id=1, content='导入的线索') for _ in range(420))
        db.commit()
        paused = search.search_message_page(db, 1, '线索', advance_index=False)
        assert paused['index'] == {'ready': False, 'indexed_count': 1}
        assert paused['total_count'] is None
        assert ids(paused) == []
        first = search.search_message_page(db, 1, '线索')
        assert not first['index']['ready']
        assert db.scalar(text('SELECT count(*) FROM mojing_message_search_pending')) == 220
    with factory() as db:
        result = complete(db, '线索')
        assert result['index'] == {'ready': True, 'indexed_count': 1}
        assert ids(result) == list(range(421, 396, -1))


def test_missing_trigger_recovers_changes_and_session_move(store):
    factory, path = store
    with factory() as db:
        original = add(db, '原文')
        db.commit()
        complete(db, '原文')
        db.execute(text('DROP TRIGGER mojing_message_search_update'))
        db.commit()
    with sqlite3.connect(path) as raw:
        raw.execute('UPDATE messages SET content=? WHERE id=?', ('恢复后的证词', original))
    with factory() as db:
        assert ids(complete(db, '证词')) == [original]
        db.execute(text('UPDATE messages SET session_id=2 WHERE id=:id'), {'id': original})
        db.commit()
        assert ids(search.search_message_page(db, 2, '证词')) == [original]
        assert ids(complete(db, '证词')) == []


def test_failed_rebuild_rolls_back_derived_ddl(store, monkeypatch):
    factory, _ = store
    with factory() as db:
        original = add(db, '保留的历史')
        db.commit()
        complete(db, '历史')
        execute = db.execute
        def fail_create(statement, *args, **kwargs):
            if str(statement).startswith('CREATE VIRTUAL TABLE'):
                raise RuntimeError('injected recreate failure')
            return execute(statement, *args, **kwargs)
        monkeypatch.setattr(db, 'execute', fail_create)
        with pytest.raises(RuntimeError):
            search.rebuild_message_search_index(db)
        monkeypatch.setattr(db, 'execute', execute)
        assert ids(complete(db, '历史')) == [original]
        assert db.scalar(text('SELECT count(*) FROM mojing_message_search_fts')) == 1
        assert db.get(MessageModel, original).content == '保留的历史'


def test_sqlite_snapshot_and_bulk_restore_keep_index_recoverable(store, tmp_path):
    factory, path = store
    with factory() as db:
        original = add(db, '备份之前的消息')
        db.commit()
        complete(db, '消息')
    snapshot = tmp_path / 'snapshot.db'
    source = sqlite3.connect(path)
    target = sqlite3.connect(snapshot)
    try:
        source.backup(target)
        assert target.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        target.execute('UPDATE messages SET content=? WHERE id=?', ('副本独立编辑', original))
        target.commit()
    finally:
        source.close()
        target.close()
    copied_engine = create_engine(f'sqlite:///{snapshot}')
    try:
        from sqlalchemy.orm import Session
        with Session(copied_engine) as db:
            assert ids(complete(db, '独立编辑')) == [original]
            db.execute(text('DELETE FROM messages'))
            add(db, '恢复导入的新正文')
            db.commit()
            assert ids(complete(db, '独立编辑')) == []
            assert len(complete(db, '新正文')['items']) == 1
        with factory() as db:
            assert db.get(MessageModel, original).content == '备份之前的消息'
    finally:
        copied_engine.dispose()


def test_http_contract_and_errors_without_real_app_startup(store):
    from backend.app.routes.sessions import router
    factory, _ = store
    app = FastAPI()
    app.include_router(router)
    def isolated_db():
        with factory() as db:
            yield db
    app.dependency_overrides[get_db] = isolated_db
    with factory() as db:
        expected = add(db, '雾港')
        db.commit()
    with TestClient(app) as client:
        response = client.get('/sessions/1/messages/search-page', params={'q': '雾港'})
        assert response.status_code == 200, response.text
        assert ids(response.json()) == [expected]
        assert isinstance(response.json()['items'][0]['created_at'], str)
        assert client.get('/sessions/999/messages/search-page', params={'q': '港'}).status_code == 404
        assert client.get('/sessions/1/messages/search-page', params={'q': '港', 'branch_id': 'bad'}).status_code == 400
        assert client.get('/sessions/1/messages/search-page', params={'q': '港' * 257}).status_code == 400
        assert client.post('/sessions/1/messages/search-index/rebuild').json() == {'ok': True}
        with factory() as db:
            db.execute(text('UPDATE mojing_message_search_meta SET version=999'))
            db.commit()
        assert client.post('/sessions/1/messages/search-index/rebuild').status_code == 409
