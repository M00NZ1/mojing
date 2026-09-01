from types import SimpleNamespace

from backend.app.models import CharacterModel, ChatSessionModel
from backend.app.services import chat_service


class FakeDb:
    def __init__(self, character, session, world):
        self.character = character
        self.session = session
        self.world = world
        self.closed = False

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
    response = [SimpleNamespace(choices=[SimpleNamespace(delta=delta)])]
    saved_message = SimpleNamespace(id=11)
    saved_payload = {}

    monkeypatch.setattr(chat_service, "SessionLocal", lambda: db)
    monkeypatch.setattr(chat_service, "resolve_branch_context", lambda *args: None)
    monkeypatch.setattr(chat_service, "build_group_prompt", lambda *args: ([], {}))
    monkeypatch.setattr(chat_service, "build_client", lambda *args: object())
    monkeypatch.setattr(chat_service, "get_local_config", lambda *args: {})
    monkeypatch.setattr(chat_service, "resolve_think_max_chat_model", lambda *args, **kwargs: "local-model")
    monkeypatch.setattr(chat_service, "safe_streaming_call", lambda *args, **kwargs: response)
    monkeypatch.setattr(chat_service, "record_llm_call", lambda *args, **kwargs: None)
    monkeypatch.setattr(chat_service, "detect_provider", lambda *args: "local")
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

    events = list(chat_service.stream_character_reply(3, 7, "main"))

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
    response = [SimpleNamespace(choices=[SimpleNamespace(delta=delta)])]
    saved_message = SimpleNamespace(id=12)
    saved_payload = {}

    monkeypatch.setattr(chat_service, "SessionLocal", lambda: db)
    monkeypatch.setattr(chat_service, "resolve_branch_context", lambda *args: None)
    monkeypatch.setattr(chat_service, "build_narrator_prompt", lambda *args: ([], {}))
    monkeypatch.setattr(chat_service, "build_client", lambda *args: object())
    monkeypatch.setattr(chat_service, "resolve_text_model", lambda *args, **kwargs: "local-model")
    monkeypatch.setattr(chat_service, "safe_streaming_call", lambda *args, **kwargs: response)
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

    events = list(chat_service.stream_narrator_reply(3, "main"))

    assert not [event for event in events if event["type"] == "error"]
    assert events[-1]["type"] == "message_end"
    assert saved_payload["content"] == "夜幕降临。"
    assert saved_payload["structured_content"]["choices"] == ["进入旅店", "沿河继续走"]
    assert db.closed is True
