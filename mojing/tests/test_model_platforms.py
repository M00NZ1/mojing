"""Platform persistence and real route contracts; all upstream calls are mocked."""
import asyncio
import json
from pathlib import Path

import httpx
import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from cryptography.fernet import Fernet

from backend.app.config import settings
from backend.app.database import Base, get_db
from backend.app.models import AppSettingModel, CharacterModel, ChatSessionModel, MessageModel
from backend.app.routes import sessions, system
from backend.app.schemas import GenerateRequest
from backend.app.services import crypto_service
from backend.app.services.backup_service import _redact_database_secrets
from backend.app.services.llm_client import resolve_text_config
from backend.app.services.model_platform_service import (
    CATALOG_KEY, DiscoverRequest, ModelChoiceWrite, ModelSelection, PlatformWrite, discover_models,
    draft_key, get_catalog, get_model_choice, public_catalog, resolve_selection,
    save_platform, set_default_platform,
)
from backend.app.services.system_config_service import get_local_config, get_setting, set_local_config, set_setting


@pytest.fixture
def db(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "encryption_key", Fernet.generate_key().decode())
    crypto_service._reset_fernet_cache_for_tests()
    engine = create_engine(f"sqlite:///{tmp_path / 'platforms.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        yield session
    engine.dispose()
    crypto_service._reset_fernet_cache_for_tests()


def platform(name="A", key="fake-key-A", models=None):
    return PlatformWrite(name=name, base_url=f"https://{name.lower()}.invalid/v1", api_key=key, models=models or ["chat", "reason"], selected_model="chat")


def test_lazy_legacy_migration_preserves_original_and_independent_keys(db):
    set_local_config(db, {"public_text_base_url": "https://old.invalid/v1", "public_text_api_key": "fake-old", "public_text_model": "old"})
    original = get_setting(db, "local_config", {})
    assert get_catalog(db) == get_catalog(db)
    assert get_setting(db, CATALOG_KEY, {}) == {}
    save_platform(db, "a", platform())
    save_platform(db, "b", platform("B", "fake-key-B"))
    set_default_platform(db, "b")
    db.expire_all()
    assert [p["id"] for p in get_catalog(db)["platforms"]] == ["legacy", "a", "b"]
    assert resolve_selection(db, ModelSelection(platform_id="a", model="reason")).api_key == "fake-key-A"
    assert resolve_selection(db, ModelSelection(platform_id="b", model="chat")).api_key == "fake-key-B"
    assert get_local_config(db)["public_text_api_key"] == "fake-key-B"
    assert get_setting(db, "local_config", {}) == original
    current = get_local_config(db)
    current["max_auto_speakers"] = 3
    set_local_config(db, current)
    assert get_setting(db, "local_config", {})["public_text_api_key"] == original["public_text_api_key"]
    assert get_local_config(db)["public_text_model"] == "chat"
    assert "fake-key" not in json.dumps(get_setting(db, CATALOG_KEY, {}))
    assert "fake-" not in json.dumps(public_catalog(db))


def test_masked_key_is_bound_to_id_and_address(db):
    save_platform(db, "a", platform())
    masked = public_catalog(db)["platforms"][0]
    value = PlatformWrite.model_validate(masked)
    assert draft_key(db, "a", value) == "fake-key-A"
    save_platform(db, "a", value)
    before = get_catalog(db)
    for changed in (value.model_copy(update={"base_url": "https://other.invalid/v1"}), value):
        with pytest.raises(HTTPException):
            draft_key(db, "a" if changed is not value else "new", changed)
    with pytest.raises(HTTPException):
        save_platform(db, "a", value.model_copy(update={"api_key": ""}))
    assert get_catalog(db) == before


def test_unreadable_version_is_not_overwritten(db):
    set_setting(db, CATALOG_KEY, {"version": 99, "platforms": []})
    with pytest.raises(HTTPException, match="原数据已保留"):
        save_platform(db, "a", platform())
    assert get_setting(db, CATALOG_KEY, {})["version"] == 99
    set_setting(db, "chat_model_choice_1", {"version": 99})
    with pytest.raises(HTTPException):
        get_model_choice(db, 1)


def test_session_choices_and_round_snapshot_are_independent(db, monkeypatch):
    save_platform(db, "a", platform())
    save_platform(db, "b", platform("B", "fake-key-B"))
    first, other = ChatSessionModel(title="First"), ChatSessionModel(title="Other")
    db.add_all([first, other]); db.commit()
    sessions.update_model_choice(first.id, ModelChoiceWrite(selection=ModelSelection(platform_id="a", model="reason")), db)
    assert get_model_choice(db, other.id)["selection"] is None
    calls = []
    def character_stream(session_id, character_id, branch_id, **kwargs):
        calls.append(kwargs["text_config"])
        save_platform(db, "a", platform(key="fake-replaced"))
        sessions.update_model_choice(first.id, ModelChoiceWrite(selection=ModelSelection(platform_id="b", model="chat")), db)
        yield {"type": "delta", "delta": "hello"}
    def narrator_stream(session_id, branch_id, **kwargs):
        calls.append(kwargs["text_config"])
        yield {"type": "delta", "delta": "world"}
    monkeypatch.setattr(sessions, "resolve_branch_context", lambda *a: None)
    monkeypatch.setattr(sessions, "stream_character_reply", character_stream)
    monkeypatch.setattr(sessions, "stream_narrator_reply", narrator_stream)
    monkeypatch.setattr(sessions, "trigger_memory_compaction_async", lambda *a: None)
    response = sessions.generate_stream(first.id, GenerateRequest(character_ids=[1, 2], include_narrator=True), db)
    async def consume():
        return [item async for item in response.body_iterator]
    asyncio.run(consume())
    assert len(calls) == 3
    assert all(c.api_key == "fake-key-A" and c.model == "reason" for c in calls)
    assert all(c is calls[0] for c in calls)
    assert resolve_selection(db, ModelSelection.model_validate(get_model_choice(db, first.id)["selection"])).api_key == "fake-key-B"
    db.info["text_config_override"] = calls[0]
    assert resolve_text_config(CharacterModel(api_key="fake-character-key"), db) is calls[0]


def test_invalid_selection_fails_before_message_write(db):
    session = ChatSessionModel(title="Invalid")
    db.add(session); db.commit()
    set_setting(db, f"chat_model_choice_{session.id}", {"version": 1, "selection": {"platform_id": "missing", "model": "x"}})
    with pytest.raises(HTTPException):
        sessions.generate_stream(session.id, GenerateRequest(user_message="do not write"), db)
    assert not list(db.scalars(select(MessageModel)))


def test_http_endpoints_mask_keys_and_keep_selection_after_reload(db):
    app = FastAPI()
    app.include_router(system.router); app.include_router(sessions.router)
    app.dependency_overrides[get_db] = lambda: db
    chat = ChatSessionModel(title="HTTP")
    db.add(chat); db.commit()
    with TestClient(app) as client:
        response = client.put("/system/model-platforms/a", json=platform().model_dump())
        assert response.status_code == 200
        assert "fake-key" not in response.text
        response = client.put(f"/sessions/{chat.id}/model-choice", json={"selection": {"platform_id": "a", "model": "reason"}})
        assert response.status_code == 200
        db.expire_all()
        assert client.get(f"/sessions/{chat.id}/model-choice").json()["selection"]["model"] == "reason"
        assert client.put(f"/sessions/{chat.id}/model-choice", json={"selection": None}).status_code == 200
        assert client.get(f"/sessions/{chat.id}/model-choice").json()["selection"] is None


def test_public_backup_redacts_nested_platform_keys(db):
    save_platform(db, "a", platform())
    path = db.bind.url.database
    assert _redact_database_secrets(Path(path)) >= 1
    db.expire_all()
    assert get_catalog(db)["platforms"][0]["api_key"] == ""


def test_discovery_all_models_pagination_and_correct_auth():
    assert DiscoverRequest(base_url="https://api.anthropic.com", api_key="fake-key", name="").name == ""
    calls = []
    def handler(request):
        calls.append(request)
        assert request.url.path == "/v1/models"
        assert request.headers["x-api-key"] == "fake-list-key"
        assert "authorization" not in request.headers
        if len(calls) == 1:
            return httpx.Response(200, json={"data": [{"id": "chat"}, {"id": "image"}], "has_more": True, "last_id": "image"})
        assert request.url.params["after_id"] == "image"
        return httpx.Response(200, json={"data": [{"id": "chat"}, {"id": "embedding"}], "has_more": False})
    result = asyncio.run(discover_models("https://api.anthropic.com", "fake-list-key", transport=httpx.MockTransport(handler)))
    assert result == ["chat", "image", "embedding"]


@pytest.mark.parametrize("status", [302, 401, 429, 500])
def test_discovery_errors_are_safe_and_never_follow_redirects(status):
    calls = []
    def handler(request):
        calls.append(request)
        assert request.headers["authorization"] == "Bearer fake-list-key"
        return httpx.Response(status, headers={"location": "https://other.invalid"}, text="fake-list-key should never echo")
    with pytest.raises(HTTPException) as exc:
        asyncio.run(discover_models("https://custom.invalid/v1", "fake-list-key", transport=httpx.MockTransport(handler)))
    assert "fake-list-key" not in exc.value.detail
    assert len(calls) == 1


def test_repeated_cursor_and_cancellation():
    transport = httpx.MockTransport(lambda r: httpx.Response(200, json={"data": [{"id": "x"}], "has_more": True, "last_id": "x"}))
    with pytest.raises(HTTPException):
        asyncio.run(discover_models("https://api.anthropic.com", "fake-key", transport=transport))
    async def cancelled(request):
        raise asyncio.CancelledError()
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(discover_models("https://custom.invalid", "fake-key", transport=httpx.MockTransport(cancelled)))


def test_client_disconnect_cancels_pending_discovery():
    cancelled = []
    async def slow_request(request):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.append(True)
    async def disconnected():
        return True
    with pytest.raises(HTTPException) as exc:
        asyncio.run(discover_models("https://custom.invalid", "fake-key", transport=httpx.MockTransport(slow_request), is_disconnected=disconnected))
    assert exc.value.status_code == 499
    assert cancelled == [True]
