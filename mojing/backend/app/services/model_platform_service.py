"""Versioned local platform catalog. Keys never travel in chat selections."""
from __future__ import annotations

import asyncio
from contextlib import suppress
from urllib.parse import urlsplit

import httpx
from fastapi import HTTPException
from pydantic import BaseModel, Field, field_validator
from sqlalchemy.orm import Session

from .crypto_service import decrypt_api_key, is_secret_mask, mask_secret, prepare_secret_for_storage
from .system_config_service import DEFAULT_LOCAL_CONFIG, LOCAL_CONFIG_KEY, get_setting, set_setting

CATALOG_KEY = "model_platforms_v1"


class PlatformWrite(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    base_url: str
    api_key: str = ""
    models: list[str] = Field(default_factory=list, max_length=10000)
    selected_model: str = ""

    @field_validator("name", "selected_model")
    @classmethod
    def trim(cls, value: str) -> str:
        return value.strip()

    @field_validator("base_url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        value = value.strip().rstrip("/")
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("请输入不含凭据、查询参数的 HTTP(S) API 地址")
        return value

    @field_validator("models")
    @classmethod
    def clean_models(cls, value: list[str]) -> list[str]:
        return list(dict.fromkeys(item.strip() for item in value if item.strip()))


class ModelSelection(BaseModel):
    platform_id: str
    model: str


class ModelChoiceWrite(BaseModel):
    selection: ModelSelection | None = None


class DiscoverRequest(PlatformWrite):
    name: str = ""
    platform_id: str | None = None


def get_catalog(db: Session) -> dict:
    raw = get_setting(db, CATALOG_KEY, {})
    if raw:
        if raw.get("version") != 1 or not isinstance(raw.get("platforms"), list):
            raise HTTPException(409, "平台配置格式无法读取，原数据已保留。")
        ids = []
        try:
            for platform in raw["platforms"]:
                PlatformWrite.model_validate(platform)
                if not isinstance(platform.get("id"), str) or not platform["id"] or "api_key" not in platform:
                    raise ValueError()
                ids.append(platform["id"])
            if len(ids) != len(set(ids)) or (raw.get("active_id") and raw["active_id"] not in ids):
                raise ValueError()
        except (ValueError, KeyError, TypeError):
            raise HTTPException(409, "平台配置校验失败，原数据已保留。") from None
        return raw
    # A read-only projection until the first explicit save; retain the old row
    # as a rollback point and avoid creating duplicates on repeated reads.
    legacy = get_setting(db, LOCAL_CONFIG_KEY, DEFAULT_LOCAL_CONFIG)
    if not any(legacy.get(f"public_text_{key}") for key in ("api_key", "base_url", "model")):
        return {"version": 1, "active_id": None, "platforms": []}
    model = str(legacy.get("public_text_model") or "").strip()
    return {"version": 1, "active_id": "legacy", "platforms": [{
        "id": "legacy", "name": "原有文字平台", "base_url": legacy.get("public_text_base_url") or "https://api.deepseek.com",
        "api_key": legacy.get("public_text_api_key") or "", "models": [model] if model else [], "selected_model": model,
    }]}


def public_catalog(db: Session) -> dict:
    catalog = get_catalog(db)
    return {**catalog, "platforms": [{**p, "api_key": mask_secret(decrypt_api_key(p["api_key"]))} for p in catalog["platforms"]]}


def draft_key(db: Session, platform_id: str | None, value: PlatformWrite) -> str:
    old = next((p for p in get_catalog(db)["platforms"] if p["id"] == platform_id), None)
    if is_secret_mask(value.api_key):
        if not old or old["base_url"].rstrip("/") != value.base_url:
            raise HTTPException(400, "地址已更改，请为该平台重新填写 Key。")
        return decrypt_api_key(old["api_key"])
    return value.api_key.strip()


def save_platform(db: Session, platform_id: str, value: PlatformWrite) -> dict:
    if not platform_id or len(platform_id) > 100:
        raise HTTPException(400, "平台标识无效")
    if not value.name or not value.models or value.selected_model not in value.models:
        raise HTTPException(400, "请填写平台名称、模型列表，并选择列表中的默认模型。")
    catalog = get_catalog(db)
    secret = draft_key(db, platform_id, value)
    if not secret:
        raise HTTPException(400, "请填写该平台的 Key。")
    stored = {**value.model_dump(), "id": platform_id, "api_key": prepare_secret_for_storage(secret)}
    platforms = [stored if p["id"] == platform_id else {**p, "api_key": prepare_secret_for_storage(decrypt_api_key(p["api_key"]))} for p in catalog["platforms"]]
    if not any(p["id"] == platform_id for p in catalog["platforms"]):
        platforms.append(stored)
    set_setting(db, CATALOG_KEY, {**catalog, "active_id": catalog["active_id"] or platform_id, "platforms": platforms})
    return public_catalog(db)


def resolve_selection(db: Session, selection: ModelSelection):
    from .llm_client import ResolvedTextConfig
    platform = next((p for p in get_catalog(db)["platforms"] if p["id"] == selection.platform_id), None)
    if not platform or selection.model not in platform["models"]:
        raise HTTPException(409, "所选平台或模型已变更，请重新选择。")
    key = decrypt_api_key(platform["api_key"])
    if not key:
        raise HTTPException(400, "所选平台尚未填写 Key。")
    return ResolvedTextConfig(key, platform["base_url"], selection.model, "session")


def set_default_platform(db: Session, platform_id: str) -> dict:
    catalog = get_catalog(db)
    platform = next((p for p in catalog["platforms"] if p["id"] == platform_id), None)
    if not platform:
        raise HTTPException(404, "平台不存在")
    resolve_selection(db, ModelSelection(platform_id=platform_id, model=platform["selected_model"]))
    set_setting(db, CATALOG_KEY, {**catalog, "active_id": platform_id})
    return public_catalog(db)


def get_model_choice(db: Session, session_id: int) -> dict:
    raw = get_setting(db, f"chat_model_choice_{session_id}", {"version": 1, "selection": None})
    if raw.get("version") != 1:
        raise HTTPException(409, "会话模型配置版本无法读取，原数据已保留。")
    try:
        ModelChoiceWrite.model_validate(raw)
    except ValueError:
        raise HTTPException(409, "会话模型配置无法读取，原数据已保留。") from None
    return raw


async def discover_models(base_url: str, key: str, *, transport=None, is_disconnected=None) -> list[str]:
    task = asyncio.create_task(_discover_models(base_url, key, transport=transport))
    try:
        if is_disconnected is None:
            return await task
        while True:
            done, _ = await asyncio.wait({task}, timeout=0.1)
            if task in done:
                return task.result()
            if await is_disconnected():
                raise HTTPException(499, "模型获取已取消。")
    finally:
        if not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task


async def _discover_models(base_url: str, key: str, *, transport=None) -> list[str]:
    from .openai_compatible_routing import normalize_base, uses_versioned_root
    root = normalize_base(base_url)
    if not uses_versioned_root(root):
        root += "/v1"
    anthropic = urlsplit(base_url).hostname == "api.anthropic.com"
    headers = {"x-api-key": key, "anthropic-version": "2023-06-01"} if anthropic else {"Authorization": f"Bearer {key}"}
    models: dict[str, None] = {}
    cursors: set[str] = set()
    params: dict[str, str] = {}
    try:
        async with asyncio.timeout(60), httpx.AsyncClient(timeout=20, follow_redirects=False, transport=transport) as client:
            for _ in range(100):
                response = await client.get(f"{root}/models", headers=headers, params=params)
                if response.status_code != 200:
                    raise HTTPException(502, f"获取模型失败（HTTP {response.status_code}），可手动填写模型名称。")
                data = response.json()
                if not isinstance(data, dict) or not isinstance(data.get("data"), list):
                    raise ValueError()
                for item in data["data"]:
                    if isinstance(item, dict) and isinstance(item.get("id"), str) and item["id"].strip():
                        models[item["id"].strip()] = None
                if not data.get("has_more"):
                    if not models:
                        raise HTTPException(502, "平台未返回模型，可手动填写模型名称。")
                    return list(models)
                cursor = data.get("last_id")
                if not isinstance(cursor, str) or not cursor or cursor in cursors:
                    raise ValueError()
                cursors.add(cursor)
                params = {"after_id" if anthropic else "after": cursor}
    except (httpx.HTTPError, TimeoutError):
        raise HTTPException(502, "获取模型超时或连接中断，可重试或手动填写。") from None
    except (ValueError, TypeError):
        raise HTTPException(502, "平台返回的模型列表格式无法识别，可手动填写。") from None
    raise HTTPException(502, "模型列表分页未结束，请手动填写所需模型。")
