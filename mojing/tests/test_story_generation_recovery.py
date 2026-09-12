import asyncio
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.models import (CharacterModel, ChatSessionModel, MessageModel, StoryGenerationDraftModel,
                                StoryRequestReceiptModel, WorldEncyclopediaModel, WorldTemplateModel)
from backend.app.routes.story_simulations import router
from backend.app.schemas import StoryWritingRequest
from backend.app.services import story_simulation_service
from backend.app.services.story_simulation_service import StoryGenerationCancelled, create_story_session
from backend.app.services.story_request_receipt import RECEIPT_VERSION, claim_request, payload_hash, release_request

VALID = '{"title":"雨夜旧塔","chapters":[{"title":"第一章","content":"钟声穿过雾港。"},{"title":"第二章","content":"门后传来脚步。"}],"next_choices":["进入旧塔","离开码头"]}'


class FakeClient:
    def __init__(self):
        self.calls = 0
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    async def create(self, **_kwargs):
        self.calls += 1
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=VALID))])

    async def close(self):
        pass


@pytest.fixture
def local_app(tmp_path):
    local_engine = create_engine(f"sqlite:///{tmp_path / 'story-generation.sqlite3'}", connect_args={"check_same_thread": False})
    Session = sessionmaker(bind=local_engine, autoflush=False, autocommit=False, expire_on_commit=False)
    Base.metadata.create_all(local_engine)
    Base.metadata.create_all(local_engine)
    app = FastAPI()
    app.include_router(router, prefix="/api")

    def override_db():
        db = Session()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_db
    yield app, Session
    app.dependency_overrides.clear()
    local_engine.dispose()


