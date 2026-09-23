from io import BytesIO
from uuid import uuid4

import pytest
from fastapi import HTTPException, UploadFile
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker
from starlette.datastructures import Headers

from backend.app.database import Base
from backend.app.models import ChatSessionModel, MessageAttachmentModel, MessageModel
from backend.app.routes import sessions as session_routes


def _upload(name: str, data: bytes) -> UploadFile:
    return UploadFile(
        file=BytesIO(data),
        filename=name,
        size=len(data),
        headers=Headers({"content-type": "image/png"}),
    )


def _database(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'uploads.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)


def test_chat_upload_accepts_file_at_configured_limit(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path)
    storage = tmp_path / "storage"
    monkeypatch.setattr(session_routes, "STORAGE_DIR", storage)
    monkeypatch.setattr(session_routes, "get_local_config", lambda _db: {"max_upload_mb": 1})
    try:
        with Session() as db:
            session = ChatSessionModel(title="upload")
            db.add(session)
            db.commit()

            result = session_routes.add_user_message_with_files(
                session.id,
                "with image",
                "main",
                [_upload("limit.png", b"x" * (1024 * 1024))],
                db,
            )

            assert result.attachments[0].file_name == "limit.png"
            stored = storage / result.attachments[0].storage_path
            assert stored.stat().st_size == 1024 * 1024
    finally:
        engine.dispose()


def test_chat_upload_rejects_oversize_batch_without_message_or_files(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path)
    storage = tmp_path / "storage"
    monkeypatch.setattr(session_routes, "STORAGE_DIR", storage)
    monkeypatch.setattr(session_routes, "get_local_config", lambda _db: {"max_upload_mb": 1})
    try:
        with Session() as db:
            session = ChatSessionModel(title="upload")
            db.add(session)
            db.commit()

            with pytest.raises(HTTPException) as error:
                session_routes.add_user_message_with_files(
                    session.id,
                    "must rollback",
                    "main",
                    [
                        _upload("first.png", b"ok"),
                        _upload("too-large.png", b"x" * (1024 * 1024 + 1)),
                    ],
                    db,
                )

            assert error.value.status_code == 413
            assert "1 MB" in str(error.value.detail)
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 0
            upload_dir = storage / "uploads" / f"session_{session.id:04d}"
            assert list(upload_dir.iterdir()) == []
    finally:
        engine.dispose()


def test_retried_attachment_send_reuses_original_file_and_rejects_changed_bytes(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path)
    storage = tmp_path / "storage"
    monkeypatch.setattr(session_routes, "STORAGE_DIR", storage)
    send_id = uuid4()
    try:
        with Session() as db:
            session = ChatSessionModel(title="upload")
            db.add(session)
            db.commit()

            first = session_routes.add_user_message_with_files(
                session.id, "正文", "main", [_upload("scene.png", b"first image")], db, send_id,
            )
            repeated = session_routes.add_user_message_with_files(
                session.id, "正文", "main", [_upload("scene.png", b"first image")], db, send_id,
            )
            assert repeated.id == first.id
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 1
            upload_dir = storage / "uploads" / f"session_{session.id:04d}"
            assert len(list(upload_dir.iterdir())) == 1

            with pytest.raises(HTTPException) as conflict:
                session_routes.add_user_message_with_files(
                    session.id, "正文", "main", [_upload("scene.png", b"other image")], db, send_id,
                )
            assert conflict.value.status_code == 409
            assert len(list(upload_dir.iterdir())) == 1
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 1
    finally:
        engine.dispose()


def test_response_failure_does_not_delete_a_committed_attachment(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path)
    storage = tmp_path / "storage"
    monkeypatch.setattr(session_routes, "STORAGE_DIR", storage)
    try:
        with Session() as db:
            session = ChatSessionModel(title="upload")
            db.add(session)
            db.commit()
            monkeypatch.setattr(session_routes, "serialize_message", lambda _message: (_ for _ in ()).throw(RuntimeError("response failed")))

            with pytest.raises(RuntimeError, match="response failed"):
                session_routes.add_user_message_with_files(
                    session.id, "正文", "main", [_upload("scene.png", b"important image")], db, uuid4(),
                )

            assert db.scalar(select(func.count()).select_from(MessageModel)) == 1
            attachment = db.scalar(select(MessageAttachmentModel))
            assert attachment is not None
            assert (storage / attachment.storage_path).read_bytes() == b"important image"
    finally:
        engine.dispose()
