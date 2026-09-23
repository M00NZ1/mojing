import asyncio
import shutil
import sqlite3
from contextlib import closing
from pathlib import Path
from uuid import uuid4

import pytest
from alembic import command
from alembic.config import Config
from fastapi import HTTPException
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base
from backend.app.config import settings
from backend.app.models import ChatSessionModel, MessageModel
from backend.app.routes import sessions as session_routes
from backend.app.routes.sessions import add_user_message, get_user_message_by_client_id
from backend.app.schemas import GenerateRequest, SessionMessageCreate
from backend.app.services.backup_service import BackupError
from backend.app.services.schema_migration_service import SchemaMigrationError, ensure_message_client_id_schema


def test_existing_messages_upgrade_once_with_recoverable_snapshot(tmp_path):
    database_path = tmp_path / "legacy.db"
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY, session_id INTEGER, "
            "speaker_type TEXT, branch_id TEXT, content TEXT)"
        )
        connection.execute("INSERT INTO messages VALUES (1, 2, 'user', 'main', '原消息')")
        connection.executemany(
            "INSERT INTO messages VALUES (?, 2, 'user', 'main', ?)",
            ((index, f"旧消息 {index}") for index in range(2, 2002)),
        )
        connection.commit()
    engine = create_engine(f"sqlite:///{database_path}")
    snapshots = []

    def snapshot(source, _migration_id):
        target = tmp_path / "before.db"
        shutil.copyfile(source, target)
        snapshots.append(target)
        return target

    try:
        with engine.connect() as connection:
            first = ensure_message_client_id_schema(connection, snapshot_builder=snapshot)
            connection.commit()
            second = ensure_message_client_id_schema(connection, snapshot_builder=snapshot)
            assert first.changed is True
            assert second.changed is False
            assert first.added_columns == ("client_message_id",)
            assert first.added_indexes == ("ix_messages_client_message_id",)
            assert connection.exec_driver_sql("SELECT content, client_message_id FROM messages WHERE id = 1").one() == ("原消息", None)
            assert connection.exec_driver_sql("SELECT count(*) FROM messages").scalar_one() == 2001
        assert len(snapshots) == 1
        with closing(sqlite3.connect(snapshots[0])) as old:
            assert "client_message_id" not in {row[1] for row in old.execute("PRAGMA table_info(messages)")}
            assert old.execute("SELECT content FROM messages WHERE id = 1").fetchone() == ("原消息",)
            assert old.execute("SELECT count(*) FROM messages").fetchone() == (2001,)
    finally:
        engine.dispose()


def test_failed_snapshot_does_not_change_legacy_messages(tmp_path):
    database_path = tmp_path / "legacy.db"
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY, session_id INTEGER, "
            "speaker_type TEXT, branch_id TEXT, content TEXT)"
        )
        connection.execute("INSERT INTO messages VALUES (1, 2, 'user', 'main', '原消息')")
        connection.commit()
    engine = create_engine(f"sqlite:///{database_path}")
    try:
        with sessionmaker(bind=engine)() as db:
            with pytest.raises(SchemaMigrationError):
                ensure_message_client_id_schema(
                    db, snapshot_builder=lambda *_: (_ for _ in ()).throw(BackupError("disk full")),
                )
        with closing(sqlite3.connect(database_path)) as connection:
            assert "client_message_id" not in {row[1] for row in connection.execute("PRAGMA table_info(messages)")}
            assert connection.execute("SELECT content FROM messages").fetchone() == ("原消息",)
    finally:
        engine.dispose()


def test_alembic_upgrades_existing_message_table(tmp_path, monkeypatch):
    database_path = tmp_path / "alembic-old.db"
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY, session_id INTEGER, "
            "speaker_type TEXT, branch_id TEXT, content TEXT)"
        )
        connection.execute("INSERT INTO messages VALUES (1, 2, 'user', 'main', '旧正文')")
        connection.commit()
    monkeypatch.setattr(settings, "database_url", f"sqlite:///{database_path.as_posix()}")
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    config.set_main_option("script_location", str(Path(__file__).resolve().parents[1] / "backend" / "alembic"))
    command.stamp(config, "20260923_0016")
    command.upgrade(config, "head")
    with closing(sqlite3.connect(database_path)) as connection:
        assert "client_message_id" in {row[1] for row in connection.execute("PRAGMA table_info(messages)")}
        assert connection.execute("SELECT content FROM messages").fetchone() == ("旧正文",)
        assert connection.execute("SELECT version_num FROM alembic_version").fetchone() == ("20260924_0017",)
        client_id = str(uuid4())
        connection.execute("UPDATE messages SET client_message_id = ? WHERE id = 1", (client_id,))
        with pytest.raises(sqlite3.IntegrityError):
            connection.execute(
                "INSERT INTO messages (id, session_id, speaker_type, branch_id, content, client_message_id) "
                "VALUES (2, 2, 'user', 'main', '重复消息', ?)", (client_id,),
            )
    assert list((tmp_path / "backups" / "migrations").glob("*.zip"))


