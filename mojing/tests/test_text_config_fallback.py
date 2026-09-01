"""角色文字线路回退契约；测试不访问任何上游网络。"""

import os
import sys

import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from backend.app.database import Base
from backend.app.models import ChatSessionModel, CharacterModel, SessionParticipantModel
from backend.app.services import channel_service, system_config_service
from backend.app.services.chat_service import build_group_prompt
from backend.app.services.llm_client import build_client, resolve_text_config
from backend.app.services.think_max_model import resolve_think_max_chat_model


def _character(**overrides) -> CharacterModel:
    values = {
        "name": "继承配置角色",
        "persona_prompt": "",
        "api_key": "",
        "api_base_url": "https://api.deepseek.com",
        "model_name": "deepseek-chat",
    }
    values.update(overrides)
    return CharacterModel(**values)


def _public_config(**overrides) -> dict:
    values = {
        "public_text_api_key": "public-key-for-test",
        "public_text_base_url": "https://example.invalid/openai",
        "public_text_model": "public-model",
    }
    values.update(overrides)
    return values


def test_character_key_keeps_character_route():
    resolved = resolve_text_config(
        _character(api_key="character-key-for-test", api_base_url="https://role.invalid", model_name="role-model")
    )

    assert resolved.source == "character"
    assert resolved.api_key == "character-key-for-test"
    assert resolved.base_url == "https://role.invalid"
    assert resolved.model == "role-model"


@pytest.mark.parametrize("placeholder", ["", "https://api.deepseek.com", "https://api.deepseek.com/v1/"])
def test_character_without_key_inherits_public_route(monkeypatch, placeholder):
    monkeypatch.setattr(system_config_service, "get_local_config", lambda _db: _public_config())
    monkeypatch.setattr(channel_service, "_get_default_channel", lambda _purpose: None)

    resolved = resolve_text_config(_character(api_base_url=placeholder), object())

    assert resolved.source == "public"
    assert resolved.api_key == "public-key-for-test"
    assert resolved.base_url == "https://example.invalid/openai"
    assert resolved.model == "public-model"


def test_custom_character_base_can_reuse_public_key(monkeypatch):
    monkeypatch.setattr(system_config_service, "get_local_config", lambda _db: _public_config())
    monkeypatch.setattr(channel_service, "_get_default_channel", lambda _purpose: None)

    resolved = resolve_text_config(
        _character(api_base_url="http://127.0.0.1:9999", model_name="local-model"),
        object(),
    )

    assert resolved.source == "public"
    assert resolved.base_url == "http://127.0.0.1:9999"
    assert resolved.model == "local-model"


def test_ollama_route_needs_no_key_and_has_single_v1_root():
    character = _character(
        api_base_url="http://127.0.0.1:11434",
        model_name="qwen2",
    )

    resolved = resolve_text_config(character)
    client = build_client(character)

    assert resolved.source == "character"
    assert resolved.api_key == "ollama"
    assert str(client.base_url).rstrip("/") == "http://127.0.0.1:11434/v1"


def test_missing_effective_key_returns_actionable_error(monkeypatch):
    monkeypatch.setattr(
        system_config_service,
        "get_local_config",
        lambda _db: _public_config(public_text_api_key=""),
    )
    monkeypatch.setattr(channel_service, "_get_default_channel", lambda _purpose: None)

    with pytest.raises(HTTPException) as exc_info:
        resolve_text_config(_character(), object())

    assert exc_info.value.status_code == 400
    assert "设置 → 公共 API" in exc_info.value.detail
    assert "角色 → 联网配置" in exc_info.value.detail


def test_chat_model_follows_public_route_when_character_uses_placeholder():
    resolved = resolve_think_max_chat_model(
        _character(),
        None,
        {"public_text_model": "public-model", "allow_session_think_max": False},
        default_model="fallback-model",
    )

    assert resolved == "public-model"


def test_first_turn_prompt_always_defines_character_book_section(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'prompt.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)

    with Session() as db:
        character = _character(name="首轮角色")
        session = ChatSessionModel(title="首轮会话")
        db.add_all([character, session])
        db.flush()
        db.add(SessionParticipantModel(session_id=session.id, character_id=character.id, sort_order=0))
        db.commit()

        messages, debug = build_group_prompt(db, session.id, character)

    assert "【角色世界书命中】" in messages[0]["content"]
    assert debug["prompt_debug"]["character_book_hits"] == []
