import asyncio
from types import SimpleNamespace

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base
from backend.app.models import CharacterModel, ChatSessionModel, MessageModel
from backend.app.routes import sessions as session_routes
from backend.app.schemas import GenerateRequest
from backend.app.services import chat_service
from backend.app.services.llm_client import ResolvedTextConfig


class FakeDb:
    def __init__(self, character, session, world):
        self.character = character
        self.session = session
        self.world = world
        self.closed = False
        self.info = {}
        self.rollbacks = 0

    def get(self, model, row_id):
        if model is CharacterModel:
            return self.character
        if model is ChatSessionModel:
            return self.session
        raise AssertionError(f"unexpected model lookup: {model}")

    def scalar(self, statement):
        return self.world

    def refresh(self, row, attribute_names=None):
        return None

    def close(self):
        self.closed = True

    def rollback(self):
        self.rollbacks += 1


class FakeNarratorDb(FakeDb):
    def __init__(self, character, world):
        super().__init__(character, SimpleNamespace(id=3), world)
        self.scalar_results = iter((world, character))

    def scalar(self, statement):
        return next(self.scalar_results)


def test_stream_character_reply_saves_message_without_world(monkeypatch):
    character = SimpleNamespace(
        id=7,
        name="测试角色",
        temperature=0.7,
        max_tokens=512,
        top_p=1.0,
        frequency_penalty=0.0,
        presence_penalty=0.0,
        top_k=0,
        repetition_penalty=1.0,
    )
    session = SimpleNamespace(id=3)
    db = FakeDb(character, session, world=None)
    delta = SimpleNamespace(
        content="<SPEECH>我在。</SPEECH>\n可选行动：\n1. 继续交谈\n2. 暂时离开"
    )
    response = [
        SimpleNamespace(choices=[SimpleNamespace(delta=delta)], usage=None),
        SimpleNamespace(choices=[], usage=SimpleNamespace(prompt_tokens=20, completion_tokens=8)),
    ]
    saved_message = SimpleNamespace(id=11)
    saved_payload = {}
    recorded = {}
    route = ResolvedTextConfig("test-key", "https://api.deepseek.com", "selected-model", "session",
                               platform_id="platform-b", platform_name="平台 B")

    monkeypatch.setattr(chat_service, "SessionLocal", lambda: db)
    monkeypatch.setattr(chat_service, "resolve_branch_context", lambda *args: None)
    monkeypatch.setattr(chat_service, "build_group_prompt", lambda *args: ([], {}))
    monkeypatch.setattr(chat_service, "resolve_text_config", lambda *args: route)
    monkeypatch.setattr(chat_service, "match_saved_platform", lambda db, resolved: resolved)
    monkeypatch.setattr(chat_service, "build_client", lambda *args: object())
    monkeypatch.setattr(chat_service, "get_local_config", lambda *args: {})
    monkeypatch.setattr(chat_service, "resolve_think_max_chat_model", lambda *args, **kwargs: "local-model")
    monkeypatch.setattr(chat_service, "safe_streaming_call", lambda *args, **kwargs: response)
    monkeypatch.setattr(chat_service, "get_price_snapshot", lambda *args: {"currency": "CNY", "input_per_million": 1, "output_per_million": 2})
    monkeypatch.setattr(chat_service, "record_llm_call", lambda *args, **kwargs: recorded.update(kwargs))
    monkeypatch.setattr(chat_service, "_maybe_generate_inline_image", lambda *args: (args[1], []))
    monkeypatch.setattr(chat_service, "get_visible_tail_message_id", lambda *args: 10)

    def save_message(*args, **kwargs):
        saved_payload.update(kwargs)
        return saved_message

    monkeypatch.setattr(chat_service, "create_message", save_message)
    monkeypatch.setattr(
        chat_service,
        "serialize_message",
        lambda message: SimpleNamespace(model_dump=lambda **kwargs: {"id": message.id}),
    )

    events = list(chat_service.stream_character_reply(3, 7, "main", text_config=route))

    assert not [event for event in events if event["type"] == "error"]
    assert events[-1] == {
        "type": "message_end",
        "stream_key": events[-1]["stream_key"],
        "character_id": 7,
        "character_name": "测试角色",
        "message": {"id": 11},
    }
    assert saved_payload["content"] == "<SPEECH>我在。</SPEECH>"
    assert saved_payload["structured_content"]["choices"] == ["继续交谈", "暂时离开"]
    assert recorded["platform_id"] == "platform-b"
    assert recorded["model_name"] == "selected-model"
    assert recorded["provider"] == "deepseek"
    assert recorded["message_id"] == 11
    assert (recorded["prompt_tokens"], recorded["completion_tokens"], recorded["usage_source"]) == (20, 8, "api")
    assert db.closed is True