def test_repeated_send_returns_original_message_and_rejects_changed_payload(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'chat.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    client_id = uuid4()
    try:
        with Session() as db:
            session = ChatSessionModel(title="会话")
            db.add(session)
            db.commit()
            session_id = session.id

        with Session() as db:
            payload = SessionMessageCreate(content="第一句", branch_id="main", client_message_id=client_id)
            first = add_user_message(session_id, payload, db)
            again = add_user_message(session_id, payload, db)
            looked_up = get_user_message_by_client_id(session_id, client_id, db)
            assert first.id == again.id == looked_up.id
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 1
            assert db.scalar(select(MessageModel.client_message_id)) == str(client_id)
            with pytest.raises(HTTPException) as wrong_session:
                get_user_message_by_client_id(session_id + 1, client_id, db)
            assert wrong_session.value.status_code == 404
            with pytest.raises(HTTPException) as conflict:
                add_user_message(session_id, SessionMessageCreate(
                    content="另一句", branch_id="main", client_message_id=client_id,
                ), db)
            assert conflict.value.status_code == 409
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 1
            legacy = add_user_message(session_id, SessionMessageCreate(content="旧客户端消息"), db)
            assert legacy.id != first.id
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 2
    finally:
        engine.dispose()


def test_generation_uses_saved_message_for_speaker_selection_without_resending(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'chat.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    selected = []
    monkeypatch.setattr(session_routes, "get_model_choice", lambda *args: {"selection": None})
    monkeypatch.setattr(
        session_routes, "select_speakers_for_turn",
        lambda _db, _session_id, content, *_args: (selected.append(content) or [7], "已选角色"),
    )
    try:
        with Session() as db:
            session = ChatSessionModel(title="会话")
            db.add(session)
            db.commit()
            saved = add_user_message(session.id, SessionMessageCreate(
                content="请让阿棠回答", client_message_id=uuid4(),
            ), db)
            response = session_routes.generate_stream(session.id, GenerateRequest(
                existing_user_message_id=saved.id,
                auto_select_speakers=True,
            ), db)
            assert response.status_code == 200
            assert selected == ["请让阿棠回答"]
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 1
            with pytest.raises(HTTPException) as conflict:
                session_routes.generate_stream(session.id, GenerateRequest(
                    user_message="重复正文", existing_user_message_id=saved.id,
                ), db)
            assert conflict.value.status_code == 400
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 1
            asyncio.run(response._close_content())
    finally:
        engine.dispose()


def test_saved_user_reply_retry_waits_for_stream_close_and_rechecks_history(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'chat.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    monkeypatch.setattr(session_routes, "get_model_choice", lambda *args: {"selection": None})

    def narrator_reply(*_args, **_kwargs):
        yield {"type": "message_start", "stream_key": "reply"}

    monkeypatch.setattr(
        session_routes, "stream_narrator_reply",
        narrator_reply,
    )
    try:
        with Session() as db:
            session = ChatSessionModel(title="会话")
            db.add(session)
            db.commit()
            saved = add_user_message(session.id, SessionMessageCreate(content="请继续", client_message_id=uuid4()), db)
            payload = GenerateRequest(existing_user_message_id=saved.id, narrator_only=True)
            assert session_routes.get_user_message_reply_status(session.id, saved.id, "main", db) == {"status": "ready"}
            response = session_routes.generate_stream(session.id, payload, db)
            assert session_routes.get_user_message_reply_status(session.id, saved.id, "main", db) == {"status": "active"}
            with pytest.raises(HTTPException) as active:
                session_routes.generate_stream(session.id, payload, db)
            assert active.value.status_code == 409

            async def consume():
                return [chunk async for chunk in response.body_iterator]

            assert any('"type": "done"' in chunk for chunk in asyncio.run(consume()))
            assert session_routes.get_user_message_reply_status(session.id, saved.id, "main", db) == {"status": "ready"}
            interrupted = session_routes.generate_stream(session.id, payload, db)

            async def disconnect():
                await interrupted.body_iterator.__anext__()
                await interrupted.body_iterator.aclose()

            asyncio.run(disconnect())
            assert session_routes.get_user_message_reply_status(session.id, saved.id, "main", db) == {"status": "ready"}
            db.add(MessageModel(
                session_id=session.id, branch_id="main", speaker_type="narrator",
                content="已经回复", parent_message_id=saved.id,
            ))
            db.commit()
            assert session_routes.get_user_message_reply_status(session.id, saved.id, "main", db) == {"status": "review"}
            with pytest.raises(HTTPException) as already_replied:
                session_routes.generate_stream(session.id, payload, db)
            assert already_replied.value.status_code == 409
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 2
    finally:
        engine.dispose()
