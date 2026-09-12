import asyncio
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.models import ChatSessionModel, MessageModel, StoryRequestReceiptModel
from backend.app.routes.story_simulations import router
from backend.app.schemas import StoryWritingRequest
from backend.app.services import story_simulation_service
from backend.app.services.story_simulation_service import StoryGenerationCancelled, create_story_session
from backend.app.services.story_request_receipt import RECEIPT_VERSION, payload_hash

VALID_JSON = '{"title":"雨夜旧塔","chapters":[{"title":"第一章","content":"钟声穿过雾港。"},{"title":"第二章","content":"门后传来脚步。"}],"next_choices":["进入旧塔","离开码头"]}'


class FakeClient:
    def __init__(self):
        self.calls = 0
        self.failure = None
        self.started = None
        self.release = None
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    async def create(self, **_kwargs):
        self.calls += 1
        if self.started is not None:
            self.started.set()
        if self.release is not None:
            await self.release.wait()
        if self.failure is not None:
            error, self.failure = self.failure, None
            raise error
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=VALID_JSON))])

    async def close(self):
        pass


@pytest.fixture
def local_app(tmp_path):
    local_engine = create_engine(f"sqlite:///{tmp_path / 'story-receipts.sqlite3'}", connect_args={"check_same_thread": False})
    LocalSession = sessionmaker(bind=local_engine, autoflush=False, autocommit=False, expire_on_commit=False)
    Base.metadata.create_all(bind=local_engine)
    Base.metadata.create_all(bind=local_engine)
    app = FastAPI()
    app.include_router(router, prefix="/api")

    def override_db():
        db = LocalSession()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_db
    yield app, LocalSession
    app.dependency_overrides.clear()
    local_engine.dispose()


def payload(request_id="11111111-1111-4111-8111-111111111111", premise="雾港的钟在午夜倒走"):
    return {"request_id": request_id, "premise": premise, "direction": "调查旧塔", "tone": "克制悬疑", "chapter_count": 2, "character_ids": []}


def install_fake(monkeypatch, fake):
    monkeypatch.setattr(story_simulation_service, "build_public_async_text_client", lambda _db: (fake, "offline-test"))


def test_repeated_request_returns_receipt_without_second_model_call(local_app, monkeypatch):
    app, Session = local_app
    fake = FakeClient()
    install_fake(monkeypatch, fake)
    with TestClient(app) as client:
        first = client.post("/api/story-simulations", json=payload())
        second = client.post("/api/story-simulations", json=payload())
    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()
    assert fake.calls == 1
    with Session() as db:
        assert len(db.scalars(select(ChatSessionModel)).all()) == 1
        assert db.scalar(select(StoryRequestReceiptModel)) is not None


def test_different_payload_same_id_is_409(local_app, monkeypatch):
    app, _ = local_app
    fake = FakeClient()
    install_fake(monkeypatch, fake)
    with TestClient(app) as client:
        assert client.post("/api/story-simulations", json=payload()).status_code == 200
        response = client.post("/api/story-simulations", json=payload(premise="另一份故事"))
    assert response.status_code == 409
    assert fake.calls == 1


def test_deleted_session_and_reused_id_is_410(local_app, monkeypatch):
    app, Session = local_app
    fake = FakeClient()
    install_fake(monkeypatch, fake)
    request = payload()
    with TestClient(app) as client:
        assert client.post("/api/story-simulations", json=request).status_code == 200
        with Session() as db:
            session = db.scalar(select(ChatSessionModel))
            old_id = session.id
            db.delete(session)
            db.commit()
            replacement = ChatSessionModel(title="复用会话")
            db.add(replacement)
            db.commit()
            assert replacement.id == old_id
        response = client.post("/api/story-simulations", json=request)
    assert response.status_code == 410
    assert fake.calls == 1