def test_stream_narrator_reply_saves_choices_outside_story_content(monkeypatch):
    character = SimpleNamespace(
        temperature=0.7,
        max_tokens=512,
        top_p=1.0,
        frequency_penalty=0.0,
        presence_penalty=0.0,
        top_k=0,
        repetition_penalty=1.0,
    )
    world = SimpleNamespace(
        narrator_enabled=True,
        narrator_name="墨境旁白",
        gameplay_mode="自由对话",
        world_prompt="",
    )
    db = FakeNarratorDb(character, world)
    delta = SimpleNamespace(content="夜幕降临。\n\n**下一步**\n- 进入旅店\n- 沿河继续走")
    response = [SimpleNamespace(choices=[SimpleNamespace(delta=delta)], usage=None)]
    saved_message = SimpleNamespace(id=12)
    saved_payload = {}
    recorded = {}
    route = ResolvedTextConfig("test-key", "https://example.invalid", "local-model", "session",
                               platform_id="narrator-platform", platform_name="旁白平台")

    monkeypatch.setattr(chat_service, "SessionLocal", lambda: db)
    monkeypatch.setattr(chat_service, "resolve_branch_context", lambda *args: None)
    monkeypatch.setattr(chat_service, "build_narrator_prompt", lambda *args: ([], {}))
    monkeypatch.setattr(chat_service, "resolve_text_config", lambda *args: route)
    monkeypatch.setattr(chat_service, "match_saved_platform", lambda db, resolved: resolved)
    monkeypatch.setattr(chat_service, "build_client", lambda *args: object())
    monkeypatch.setattr(chat_service, "resolve_text_model", lambda *args, **kwargs: "local-model")
    monkeypatch.setattr(chat_service, "safe_streaming_call", lambda *args, **kwargs: response)
    monkeypatch.setattr(chat_service, "get_price_snapshot", lambda *args: None)
    monkeypatch.setattr(chat_service, "record_llm_call", lambda *args, **kwargs: recorded.update(kwargs))
    monkeypatch.setattr(chat_service, "get_visible_tail_message_id", lambda *args: 11)

    def save_message(*args, **kwargs):
        saved_payload.update(kwargs)
        return saved_message

    monkeypatch.setattr(chat_service, "create_message", save_message)
    monkeypatch.setattr(
        chat_service,
        "serialize_message",
        lambda message: SimpleNamespace(model_dump=lambda **kwargs: {"id": message.id}),
    )

    events = list(chat_service.stream_narrator_reply(3, "main", text_config=route))

    assert not [event for event in events if event["type"] == "error"]
    assert events[-1]["type"] == "message_end"
    assert saved_payload["content"] == "夜幕降临。"
    assert saved_payload["structured_content"]["choices"] == ["进入旅店", "沿河继续走"]
    assert recorded["message_id"] == 12
    assert recorded["platform_id"] == "narrator-platform"
    assert recorded["usage_source"] == "estimated"
    assert recorded["completion_tokens"] > 0
    assert db.closed is True