def request(request_id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", premise="雾港的钟在午夜倒走"):
    return {"request_id": request_id, "premise": premise, "direction": "调查旧塔", "tone": "悬疑", "chapter_count": 2, "character_ids": []}


def fake(monkeypatch, client):
    monkeypatch.setattr(story_simulation_service, "build_public_async_text_client", lambda _db: (client, "offline"))


def test_cancel_after_complete_draft_keeps_status_and_replay_avoids_model(local_app, monkeypatch):
    app, Session = local_app
    client = FakeClient()
    fake(monkeypatch, client)
    body = request()
    async def disconnected():
        with Session() as inspection:
            return inspection.scalar(select(StoryGenerationDraftModel)) is not None
    with Session() as db:
        with pytest.raises(StoryGenerationCancelled):
            asyncio.run(create_story_session(db, StoryWritingRequest(**body), is_disconnected=disconnected))
        assert db.scalar(select(StoryGenerationDraftModel)) is not None
    with TestClient(app) as http:
        status = http.get(f"/api/story-simulations/requests/{body['request_id']}")
        assert status.status_code == 200 and status.json()["status"] == "draft"
        replay = http.post("/api/story-simulations", json=body)
        assert replay.status_code == 200
    assert client.calls == 1  # The completed first generation is reused during replay.
    with Session() as db:
        assert db.scalar(select(StoryGenerationDraftModel)) is None
        assert db.scalar(select(StoryRequestReceiptModel)) is not None


def test_draft_payload_mismatch_is_409_and_corrupt_version_is_rejected(local_app, monkeypatch):
    app, Session = local_app
    body = request(request_id="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    with Session() as db:
        db.add(StoryGenerationDraftModel(
            request_id=body["request_id"], draft_version=999, payload_hash=payload_hash(StoryWritingRequest(**body)),
            payload_json={"premise": body["premise"]}, context_text="保留的上下文", draft_json={"title": "坏版本"},
        ))
        db.commit()
    with TestClient(app) as http:
        response = http.get(f"/api/story-simulations/requests/{body['request_id']}")
    assert response.status_code == 409


def test_receipt_failure_leaves_recoverable_draft_and_no_session(local_app, monkeypatch):
    app, Session = local_app
    client = FakeClient()
    fake(monkeypatch, client)
    body = request(request_id="cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    with Session() as db:
        db.execute(text("CREATE TRIGGER reject_receipt BEFORE INSERT ON story_request_receipts BEGIN SELECT RAISE(ABORT, 'fixture'); END"))
        db.commit()
    with TestClient(app, raise_server_exceptions=False) as http:
        assert http.post("/api/story-simulations", json=body).status_code == 503
    with Session() as db:
        assert db.scalar(select(ChatSessionModel)) is None
        assert db.scalar(select(MessageModel)) is None
        assert db.scalar(select(StoryGenerationDraftModel)) is not None


def test_explicit_delete_removes_only_draft_and_is_idempotent(local_app):
    app, Session = local_app
    draft_id = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    with Session() as db:
        session = ChatSessionModel(title="既有会话")
        db.add(session)
        db.flush()
        db.add(StoryRequestReceiptModel(
            request_id="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            receipt_version=RECEIPT_VERSION,
            payload_hash="e" * 64,
            session_id=session.id,
            session_created_at=session.created_at,
            result_json={"session_id": session.id, "title": session.title, "chapter_count": 2},
        ))
        db.add(StoryGenerationDraftModel(
            request_id=draft_id, draft_version=999, payload_hash="d" * 64,
            payload_json={}, context_text="保留", draft_json={"损坏": True},
        ))
        db.commit()
    with TestClient(app) as http:
        assert http.delete(f"/api/story-simulations/requests/{draft_id}/draft").json() == {"deleted": True}
        assert http.delete(f"/api/story-simulations/requests/{draft_id}/draft").json() == {"deleted": True}
    with Session() as db:
        assert db.get(StoryGenerationDraftModel, draft_id) is None
        assert db.scalar(select(ChatSessionModel)) is not None
        assert db.scalar(select(StoryRequestReceiptModel)) is not None


def test_explicit_delete_is_409_while_generation_claimed(local_app):
    app, _ = local_app
    request_id = "ffffffff-ffff-4fff-8fff-ffffffffffff"
    asyncio.run(claim_request(request_id))
    try:
        with TestClient(app) as http:
            response = http.delete(f"/api/story-simulations/requests/{request_id}/draft")
        assert response.status_code == 409
    finally:
        asyncio.run(release_request(request_id))


def test_explicit_delete_cannot_remove_completed_receipt(local_app):
    app, Session = local_app
    request_id = "99999999-9999-4999-8999-999999999999"
    with Session() as db:
        session = ChatSessionModel(title="已完成")
        db.add(session)
        db.flush()
        db.add(StoryRequestReceiptModel(
            request_id=request_id, receipt_version=RECEIPT_VERSION,
            payload_hash="9" * 64, session_id=session.id,
            session_created_at=session.created_at,
            result_json={"session_id": session.id, "title": session.title, "chapter_count": 2},
        ))
        db.commit()
    with TestClient(app) as http:
        response = http.delete(f"/api/story-simulations/requests/{request_id}/draft")
    assert response.status_code == 409
    with Session() as db:
        assert db.scalar(select(StoryRequestReceiptModel)) is not None
        assert db.scalar(select(ChatSessionModel)) is not None


def test_replay_keeps_context_but_filters_deleted_references(local_app, monkeypatch):
    app, Session = local_app
    fake_client = FakeClient()
    fake(monkeypatch, fake_client)
    body = request(request_id="12121212-1212-4121-8121-121212121212")
    body.update({"template_id": "old-template", "encyclopedia_id": 9, "character_ids": [8]})
    with Session() as db:
        template = WorldTemplateModel(template_id="old-template", label="旧模板", world_prompt="旧上下文")
        encyclopedia = WorldEncyclopediaModel(name="旧百科", world_prompt="旧百科上下文")
        character = CharacterModel(name="旧角色", persona_prompt="旧角色上下文")
        db.add_all([template, encyclopedia, character])
        db.commit()
        db.delete(template)
        db.delete(encyclopedia)
        db.delete(character)
        db.commit()
        db.add(StoryGenerationDraftModel(
            request_id=body["request_id"], draft_version=1,
            payload_hash=payload_hash(StoryWritingRequest(**body)),
            payload_json={k: v for k, v in body.items() if k != "request_id"},
            context_text="已删除资料的完整原始上下文",
            draft_json={"title": "暂存故事", "chapters": [{"number": 1, "title": "第一章", "content": "正文"}, {"number": 2, "title": "第二章", "content": "后文"}], "next_choices": ["继续", "离开"]},
        ))
        db.commit()
    with TestClient(app) as http:
        response = http.post("/api/story-simulations", json=body)
    assert response.status_code == 200
    assert fake_client.calls == 0
    with Session() as db:
        session = db.scalar(select(ChatSessionModel))
        assert session.world.template_id == "custom"
        assert session.world.encyclopedia_id is None
        assert db.scalar(select(StoryGenerationDraftModel)) is None