def test_failure_and_cancellation_leave_no_receipt_and_can_retry(local_app, monkeypatch):
    app, Session = local_app
    fake = FakeClient()
    fake.failure = RuntimeError("fixture failure")
    install_fake(monkeypatch, fake)
    request = payload()
    with TestClient(app, raise_server_exceptions=False) as client:
        assert client.post("/api/story-simulations", json=request).status_code == 400
        assert client.post("/api/story-simulations", json=request).status_code == 200
    assert fake.calls == 2
    with Session() as db:
        assert db.scalar(select(StoryRequestReceiptModel)) is not None

    fake2 = FakeClient()
    install_fake(monkeypatch, fake2)

    async def disconnected():
        return True

    cancelled_request = StoryWritingRequest(**payload(request_id="22222222-2222-4222-8222-222222222222"))
    with Session() as db:
        with pytest.raises(StoryGenerationCancelled):
            asyncio.run(create_story_session(db, cancelled_request, is_disconnected=disconnected))
        assert db.scalar(select(StoryRequestReceiptModel).where(StoryRequestReceiptModel.request_id.like("2222%"))) is None
        assert asyncio.run(create_story_session(db, cancelled_request)).session_id > 0
        assert fake2.calls == 1


def test_same_id_in_progress_is_409_and_guard_is_released(local_app, monkeypatch):
    _, Session = local_app
    fake = FakeClient()
    fake.started = asyncio.Event()
    fake.release = asyncio.Event()
    install_fake(monkeypatch, fake)
    request = StoryWritingRequest(**payload(request_id="33333333-3333-4333-8333-333333333333"))

    async def run():
        first_db, second_db = Session(), Session()
        try:
            first = asyncio.create_task(create_story_session(first_db, request))
            await fake.started.wait()
            with pytest.raises(Exception) as error:
                await create_story_session(second_db, request)
            assert getattr(error.value, "status_code", None) == 409
            fake.release.set()
            assert (await first).session_id > 0
        finally:
            first_db.close()
            second_db.close()

    asyncio.run(run())
    assert fake.calls == 1


def test_toctou_receipt_created_between_precheck_and_claim_is_reused(local_app, monkeypatch):
    _, Session = local_app
    fake = FakeClient()
    install_fake(monkeypatch, fake)
    request = StoryWritingRequest(**payload(request_id="55555555-5555-4555-8555-555555555555"))
    original_claim = story_simulation_service.claim_request
    inserted = False

    async def claim_after_other_worker(request_id):
        nonlocal inserted
        if not inserted:
            inserted = True
            with Session() as db:
                session = ChatSessionModel(title="另一进程已完成")
                db.add(session)
                db.flush()
                db.add(StoryRequestReceiptModel(
                    request_id=request_id,
                    receipt_version=RECEIPT_VERSION,
                    payload_hash=payload_hash(request),
                    session_id=session.id,
                    session_created_at=session.created_at,
                    result_json={"session_id": session.id, "title": session.title, "chapter_count": 2},
                ))
                db.commit()
        await original_claim(request_id)

    monkeypatch.setattr(story_simulation_service, "claim_request", claim_after_other_worker)
    with Session() as db:
        result = asyncio.run(create_story_session(db, request))
        assert result.title == "另一进程已完成"
    assert fake.calls == 0


def test_commit_failure_rolls_back_all_rows_and_create_all_is_repeatable(local_app, monkeypatch):
    app, Session = local_app
    fake = FakeClient()
    install_fake(monkeypatch, fake)
    request = payload(request_id="44444444-4444-4444-8444-444444444444")
    with Session() as db:
        db.execute(text("CREATE TRIGGER reject_story_receipt BEFORE INSERT ON story_request_receipts BEGIN SELECT RAISE(ABORT, 'fixture failure'); END"))
        db.commit()
    with TestClient(app, raise_server_exceptions=False) as client:
        assert client.post("/api/story-simulations", json=request).status_code == 500
        with Session() as db:
            assert db.scalar(select(ChatSessionModel)) is None
            assert db.scalar(select(MessageModel)) is None
            assert db.scalar(select(StoryRequestReceiptModel)) is None
            db.execute(text("DROP TRIGGER reject_story_receipt"))
            db.commit()
        assert client.post("/api/story-simulations", json=request).status_code == 200
