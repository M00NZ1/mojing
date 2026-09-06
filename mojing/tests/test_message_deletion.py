from concurrent.futures import ThreadPoolExecutor, TimeoutError
from threading import Event

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.models import ChatSessionModel, MessageModel, MessageBookmarkModel, MessageAttachmentModel, SessionBranchModel
from backend.app.routes import sessions as routes
from backend.app.schemas import SessionBranchCreate, SessionMessageEdit
from backend.app.services.chat_service import get_session_messages_page
from backend.app.services.message_search_service import search_message_page


@pytest.fixture
def store(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'deletion.db'}", connect_args={'check_same_thread': False, 'timeout': 5})
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)
    with factory() as db:
        db.add(ChatSessionModel(id=1, title='原剧情'))
        db.flush()
        db.add_all(MessageModel(id=i, session_id=1, content=f'信件 {i}') for i in range(1, 5))
        db.commit()
    yield factory
    engine.dispose()


def branch(db, name='A', source=2, **kwargs):
    db.add(SessionBranchModel(session_id=1, branch_id=name, source_message_id=source, parent_branch_id='main', label=f'另一条路 {name}', **kwargs))
    db.commit()


@pytest.mark.parametrize('checkpoint', [False, True])
def test_source_of_storyline_or_checkpoint_cannot_be_deleted(store, checkpoint):
    with store() as db:
        branch(db, is_checkpoint=checkpoint)
        preview = routes.preview_message_deletion(1, 2, 'main', db)
        assert not preview['can_delete']
        assert preview['branches'][0]['is_checkpoint'] is checkpoint
        with pytest.raises(HTTPException) as error:
            routes.delete_message(1, 2, db)
        assert error.value.status_code == 409
        assert [row.id for row in get_session_messages_page(db, 1, None, 40, 'A')[0]] == [1, 2]
        assert db.get(MessageModel, 2).content == '信件 2'


def test_edited_version_and_original_both_remain_readable(store):
    with store() as db:
        edited = routes.update_message(1, 2, SessionMessageEdit(content='修改后的信件', branch_id='main'), db)
        for message_id, selected in [(2, 'main'), (edited.id, edited.branch_id)]:
            with pytest.raises(HTTPException) as error:
                routes.delete_message(1, message_id, db, selected)
            assert error.value.status_code == 409
        assert [row.content for row in get_session_messages_page(db, 1, None, 40, edited.branch_id)[0]] == ['信件 1', '修改后的信件']


def test_regular_delete_removes_bookmark_and_index_but_preserves_media_file(store, tmp_path):
    media = tmp_path / 'user-attachment.txt'
    media.write_text('保留的媒体', encoding='utf-8')
    with store() as db:
        db.add(MessageBookmarkModel(session_id=1, message_id=3, note='收藏'))
        db.add(MessageAttachmentModel(message_id=3, asset_type='file', file_name=media.name, mime_type='text/plain', storage_path=str(media)))
        db.commit()
        assert search_message_page(db, 1, '信件 3')['items']
        assert routes.preview_message_deletion(1, 3, 'main', db)['can_delete']
        assert routes.delete_message(1, 3, db, 'main') == {'ok': True}
        assert db.get(MessageModel, 3) is None
        assert not list(db.scalars(select(MessageBookmarkModel)))
        assert not list(db.scalars(select(MessageAttachmentModel)))
        assert search_message_page(db, 1, '信件 3')['items'] == []
        assert media.read_text(encoding='utf-8') == '保留的媒体'
        assert db.get(MessageModel, 4).content == '信件 4'


def test_preview_is_bounded_and_commit_rechecks_new_references(store):
    with store() as db:
        assert routes.preview_message_deletion(1, 2, 'main', db)['can_delete']
        for i in range(12):
            branch(db, name=str(i))
        preview = routes.preview_message_deletion(1, 2, 'main', db)
        assert preview['reference_count'] == 12
        assert len(preview['branches']) == 10
        with pytest.raises(HTTPException) as error:
            routes.delete_message(1, 2, db)
        assert error.value.status_code == 409