def _interrupted_stream_fixture(monkeypatch, *, narrator, response):
    character = SimpleNamespace(
        id=7, name="测试角色", temperature=0.7, max_tokens=512, top_p=1.0,
        frequency_penalty=0.0, presence_penalty=0.0, top_k=0, repetition_penalty=1.0,
    )
    world = SimpleNamespace(narrator_enabled=True, narrator_name="墨境旁白") if narrator else None
    db = FakeNarratorDb(character, world) if narrator else FakeDb(character, SimpleNamespace(id=3), world)
    route = ResolvedTextConfig("test-key", "https://example.invalid", "local-model", "session")
    saved = []
    recorded = []

    monkeypatch.setattr(chat_service, "SessionLocal", lambda: db)
    monkeypatch.setattr(chat_service, "resolve_branch_context", lambda *args: None)
    monkeypatch.setattr(chat_service, "build_group_prompt", lambda *args: ([], {}))
    monkeypatch.setattr(chat_service, "build_narrator_prompt", lambda *args: ([], {}))
    monkeypatch.setattr(chat_service, "resolve_text_config", lambda *args: route)
    monkeypatch.setattr(chat_service, "match_saved_platform", lambda db, resolved: resolved)
    monkeypatch.setattr(chat_service, "build_client", lambda *args: object())
    monkeypatch.setattr(chat_service, "get_local_config", lambda *args: {})
    monkeypatch.setattr(chat_service, "resolve_think_max_chat_model", lambda *args, **kwargs: "local-model")
    monkeypatch.setattr(chat_service, "resolve_text_model", lambda *args, **kwargs: "local-model")
    monkeypatch.setattr(chat_service, "safe_streaming_call", lambda *args, **kwargs: response)
    monkeypatch.setattr(chat_service, "get_price_snapshot", lambda *args: None)
    monkeypatch.setattr(chat_service, "get_visible_tail_message_id", lambda *args: 10)
    monkeypatch.setattr(chat_service, "record_llm_call", lambda *args, **kwargs: recorded.append(kwargs))

    def save_message(*args, **kwargs):
        saved.append(kwargs)
        return SimpleNamespace(id=11)

    monkeypatch.setattr(chat_service, "create_message", save_message)
    monkeypatch.setattr(
        chat_service, "serialize_message",
        lambda message: SimpleNamespace(model_dump=lambda **kwargs: {"id": message.id}),
    )
    return db, route, saved, recorded


def test_character_stream_error_saves_received_text_before_error_event(monkeypatch):
    def chunks():
        delta = SimpleNamespace(content="<SPEECH>我在。<CHOICES><OPTION>未完")
        yield SimpleNamespace(choices=[SimpleNamespace(delta=delta)], usage=None)
        raise OSError("connection lost")

    db, route, saved, recorded = _interrupted_stream_fixture(monkeypatch, narrator=False, response=chunks())
    events = list(chat_service.stream_character_reply(3, 7, "story-a", text_config=route))

    assert [event["type"] for event in events] == ["message_start", "delta", "error"]
    assert events[-1]["reply_saved"] is True
    assert events[-1]["saved_message"] == {"id": 11}
    assert len(saved) == 1
    assert saved[0]["content"] == "我在。"
    assert saved[0]["branch_id"] == "story-a"
    assert saved[0]["structured_content"]["interrupted"] is True
    assert saved[0]["structured_content"]["choices"] == []
    assert recorded[0]["message_id"] == 11
    assert recorded[0]["success"] is False
    assert db.closed is True


def test_stream_error_without_text_does_not_claim_a_saved_reply(monkeypatch):
    def chunks():
        raise OSError("connection lost")
        yield  # Keep this as an iterator that fails before producing a chunk.

    db, route, saved, recorded = _interrupted_stream_fixture(monkeypatch, narrator=False, response=chunks())
    events = list(chat_service.stream_character_reply(3, 7, "main", text_config=route))

    assert events[-1]["type"] == "error"
    assert events[-1]["reply_saved"] is False
    assert events[-1]["saved_message"] is None
    assert saved == []
    assert recorded[0]["message_id"] is None
    assert db.closed is True


def test_closing_narrator_stream_saves_received_text_once(monkeypatch):
    delta = SimpleNamespace(content="<NARRATION>夜色落下。")
    closed = []

    def chunks():
        try:
            yield SimpleNamespace(choices=[SimpleNamespace(delta=delta)], usage=None)
            yield SimpleNamespace(choices=[], usage=None)
        finally:
            closed.append(True)

    db, route, saved, recorded = _interrupted_stream_fixture(monkeypatch, narrator=True, response=chunks())
    stream = chat_service.stream_narrator_reply(3, "story-a", text_config=route)

    assert next(stream)["type"] == "message_start"
    assert next(stream)["type"] == "delta"
    stream.close()

    assert len(saved) == 1
    assert saved[0]["content"] == "夜色落下。"
    assert saved[0]["speaker_type"] == "narrator"
    assert saved[0]["branch_id"] == "story-a"
    assert saved[0]["structured_content"]["interrupted"] is True
    assert recorded[0]["message_id"] == 11
    assert recorded[0]["success"] is False
    assert closed == [True]
    assert db.closed is True


