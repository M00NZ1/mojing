from __future__ import annotations

import io
import zipfile

from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session, sessionmaker

from backend.app.database import get_db
from backend.app.models import (
    Base,
    CharacterModel,
    CharacterProfileModel,
    ChatSessionModel,
    MessageModel,
    SessionBranchModel,
    SessionMemoryCorrectionModel,
    SessionParticipantModel,
    SessionWorldModel,
)
from backend.app.routes.sessions import router as sessions_router
from backend.app.services import export_service
from backend.app.services.session_exchange_service import (
    SessionExchangeError,
    import_session_archive,
)


def _db(tmp_path):
    engine = create_engine(
        f"sqlite:///{tmp_path / 'exchange.db'}",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    return engine


def _seed(db: Session):
    session = ChatSessionModel(title="可移植会话", summary="总结", think_max_enabled=True)
    character = CharacterModel(
        name="夜航",
        persona_prompt="角色原始提示",
        api_key="secret-api-key",
        api_base_url="http://127.0.0.1:9999/private-endpoint",
        avatar_image_path="C:/private/avatar.png",
        voice_api_key="secret-voice-key",
        image_gen_api_key="secret-image-key",
    )
    db.add_all([session, character])
    db.flush()
    db.add(CharacterProfileModel(character_id=character.id, raw_persona_text="完整角色设定"))
    db.add(SessionParticipantModel(session_id=session.id, character_id=character.id, sort_order=0))
    db.add(SessionWorldModel(
        session_id=session.id,
        template_id="unknown-template",
        world_prompt="世界规则",
        gameplay_mode="自由剧情",
        world_timeline_json=[{"title": "第一幕"}],
        auto_sediment_enabled=False,
        sediment_interval=33,
    ))
    first = MessageModel(session_id=session.id, character_id=character.id, content="第一条")
    second = MessageModel(session_id=session.id, character_id=character.id, content="第二条")
    db.add_all([first, second])
    db.flush()
    second.parent_message_id = first.id
    second.regenerated_from_message_id = first.id
    db.add(SessionBranchModel(
        session_id=session.id,
        branch_id="alternate",
        label="另一条路",
        source_message_id=first.id,
        parent_branch_id="main",
        is_checkpoint=True,
        checkpoint_label="重要节点",
    ))
    db.add(SessionMemoryCorrectionModel(
        session_id=session.id,
        branch_id="alternate",
        content="用户纠正",
        source_message_id=first.id,
    ))
    db.commit()
    return session.id


def _archive_bytes(db: Session, session_id: int, tmp_path, monkeypatch) -> bytes:
    storage = tmp_path / "storage"
    monkeypatch.setattr(export_service, "STORAGE_DIR", storage)
    monkeypatch.setattr(export_service, "EXPORT_ROOT", storage / "conversations")
    path = export_service.build_session_export_archive(db, session_id)
    return path.read_bytes()


def _without_manifest(data: bytes) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(output, "w") as target:
        for item in source.infolist():
            if item.filename.endswith("/manifest.json"):
                continue
            target.writestr(item, source.read(item))
    return output.getvalue()


def test_session_exchange_round_trip_remaps_ids_and_does_not_import_secrets(tmp_path, monkeypatch):
    engine = _db(tmp_path)
    with Session(engine) as db:
        source_id = _seed(db)
        archive = _archive_bytes(db, source_id, tmp_path, monkeypatch)
        assert b"secret-api-key" not in archive
        assert b"secret-voice-key" not in archive
        assert b"secret-image-key" not in archive
        assert b"C:/private/avatar.png" not in archive
        assert b"private-endpoint" not in archive

        result = import_session_archive(db, archive)
        imported = db.get(ChatSessionModel, result["session_id"])
        assert imported is not None and imported.id != source_id
        assert result == {
            "session_id": imported.id,
            "title": "可移植会话",
            "message_count": 2,
            "character_count": 1,
            "branch_count": 1,
        }
        character = db.scalar(
            select(CharacterModel).join(SessionParticipantModel).where(SessionParticipantModel.session_id == imported.id)
        )
        assert character.name == "夜航（导入）"
        assert character.api_key == ""
        assert character.avatar_image_path == ""
        assert character.voice_api_key == ""
        messages = list(db.scalars(select(MessageModel).where(MessageModel.session_id == imported.id).order_by(MessageModel.id)))
        assert messages[1].parent_message_id == messages[0].id
        assert messages[1].regenerated_from_message_id == messages[0].id
        branch = db.scalar(select(SessionBranchModel).where(SessionBranchModel.session_id == imported.id))
        correction = db.scalar(select(SessionMemoryCorrectionModel).where(SessionMemoryCorrectionModel.session_id == imported.id))
        assert branch.source_message_id == messages[0].id
        assert bool(branch.is_checkpoint) is True
        assert branch.checkpoint_label == "重要节点"
        assert correction.source_message_id == messages[0].id
        assert correction.branch_id == "alternate"
        world = db.scalar(select(SessionWorldModel).where(SessionWorldModel.session_id == imported.id))
        assert world.template_id == "custom"
        assert world.world_timeline_json == [{"title": "第一幕"}]
        assert bool(world.auto_sediment_enabled) is False
        assert world.sediment_interval == 33


def test_session_exchange_accepts_legacy_export_without_manifest(tmp_path, monkeypatch):
    engine = _db(tmp_path)
    with Session(engine) as db:
        source_id = _seed(db)
        archive = _without_manifest(_archive_bytes(db, source_id, tmp_path, monkeypatch))
        result = import_session_archive(db, archive)
        assert result["message_count"] == 2
        assert result["character_count"] == 1


def test_session_exchange_http_accepts_file_field_and_commits(tmp_path, monkeypatch):
    engine = _db(tmp_path)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    with SessionFactory() as db:
        source_id = _seed(db)
        archive = _archive_bytes(db, source_id, tmp_path, monkeypatch)

    app = FastAPI()
    app.include_router(sessions_router, prefix="/api")

    def override_db():
        with SessionFactory() as db:
            yield db

    app.dependency_overrides[get_db] = override_db
    try:
        with TestClient(app) as client:
            response = client.post(
                "/api/sessions/import-archive",
                files={"file": ("session.zip", archive, "application/zip")},
            )
        assert response.status_code == 200
        imported_id = response.json()["session_id"]
        with SessionFactory() as db:
            assert db.get(ChatSessionModel, imported_id) is not None
    finally:
        app.dependency_overrides.clear()
        engine.dispose()


def test_invalid_archive_rolls_back_all_created_rows(tmp_path, monkeypatch):
    engine = _db(tmp_path)
    with Session(engine) as db:
        source_id = _seed(db)
        archive = _archive_bytes(db, source_id, tmp_path, monkeypatch)
        broken = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(archive)) as source, zipfile.ZipFile(broken, "w") as target:
            for item in source.infolist():
                raw = source.read(item)
                if item.filename.endswith("/meta/branches.json"):
                    raw = b'[{"branch_id":"broken","source_message_id":999999,"parent_branch_id":"main"}]'
                target.writestr(item, raw)
        before = len(list(db.scalars(select(ChatSessionModel))))
        try:
            import_session_archive(db, broken.getvalue())
        except SessionExchangeError:
            pass
        else:
            raise AssertionError("损坏引用应被拒绝")
        assert len(list(db.scalars(select(ChatSessionModel)))) == before


def test_illegal_zip_is_rejected_without_database_change(tmp_path):
    engine = _db(tmp_path)
    with Session(engine) as db:
        before = len(list(db.scalars(select(ChatSessionModel))))
        try:
            import_session_archive(db, b"not a zip")
        except SessionExchangeError:
            pass
        else:
            raise AssertionError("非法 ZIP 应被拒绝")
        assert len(list(db.scalars(select(ChatSessionModel)))) == before