def test_failed_delete_commit_rolls_back_original_and_bookmark(store, monkeypatch):
    with store() as db:
        db.add(MessageBookmarkModel(session_id=1, message_id=3))
        db.commit()
        real_commit = db.commit
        monkeypatch.setattr(db, 'commit', lambda: (_ for _ in ()).throw(RuntimeError('commit failed')))
        with pytest.raises(RuntimeError):
            routes.delete_message(1, 3, db)
        db.rollback()
        assert db.get(MessageModel, 3) is not None
        assert list(db.scalars(select(MessageBookmarkModel)))
        monkeypatch.setattr(db, 'commit', real_commit)
        routes.delete_message(1, 3, db)


@pytest.mark.parametrize('writer', ['branch', 'edit', 'checkpoint'])
def test_branch_writer_and_delete_are_serialized(store, monkeypatch, writer):
    entered, release = Event(), Event()
    original_lock = routes.begin_storyline_write
    def hold_first(db):
        original_lock(db)
        if not entered.is_set():
            entered.set()
            assert release.wait(4)
    monkeypatch.setattr(routes, 'begin_storyline_write', hold_first)
    def create():
        with store() as db:
            if writer == 'branch':
                return routes.create_session_branch(1, SessionBranchCreate(source_message_id=4, branch_id='A'), db)
            if writer == 'edit':
                return routes.update_message(1, 4, SessionMessageEdit(content='新内容', branch_id='main'), db)
            return routes.create_checkpoint(1, {'label': '检查点', 'branch_id': 'main'}, db)
    def remove():
        with store() as db:
            try:
                routes.delete_message(1, 4, db)
            except HTTPException as error:
                return error.status_code
    with ThreadPoolExecutor(max_workers=2) as executor:
        creation = executor.submit(create)
        assert entered.wait(3)
        deletion = executor.submit(remove)
        try:
            with pytest.raises(TimeoutError):
                deletion.result(timeout=.1)
        finally:
            release.set()
        assert creation.result(timeout=4)
        assert deletion.result(timeout=4) == 409
    with store() as db:
        assert db.get(MessageModel, 4) is not None


def test_http_preview_visibility_and_failure_contract(store):
    app = FastAPI()
    app.include_router(routes.router)
    def dependency():
        with store() as db:
            yield db
    app.dependency_overrides[get_db] = dependency
    with store() as db:
        branch(db)
    with TestClient(app) as client:
        assert client.get('/sessions/1/messages/2/deletion-impact').json()['reference_count'] == 1
        assert client.delete('/sessions/1/messages/2').status_code == 409
        assert client.delete('/sessions/1/messages/4', params={'branch_id': 'A'}).status_code == 404
        assert client.get('/sessions/999/messages/4/deletion-impact').status_code == 404
        assert client.delete('/sessions/1/messages/4', params={'branch_id': 'main'}).status_code == 200


def test_delete_wins_race_and_later_branch_creation_rejects_missing_source(store, monkeypatch):
    entered, release = Event(), Event()
    remove_original = routes.remove_unreferenced_message
    def hold_delete(db, message):
        entered.set()
        assert release.wait(4)
        return remove_original(db, message)
    monkeypatch.setattr(routes, 'remove_unreferenced_message', hold_delete)
    def remove():
        with store() as db:
            return routes.delete_message(1, 4, db)
    def create():
        with store() as db:
            try:
                routes.create_session_branch(1, SessionBranchCreate(source_message_id=4, branch_id='A'), db)
            except HTTPException as error:
                return error.status_code
    with ThreadPoolExecutor(max_workers=2) as executor:
        deletion = executor.submit(remove)
        assert entered.wait(3)
        creation = executor.submit(create)
        try:
            with pytest.raises(TimeoutError):
                creation.result(timeout=.1)
        finally:
            release.set()
        assert deletion.result(timeout=4) == {'ok': True}
        assert creation.result(timeout=4) == 400
    with store() as db:
        assert db.get(MessageModel, 4) is None
        assert not list(db.scalars(select(SessionBranchModel)))
