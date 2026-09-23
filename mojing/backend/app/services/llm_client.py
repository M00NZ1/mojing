from __future__ import annotations

import logging
from dataclasses import dataclass

from openai import AsyncOpenAI, OpenAI

from ..models import CharacterModel

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ResolvedTextConfig:
    api_key: str
    base_url: str
    model: str
    source: str
    platform_id: str | None = None
    platform_name: str = ""


def is_placeholder_text_base(base_url: str | None) -> bool:
    """角色卡默认 DeepSeek 地址仅作占位，不应阻止公共线路回退。"""
    normalized = (base_url or "").strip().lower().rstrip("/")
    if normalized.endswith("/v1"):
        normalized = normalized[:-3].rstrip("/")
    return not normalized or normalized == "https://api.deepseek.com"


def is_ollama_text_base(base_url: str | None) -> bool:
    normalized = (base_url or "").strip().lower()
    return "ollama" in normalized or "localhost:11434" in normalized or "127.0.0.1:11434" in normalized


def resolve_text_config(character: CharacterModel, db=None) -> ResolvedTextConfig:
    """解析角色独立配置；未配置独立 Key 时继承公共文字线路。"""
    from .crypto_service import decrypt_api_key

    if db is not None and isinstance(getattr(db, "info", {}).get("text_config_override"), ResolvedTextConfig):
        return db.info["text_config_override"]

    character_key = decrypt_api_key(character.api_key) if character.api_key else ""
    character_base = (character.api_base_url or "").strip()
    character_model = (character.model_name or "").strip()
    if character_key:
        return ResolvedTextConfig(
            api_key=character_key,
            base_url=character_base,
            model=character_model,
            source="character",
        )
    if is_ollama_text_base(character_base):
        return ResolvedTextConfig(
            api_key="ollama",
            base_url=character_base,
            model=character_model,
            source="character",
        )

    custom_character_base = "" if is_placeholder_text_base(character_base) else character_base
    if db is not None:
        from .system_config_service import get_local_config

        config = get_local_config(db)
        public_key = decrypt_api_key(config.get("public_text_api_key", ""))
        if public_key:
            if custom_character_base and custom_character_base.rstrip("/") != str(config.get("public_text_base_url") or "").rstrip("/"):
                from fastapi import HTTPException
                raise HTTPException(400, "角色填写了不同的平台地址，请填写该平台独立 Key，或清空地址以继承默认平台。")
            return ResolvedTextConfig(
                api_key=public_key,
                base_url=custom_character_base or (config.get("public_text_base_url") or "").strip() or character_base,
                model=(
                    character_model
                    if custom_character_base
                    else (config.get("public_text_model") or "").strip() or character_model
                ),
                source="public",
            )

    # 兼容旧版本的本地渠道文件；公共配置是当前产品的首选入口。
    from .channel_service import _get_default_channel

    default_channel = _get_default_channel("text")
    if default_channel and default_channel.get("api_key"):
        if custom_character_base and custom_character_base.rstrip("/") != str(default_channel.get("base_url") or "").rstrip("/"):
            from fastapi import HTTPException
            raise HTTPException(400, "角色平台地址与默认渠道不同，请填写独立 Key。")
        return ResolvedTextConfig(
            api_key=str(default_channel.get("api_key") or ""),
            base_url=custom_character_base or str(default_channel.get("base_url") or "").strip() or character_base,
            model=character_model if custom_character_base else str(default_channel.get("model") or "").strip() or character_model,
            source="channel",
        )

    from fastapi import HTTPException

    raise HTTPException(
        status_code=400,
        detail="未配置可用的文字 API。请前往设置 → 公共 API 填写 Key，或在角色 → 联网配置中填写独立 Key。",
    )


def resolve_text_model(character: CharacterModel, db=None, *, default: str = "deepseek-chat") -> str:
    """返回与实际 Key/Base 来源一致的文字模型。"""
    return resolve_text_config(character, db).model or default