def test_stream_route_stops_after_a_speaker_error(monkeypatch):
    db = FakeDb(None, SimpleNamespace(id=3), None)
    seen = []
    closed = []
    compacted = []

    def replies(session_id, character_id, branch_id, **kwargs):
        seen.append(character_id)
        try:
            yield {"type": "message_start", "stream_key": "first"}
            yield {"type": "error", "stream_key": "first", "message": "连接中断"}
        finally:
            closed.append(character_id)

    monkeypatch.setattr(session_routes, "get_model_choice", lambda *args: {"selection": None})
    monkeypatch.setattr(session_routes, "resolve_branch_context", lambda *args: None)
    monkeypatch.setattr(session_routes, "stream_character_reply", replies)
    monkeypatch.setattr(session_routes, "trigger_memory_compaction_async", lambda *args: compacted.append(True))

    response = session_routes.generate_stream(3, GenerateRequest(character_ids=[7, 8]), db)
    async def collect():
        return [chunk async for chunk in response.body_iterator]

    chunks = asyncio.run(collect())

    assert len(chunks) == 4
    assert seen == [7]
    assert closed == [7]
    assert compacted == []


def test_stream_route_close_releases_active_reply(monkeypatch):
    db = FakeDb(None, SimpleNamespace(id=3), None)
    closed = []

    def replies(session_id, character_id, branch_id, **kwargs):
        try:
            yield {"type": "message_start", "stream_key": "first"}
            yield {"type": "delta", "stream_key": "first", "delta": "已收到"}
            yield {"type": "message_end", "stream_key": "first"}
        finally:
            closed.append(True)

    monkeypatch.setattr(session_routes, "get_model_choice", lambda *args: {"selection": None})
    monkeypatch.setattr(session_routes, "resolve_branch_context", lambda *args: None)
    monkeypatch.setattr(session_routes, "stream_character_reply", replies)

    response = session_routes.generate_stream(3, GenerateRequest(character_ids=[7]), db)

    async def close_after_delta():
        stream = response.body_iterator
        for _ in range(4):
            await stream.__anext__()
        await stream.aclose()

    asyncio.run(close_after_delta())
    assert closed == [True]


def test_client_disconnect_closes_route_and_saves_received_reply(monkeypatch):
    provider_closed = []

    def chunks():
        try:
            delta = SimpleNamespace(content="<SPEECH>已收到。")
            yield SimpleNamespace(choices=[SimpleNamespace(delta=delta)], usage=None)
            yield SimpleNamespace(choices=[], usage=None)
        finally:
            provider_closed.append(True)

    db, _, saved, recorded = _interrupted_stream_fixture(monkeypatch, narrator=False, response=chunks())
    route_db = FakeDb(None, SimpleNamespace(id=3), None)
    monkeypatch.setattr(session_routes, "get_model_choice", lambda *args: {"selection": None})
    monkeypatch.setattr(session_routes, "resolve_branch_context", lambda *args: None)
    response = session_routes.generate_stream(
        3, GenerateRequest(character_ids=[7], branch_id="story-a"), route_db,
    )

    async def disconnect_after_delta():
        delta_sent = asyncio.Event()

        async def receive():
            await delta_sent.wait()
            return {"type": "http.disconnect"}

        async def send(message):
            if message["type"] == "http.response.body" and b'"type": "delta"' in message.get("body", b""):
                delta_sent.set()
                await asyncio.Event().wait()

        await asyncio.wait_for(
            response({"type": "http", "asgi": {"version": "3.0"}}, receive, send),
            timeout=3,
        )

    asyncio.run(disconnect_after_delta())
    assert len(saved) == 1
    assert saved[0]["content"] == "已收到。"
    assert saved[0]["branch_id"] == "story-a"
    assert saved[0]["structured_content"]["interrupted"] is True
    assert recorded[0]["success"] is False
    assert provider_closed == [True]
    assert db.closed is True


def test_interrupted_reply_commits_to_isolated_database(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'partial.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    try:
        with Session() as db:
            session = ChatSessionModel(title="partial")
            db.add(session)
            db.commit()
            session_id = session.id
            saved = chat_service._save_interrupted_reply(
                db, session_id, "main", "narrator", "<NARRATION>夜色落下。<CHOICES><OPTION>未完"
            )
            assert saved is not None
            assert chat_service.serialize_message(saved).content == "夜色落下。"
            message_id = saved.id

        with Session() as db:
            persisted = db.scalar(select(MessageModel).where(MessageModel.id == message_id))
            assert persisted is not None
            assert persisted.session_id == session_id
            assert persisted.content == "夜色落下。"
            assert persisted.structured_content["interrupted"] is True
    finally:
        engine.dispose()
