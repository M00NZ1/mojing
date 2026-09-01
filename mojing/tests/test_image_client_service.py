"""image_client_service：提示词与凭证解析（不启动 FastAPI、不连库）。"""

import os
import sys
from unittest.mock import MagicMock

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from backend.app.services.image_client_service import (
    build_character_card_image_prompt,
    resolve_character_image_credentials,
    resolve_public_image_credentials,
)
from backend.app.models import CharacterModel


def test_build_encyclopedia_entry_cover_prompt():
    from backend.app.services.image_client_service import build_encyclopedia_entry_cover_prompt

    p = build_encyclopedia_entry_cover_prompt(title="古城", entry_type="location", summary="石桥与集市", prompt_hint="黄昏")
    assert "古城" in p and "location" in p and "黄昏" in p


def test_build_prompt_contains_name_and_truncates_persona():
    long = "x" * 2000
    p = build_character_card_image_prompt(name="测试", persona_prompt=long, prompt_hint="rain")
    assert "测试" in p
    assert "rain" in p
    assert len(p) < len(long) + 500


def test_resolve_public_image_missing_raises(monkeypatch):
    from backend.app.services import image_client_service as mod

    monkeypatch.setattr(mod, "get_local_config", lambda _db: {"public_image_api_key": "", "public_image_base_url": "", "public_image_model": ""})
    db = MagicMock()
    with pytest.raises(Exception) as ei:
        resolve_public_image_credentials(db)
    assert ei.value.status_code == 400


def test_resolve_character_prefers_public_when_image_gen_off():
    db = MagicMock()
    db.get = MagicMock()
    char = CharacterModel(
        name="A",
        image_gen_enabled=False,
        image_gen_api_key="",
        image_gen_base_url="",
        image_gen_model="dall-e-3",
        api_key="",
        api_base_url="",
    )
    with pytest.raises(Exception):
        resolve_character_image_credentials(db, char)


def test_resolve_character_uses_image_gen_when_enabled_and_complete(monkeypatch):
    from backend.app.services import image_client_service as mod

    monkeypatch.setattr(
        mod,
        "get_local_config",
        lambda _db: {
            "public_image_api_key": "",
            "public_image_base_url": "",
            "public_image_model": "dall-e-3",
        },
    )
    monkeypatch.setattr(mod, "decrypt_api_key", lambda k: k or "")

    db = MagicMock()
    char = CharacterModel(
        name="A",
        image_gen_enabled=True,
        image_gen_api_key="sk-test",
        image_gen_base_url="https://api.openai.com/v1",
        image_gen_model="dall-e-3",
        api_key="",
        api_base_url="",
    )
    k, b, m = resolve_character_image_credentials(db, char)
    assert k == "sk-test"
    assert "openai" in b
    assert m == "dall-e-3"
