"""思考 / Max 模式：在「角色优先」或「全局允许 + 会话开关」下，将对话请求映射到更强推理模型。"""

from __future__ import annotations

from ..models import CharacterModel, ChatSessionModel
from .llm_client import is_placeholder_text_base


def effective_think_max(
    character: CharacterModel,
    session: ChatSessionModel | None,
    local_cfg: dict,
) -> bool:
    """角色级开启则始终生效；否则需全局允许且当前会话开启。"""
    if bool(getattr(character, "think_max_enabled", False)):
        return True
    if not bool(local_cfg.get("allow_session_think_max", False)):
        return False
    if session is None:
        return False
    return bool(getattr(session, "think_max_enabled", False))


def resolve_think_max_chat_model(
    character: CharacterModel,
    session: ChatSessionModel | None,
    local_cfg: dict,
    *,
    default_model: str,
) -> str:
    """返回实际请求上游的 model id（未开启思考/Max 时与角色配置一致）。"""
    character_model = (character.model_name or "").strip()
    public_model = (local_cfg.get("public_text_model") or "").strip()
    uses_character_route = bool(character.api_key) or not is_placeholder_text_base(character.api_base_url)
    base = (
        character_model if uses_character_route else public_model or character_model
    ) or default_model
    if not effective_think_max(character, session, local_cfg):
        return base

    char_override = (getattr(character, "think_max_model_name", None) or "").strip()
    if char_override:
        return char_override

    global_override = (local_cfg.get("think_max_model") or "").strip()
    if global_override:
        return global_override

    lowered = base.lower()
    if lowered == "deepseek-chat":
        return "deepseek-reasoner"
    if lowered in ("gpt-4o", "gpt-4o-mini"):
        return base
    return base
