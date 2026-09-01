import pytest

from backend.app.models import CharacterModel
from backend.app.services.llm_client import build_client


class FakeOpenAI:
    def __init__(self, api_key: str, base_url: str):
        self.api_key = api_key
        self.base_url = base_url


def test_build_client_uses_public_text_config_and_normalizes_chat_path(monkeypatch):
    captured = {}

    def fake_openai(**kwargs):
        captured.update(kwargs)
        return FakeOpenAI(**kwargs)

    monkeypatch.setattr("backend.app.services.llm_client.OpenAI", fake_openai)
    monkeypatch.setattr("backend.app.services.channel_service._get_default_channel", lambda channel_type: None)
    monkeypatch.setattr(
        "backend.app.services.system_config_service.get_local_config",
        lambda db: {
            "public_text_api_key": "public-key",
            "public_text_base_url": "https://api.siliconflow.cn/v1/chat/completions",
            "public_text_model": "Qwen/Qwen2.5-7B-Instruct",
        },
    )
    character = CharacterModel(
        name="公共配置角色",
        persona_prompt="",
        api_key="",
        api_base_url="",
        model_name="",
    )

    client = build_client(character, db=object())

    assert isinstance(client, FakeOpenAI)
    assert captured["api_key"] == "public-key"
    assert captured["base_url"] == "https://api.siliconflow.cn/v1"


def test_build_client_normalizes_character_base_url(monkeypatch):
    captured = {}

    def fake_openai(**kwargs):
        captured.update(kwargs)
        return FakeOpenAI(**kwargs)

    monkeypatch.setattr("backend.app.services.llm_client.OpenAI", fake_openai)
    character = CharacterModel(
        name="角色线路",
        persona_prompt="",
        api_key="character-key",
        api_base_url="https://www.dmxapi.cn/v1/chat/completions",
        model_name="gpt-5.4-mini",
    )

    build_client(character)

    assert captured["api_key"] == "character-key"
    assert captured["base_url"] == "https://www.dmxapi.cn/v1"
