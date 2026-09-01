import asyncio
import json

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.main import app
from backend.app.models import ChatSessionModel, CharacterModel, MessageModel, WorldEncyclopediaModel, WorldTemplateModel
from backend.app.services import story_simulation_service as service
from backend.app.services.story_rules import sanitize_story_choice_tags


@pytest.fixture()
def client(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'story.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)

    def override_get_db():
        db = Session()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    class FakeAsyncClient:
        async def close(self):
            return None

    monkeypatch.setattr(service, "build_public_async_text_client", lambda db: (FakeAsyncClient(), "test-model"))
    monkeypatch.setattr(
        service,
        "safe_async_non_streaming_call",
        lambda *args, **kwargs: asyncio.sleep(
            0,
            result=json.dumps(
                {
                    "title": "十八岁系统",
                    "chapters": [
                        {"title": "觉醒之日", "content": "林默在高考前夕听见了系统提示音。"},
                        {"title": "第一个任务", "content": "任务把他引向一场正在发生的意外。"},
                    ],
                    "next_choices": ["调查系统来源", "先完成紧急任务", "向好友试探真相"],
                },
                ensure_ascii=False,
            ),
        ),
    )
    yield TestClient(app), Session
    app.dependency_overrides.clear()


def test_parse_story_draft_accepts_code_fence_and_legacy_content_name():
    draft = service.parse_story_draft(
        '```json\n{"title":"夜渡","chapters":['
        '{"title":"第一章","narrative":"主角在暴雨中渡河。"}],'
        '"next_choices":["追查渡船","进入城门"]}\n```',
        1,
    )
    assert draft.title == "夜渡"
    assert draft.chapters[0].content == "主角在暴雨中渡河。"
    assert draft.next_choices == ["追查渡船", "进入城门"]


def test_story_choices_respect_private_system_and_mundane_world_constraints():
    draft = service.parse_story_draft(
        json.dumps(
            {
                "title": "系统人生",
                "chapters": [{"title": "第一章", "content": "正文"}],
                "next_choices": [
                    "告诉好友系统真相",
                    "觉醒魔法",
                    "继续隐藏系统完成任务",
                    "独自调查系统来源",
                ],
            },
            ensure_ascii=False,
        ),
        1,
        "普通都市世界，没有额外的力量。只有主角拥有系统，别人都不知道。",
    )

    assert draft.next_choices == ["继续隐藏系统完成任务", "独自调查系统来源"]


def test_story_choice_tags_remove_secret_disclosure_before_message_is_saved():
    content = (
        "<NARRATION>正文</NARRATION><CHOICES>"
        "<OPTION>女主得知主角的系统秘密</OPTION>"
        "<OPTION>主角继续隐瞒系统</OPTION></CHOICES>"
    )

    sanitized = sanitize_story_choice_tags(content, "普通都市，只有主角知道系统，其他人不知道。")

    assert "女主得知" not in sanitized
    assert "主角继续隐瞒系统" in sanitized


def test_create_story_session_generates_novel_and_enters_chat(client):
    http, Session = client
    with Session() as db:
        character = CharacterModel(name="林默", persona_prompt="十八岁，谨慎但愿意帮助别人。")
        template = WorldTemplateModel(template_id="modern", label="现代都市", world_prompt="现代社会。")
        encyclopedia = WorldEncyclopediaModel(name="人物资料", world_prompt="故事发生在临江市。")
        db.add_all([character, template, encyclopedia])
        db.commit()
        character_id = character.id
        encyclopedia_id = encyclopedia.id

    response = http.post(
        "/api/story-simulations",
        json={
            "premise": "现代社会，主角十八岁觉醒系统。",
            "direction": "从觉醒当天开始",
            "tone": "都市成长",
            "chapter_count": 2,
            "template_id": "modern",
            "encyclopedia_id": encyclopedia_id,
            "character_ids": [character_id],
        },
    )
    assert response.status_code == 200
    assert response.json()["status"] == "created"
    assert response.json()["chapter_count"] == 2

    with Session() as db:
        session = db.scalar(select(ChatSessionModel))
        assert session is not None
        assert session.world.gameplay_mode == "小说创作"
        assert session.world.narrator_name == "小说作者"
        assert [participant.character_id for participant in session.participants] == [character_id]
        messages = list(db.scalars(select(MessageModel).where(MessageModel.session_id == session.id).order_by(MessageModel.id)))
        assert [message.speaker_type for message in messages] == ["user", "narrator", "narrator"]
        assert messages[-1].structured_content["choices"] == ["调查系统来源", "先完成紧急任务", "向好友试探真相"]


def test_generation_failure_does_not_leave_half_created_session(client, monkeypatch):
    http, Session = client
    monkeypatch.setattr(
        service,
        "build_public_async_text_client",
        lambda db: (_ for _ in ()).throw(HTTPException(status_code=400, detail="未配置 API Key")),
    )
    response = http.post(
        "/api/story-simulations",
        json={"premise": "故事背景", "chapter_count": 1, "character_ids": []},
    )
    assert response.status_code == 400
    with Session() as db:
        assert db.scalar(select(func.count()).select_from(ChatSessionModel)) == 0


def test_client_disconnect_cancels_upstream_and_leaves_no_session(client, monkeypatch):
    _, Session = client
    upstream_cancelled = False
    checks = 0

    async def blocking_call(*args, **kwargs):
        nonlocal upstream_cancelled
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            upstream_cancelled = True
            raise

    async def disconnected():
        nonlocal checks
        checks += 1
        return checks >= 2

    monkeypatch.setattr(service, "safe_async_non_streaming_call", blocking_call)
    with Session() as db:
        with pytest.raises(service.StoryGenerationCancelled):
            asyncio.run(
                service.create_story_session(
                    db,
                    service.StoryWritingRequest(premise="故事背景", chapter_count=1, character_ids=[]),
                    is_disconnected=disconnected,
                )
            )
        assert db.scalar(select(func.count()).select_from(ChatSessionModel)) == 0
    assert upstream_cancelled is True


def test_disconnect_after_model_result_still_prevents_persistence(client):
    _, Session = client
    checks = 0

    async def disconnected():
        nonlocal checks
        checks += 1
        return checks >= 2

    with Session() as db:
        with pytest.raises(service.StoryGenerationCancelled):
            asyncio.run(
                service.create_story_session(
                    db,
                    service.StoryWritingRequest(premise="故事背景", chapter_count=2, character_ids=[]),
                    is_disconnected=disconnected,
                )
            )
        assert db.scalar(select(func.count()).select_from(ChatSessionModel)) == 0