def detect_provider(character: CharacterModel, base_url: str | None = None) -> str:
    """根据本次实际请求地址检测服务商类型。"""
    base = (base_url if base_url is not None else character.api_base_url or "").lower().strip()
    if "api.deepseek.com" in base:
        return "deepseek"
    if "siliconflow.cn" in base:
        return "siliconflow"
    if "anthropic" in base or "claude" in base:
        return "claude"
    if "ollama" in base or "localhost:11434" in base or "127.0.0.1:11434" in base:
        return "ollama"
    if "gemini" in base or "generativelanguage" in base:
        return "gemini"
    return "openai"


def build_public_text_client(db) -> tuple[OpenAI, str]:
    """百科批量生成等无人物上下文场景：仅用 local_config 公共对话线路。"""
    from .system_config_service import get_local_config

    if db is None:
        raise ValueError("db is required for build_public_text_client")
    char = CharacterModel(
        name="_public_text_llm_",
        persona_prompt="",
        api_key="",
        api_base_url="",
        model_name="",
    )
    cfg = get_local_config(db)
    model = (cfg.get("public_text_model") or "").strip() or "deepseek-chat"
    client = build_client(char, db)
    return client, model


def build_public_async_text_client(db) -> tuple[AsyncOpenAI, str]:
    """小说开篇等需要请求级取消的场景：使用公共文字线路的异步客户端。"""
    from .system_config_service import get_local_config

    if db is None:
        raise ValueError("db is required for build_public_async_text_client")
    char = CharacterModel(
        name="_public_text_llm_",
        persona_prompt="",
        api_key="",
        api_base_url="",
        model_name="",
    )
    cfg = get_local_config(db)
    model = (cfg.get("public_text_model") or "").strip() or "deepseek-chat"
    client = build_async_client(char, db)
    return client, model


def build_client(character: CharacterModel, db=None) -> OpenAI:
    """按角色独立配置创建客户端；留空时继承设置中的公共文字线路。"""
    resolved = resolve_text_config(character, db)
    api_key = resolved.api_key
    base_url = resolved.base_url

    from .openai_compatible_routing import normalize_base, uses_versioned_root

    # The shared normalizer returns a provider root so image/responses routes can
    # append their own resource path. The OpenAI SDK, however, expects its
    # ``base_url`` to include the compatible ``/v1`` root.
    base_url = normalize_base(base_url)
    if base_url and not uses_versioned_root(base_url):
        base_url = f"{base_url}/v1"
    provider = detect_provider(character, base_url)

    if provider == "ollama":
        logger.info("使用 Ollama 后端：%s", base_url)
        return OpenAI(
            api_key=api_key or "ollama",
            base_url=base_url.rstrip("/"),
        )

    if provider == "gemini":
        logger.info("使用 Gemini 后端：%s", base_url)
        return OpenAI(
            api_key=api_key,
            base_url=base_url.rstrip("/"),
        )

    return OpenAI(
        api_key=api_key,
        base_url=base_url,
    )


def build_async_client(character: CharacterModel, db=None) -> AsyncOpenAI:
    """与 ``build_client`` 使用同一线路解析，但允许协程取消关闭上游请求。"""
    resolved = resolve_text_config(character, db)
    api_key = resolved.api_key
    base_url = resolved.base_url

    from .openai_compatible_routing import normalize_base, uses_versioned_root

    base_url = normalize_base(base_url)
    if base_url and not uses_versioned_root(base_url):
        base_url = f"{base_url}/v1"
    provider = detect_provider(character, base_url)

    if provider == "ollama":
        logger.info("使用异步 Ollama 后端：%s", base_url)
        return AsyncOpenAI(
            api_key=api_key or "ollama",
            base_url=base_url.rstrip("/"),
        )

    if provider == "gemini":
        logger.info("使用异步 Gemini 后端：%s", base_url)
        return AsyncOpenAI(
            api_key=api_key,
            base_url=base_url.rstrip("/"),
        )

    return AsyncOpenAI(
        api_key=api_key,
        base_url=base_url,
    )


def get_model_context_limit_fallback(character: CharacterModel) -> int:
    """根据检测到的提供商返回模型上下文限制。"""
    provider = detect_provider(character)
    limits = {
        "claude": 200000,
        "ollama": 8192,
        "gemini": 1048576,
    }
    return limits.get(provider, 65536)
