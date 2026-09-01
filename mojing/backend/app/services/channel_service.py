from __future__ import annotations

import json
import os
import threading
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from ..config import STORAGE_DIR, settings
from .crypto_service import decrypt_api_key, mask_secret, prepare_secret_for_storage


CHANNEL_FILE = (
    Path(settings.channel_storage_path).expanduser().resolve()
    if settings.channel_storage_path.strip()
    else STORAGE_DIR / "api_channels.json"
)


class ChannelStorageError(RuntimeError):
    """渠道配置不存在有效 JSON 或无法原子保存。"""


_CHANNEL_LOCK = threading.RLock()


@contextmanager
def channel_storage_lock():
    with _CHANNEL_LOCK:
        yield

PROVIDER_PRESETS: list[dict[str, str]] = [
    {"id": "deepseek", "label": "DeepSeek", "base_url": "https://api.deepseek.com"},
    {"id": "siliconflow", "label": "硅基流动 (SiliconFlow)", "base_url": "https://api.siliconflow.cn/v1"},
    {"id": "volcengine", "label": "火山引擎 (Doubao)", "base_url": "https://ark.cn-beijing.volces.com/api/v3"},
    {"id": "dmxapi", "label": "DMXAPI（聚合）", "base_url": "https://www.dmxapi.cn/v1"},
    {"id": "openai", "label": "OpenAI", "base_url": "https://api.openai.com/v1"},
    {"id": "anthropic", "label": "Anthropic Claude", "base_url": "https://api.anthropic.com"},
    {"id": "ollama", "label": "Ollama (本地)", "base_url": "http://localhost:11434/v1"},
    {"id": "gemini", "label": "Google Gemini", "base_url": "https://generativelanguage.googleapis.com/v1beta/openai/"},
    {"id": "custom", "label": "自定义兼容接口", "base_url": ""},
]

PURPOSE_OPTIONS: list[dict[str, str]] = [
    {"id": "text", "label": "文字对话"},
    {"id": "image", "label": "图片生成"},
    {"id": "voice", "label": "语音合成"},
    {"id": "embedding", "label": "向量嵌入"},
]


def _load_channels_raw() -> list[dict[str, Any]]:
    if CHANNEL_FILE.exists():
        try:
            value = json.loads(CHANNEL_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as exc:
            raise ChannelStorageError("渠道配置无法读取，已停止使用以避免覆盖原文件。") from exc
        if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
            raise ChannelStorageError("渠道配置结构无效，已停止使用以避免覆盖原文件。")
        return value
    return []


def _save_channels(channels: list[dict[str, Any]]) -> None:
    CHANNEL_FILE.parent.mkdir(parents=True, exist_ok=True)
    temp_path = CHANNEL_FILE.parent / f".{CHANNEL_FILE.name}.{uuid.uuid4().hex}.tmp"
    try:
        with temp_path.open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(channels, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, CHANNEL_FILE)
    finally:
        if temp_path.exists():
            temp_path.unlink()


def _load_channels() -> list[dict[str, Any]]:
    channels = _load_channels_raw()
    result: list[dict[str, Any]] = []
    for channel in channels:
        item = channel.copy()
        item["api_key"] = decrypt_api_key(str(item.get("api_key") or ""))
        result.append(item)
    return result


def list_channels() -> list[dict[str, Any]]:
    with channel_storage_lock():
        channels = _load_channels()
        for channel in channels:
            channel["api_key"] = mask_secret(str(channel.get("api_key") or ""))
        return channels


def save_channel(payload: dict[str, Any]) -> dict[str, Any]:
    with channel_storage_lock():
        return _save_channel_locked(payload)


def _save_channel_locked(payload: dict[str, Any]) -> dict[str, Any]:
    payload = payload.copy()
    clear_api_key = bool(payload.pop("clear_api_key", False))
    channels = _load_channels_raw()
    existing = next((c for c in channels if c.get("id") == payload.get("id")), None)
    if existing:
        updated = existing.copy()
        updated.update(payload)
        if clear_api_key:
            updated["api_key"] = ""
        elif "api_key" in payload and str(payload.get("api_key") or ""):
            updated["api_key"] = prepare_secret_for_storage(
                str(payload.get("api_key") or ""),
                existing=str(existing.get("api_key") or ""),
            )
        else:
            updated["api_key"] = str(existing.get("api_key") or "")
        existing.clear()
        existing.update(updated)
        stored = existing
    else:
        stored = payload.copy()
        stored["api_key"] = prepare_secret_for_storage(
            str(payload.get("api_key") or "")
        ) if payload.get("api_key") else ""
        channels.append(stored)
    _save_channels(channels)
    response = stored.copy()
    response["api_key"] = mask_secret(str(stored.get("api_key") or ""))
    return response


def delete_channel(channel_id: str) -> None:
    with channel_storage_lock():
        channels = _load_channels_raw()
        channels = [c for c in channels if c.get("id") != channel_id]
        _save_channels(channels)


def get_provider_presets() -> list[dict[str, str]]:
    return PROVIDER_PRESETS


def get_purpose_options() -> list[dict[str, str]]:
    return PURPOSE_OPTIONS


def _get_default_channel(purpose: str = "text") -> dict[str, str] | None:
    """获取指定用途的第一个已启用渠道，用于 Key 三级回退。"""
    with channel_storage_lock():
        channels = _load_channels()
        for c in channels:
            if c.get("enabled", True) and c.get("purpose") == purpose:
                return {
                    "api_key": c.get("api_key", ""),
                    "base_url": c.get("base_url", ""),
                    "model": c.get("model_name", ""),
                }
    return None
