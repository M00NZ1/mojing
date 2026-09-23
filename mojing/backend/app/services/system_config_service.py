from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AppSettingModel
from .crypto_service import decrypt_api_key, mask_secret, prepare_secret_for_storage


LOCAL_CONFIG_KEY = "local_config"
DEFAULT_LOCAL_CONFIG = {
    "default_world_template_id": "custom",
    "default_narrator_enabled": False,
    "default_choice_generation_enabled": True,
    "default_anti_cheat_enabled": True,
    "memory_compact_threshold": 12,
    "max_upload_mb": 20,
    "max_auto_speakers": 2,
    "public_text_api_key": "",
    "public_text_base_url": "",
    "public_text_model": "",
    "public_image_api_key": "",
    "public_image_base_url": "",
    "public_image_model": "dall-e-3",
    "public_voice_api_key": "",
    "public_voice_base_url": "",
    "public_voice_model": "",
    "allow_session_think_max": False,
    "think_max_model": "deepseek-reasoner",
}
LOCAL_CONFIG_SECRET_FIELDS = {
    "public_text_api_key",
    "public_image_api_key",
    "public_voice_api_key",
}
VOICE_SERVICE_CONFIG_KEY = "voice_service_config"
DEFAULT_VOICE_SERVICE_CONFIG = {
    "mode": "builtin_only",
    "external_base_url": "",
    "external_api_key": "",
    "clone_endpoint": "/clone",
    "timeout_seconds": 120,
    "enabled": False,
    # 单次 XTTS（Coqui）线程执行上限；超时后对白会降级为 Edge TTS（见 voice_service）
    "xtts_timeout_seconds": 90,
    # 单段旁白/内心 Edge TTS 上限，避免 worker 被慢网络拖死
    "edge_tts_timeout_seconds": 120,
    # 整条消息多段合成总上限（HTTP 层可再包一层，见 routes/voices）
    "message_synthesis_total_timeout_seconds": 300,
}
VOICE_SERVICE_SECRET_FIELDS = {"external_api_key"}


def effective_memory_compact_threshold(value: object) -> int:
    """The Web compactor reads at most 40 messages per bounded batch."""
    return value if type(value) is int and 10 <= value <= 40 else 12


def get_setting(db: Session, key: str, default_value: dict) -> dict:
    row = db.scalar(select(AppSettingModel).where(AppSettingModel.key == key))
    if row is None:
        return default_value.copy()
    value = row.value_json or {}
    merged = default_value.copy()
    merged.update(value)
    return merged


def set_setting(db: Session, key: str, value: dict) -> dict:
    row = db.scalar(select(AppSettingModel).where(AppSettingModel.key == key))
    if row is None:
        row = AppSettingModel(key=key, value_json=value)
        db.add(row)
    else:
        row.value_json = value
    db.commit()
    return value


def get_local_config(db: Session) -> dict:
    result = _decrypt_config(
        get_setting(db, LOCAL_CONFIG_KEY, DEFAULT_LOCAL_CONFIG),
        LOCAL_CONFIG_SECRET_FIELDS,
    )
    result["memory_compact_threshold"] = effective_memory_compact_threshold(
        result.get("memory_compact_threshold")
    )
    # Once a catalog is saved it owns the public text route. Legacy fields
    # remain untouched as a rollback point and for older clients.
    from .model_platform_service import CATALOG_KEY, get_catalog
    if get_setting(db, CATALOG_KEY, {}):
        catalog = get_catalog(db)
        active = next((p for p in catalog["platforms"] if p["id"] == catalog["active_id"]), None)
        if active:
            result.update(public_text_api_key=decrypt_api_key(active["api_key"]),
                          public_text_base_url=active["base_url"], public_text_model=active["selected_model"])
    return result


def set_local_config(
    db: Session,
    value: dict,
    *,
    clear_secret_fields: set[str] | None = None,
) -> dict:
    stored = _prepare_config_for_storage(
        db,
        LOCAL_CONFIG_KEY,
        DEFAULT_LOCAL_CONFIG,
        value,
        LOCAL_CONFIG_SECRET_FIELDS,
        clear_secret_fields=clear_secret_fields or set(),
    )
    stored["memory_compact_threshold"] = effective_memory_compact_threshold(
        stored.get("memory_compact_threshold")
    )
    from .model_platform_service import CATALOG_KEY
    if get_setting(db, CATALOG_KEY, {}):
        original = get_setting(db, LOCAL_CONFIG_KEY, DEFAULT_LOCAL_CONFIG)
        for field in ("public_text_api_key", "public_text_base_url", "public_text_model"):
            stored[field] = original[field]
    set_setting(db, LOCAL_CONFIG_KEY, stored)
    return get_local_config(db)


def get_voice_service_config(db: Session) -> dict:
    return _decrypt_config(
        get_setting(db, VOICE_SERVICE_CONFIG_KEY, DEFAULT_VOICE_SERVICE_CONFIG),
        VOICE_SERVICE_SECRET_FIELDS,
    )


def set_voice_service_config(
    db: Session,
    value: dict,
    *,
    clear_secret_fields: set[str] | None = None,
) -> dict:
    stored = _prepare_config_for_storage(
        db,
        VOICE_SERVICE_CONFIG_KEY,
        DEFAULT_VOICE_SERVICE_CONFIG,
        value,
        VOICE_SERVICE_SECRET_FIELDS,
        clear_secret_fields=clear_secret_fields or set(),
    )
    set_setting(db, VOICE_SERVICE_CONFIG_KEY, stored)
    return _decrypt_config(stored, VOICE_SERVICE_SECRET_FIELDS)


def mask_local_config_for_api(value: dict) -> dict:
    return _mask_config(value, LOCAL_CONFIG_SECRET_FIELDS)


def mask_voice_service_config_for_api(value: dict) -> dict:
    return _mask_config(value, VOICE_SERVICE_SECRET_FIELDS)


def _decrypt_config(value: dict, secret_fields: set[str]) -> dict:
    result = value.copy()
    for field in secret_fields:
        result[field] = decrypt_api_key(str(result.get(field) or ""))
    return result


def _mask_config(value: dict, secret_fields: set[str]) -> dict:
    result = value.copy()
    for field in secret_fields:
        result[field] = mask_secret(str(result.get(field) or ""))
    return result


def _prepare_config_for_storage(
    db: Session,
    key: str,
    defaults: dict,
    value: dict,
    secret_fields: set[str],
    *,
    clear_secret_fields: set[str],
) -> dict:
    current = get_setting(db, key, defaults)
    result = value.copy()
    for field in secret_fields:
        if field in clear_secret_fields:
            result[field] = ""
        elif field not in result or not str(result.get(field) or ""):
            result[field] = str(current.get(field) or "")
        else:
            result[field] = prepare_secret_for_storage(
                str(result[field]),
                existing=str(current.get(field) or ""),
            )
    return result
