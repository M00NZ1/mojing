import logging
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)

try:
    import tiktoken
    HAS_TIKTOKEN = True
except ImportError:
    HAS_TIKTOKEN = False
    logger.warning("tiktoken is not installed. Using len(text) * 1.5 as fallback token counter.")


def count_tokens(text: str, model_name: str = "gpt-4") -> int:
    """计算文本的大致Token数。如果安装了tiktoken则精准计算，否则采用经验公式回退。"""
    if not text:
        return 0
    if HAS_TIKTOKEN:
        try:
            enc = tiktoken.encoding_for_model(model_name)
        except KeyError:
            enc = tiktoken.get_encoding("cl100k_base")
        return len(enc.encode(text))
    else:
        # Fallback ratio: roughly 1 CJK char = 1.5~2 tokens, 1 English block ~ 0.5 tokens
        return int(len(text) * 1.5)


def get_token_usage_stats(db: Session, session_id: int, branch_id: str = "main") -> dict:
    """估算当前对话各模块占用的 Token 数额。"""

    from .chat_service import get_session_messages_page
    from ..models import (
        ChatSessionModel,
        SessionWorldModel,
        SessionParticipantModel,
        CharacterModel,
        EncyclopediaEntryModel,
        SessionCharacterStateModel,
        WorldLoreEntryModel,
        WorldTemplateModel,
    )

    # 1. 世界设定与系统提示
    system_text = ""
    world = db.query(SessionWorldModel).filter(SessionWorldModel.session_id == session_id).first()
    if world:
        system_text += f"{world.world_prompt}\n{world.anti_cheat_prompt}\n"
    system_tokens = count_tokens(system_text)

    # 2. 人物设定（所有参与者的 persona_prompt）
    participants = (
        db.query(SessionParticipantModel)
        .filter(SessionParticipantModel.session_id == session_id)
        .all()
    )
    character_texts = []
    for p in participants:
        char = db.get(CharacterModel, p.character_id)
        if char and char.persona_prompt:
            character_texts.append(f"{char.name}：{char.persona_prompt}")
    character_tokens = count_tokens("\n".join(character_texts))

    # 3. 人物记忆/状态（session_character_states）
    states = (
        db.query(SessionCharacterStateModel)
        .filter(SessionCharacterStateModel.session_id == session_id)
        .all()
    )
    memory_texts = []
    for s in states:
        state_str = str(s.dynamic_state_json or {}) + str(s.relations_json or {}) + str(s.private_facts_json or []) + str(s.event_log_json or [])
        memory_texts.append(state_str)
    memory_tokens = count_tokens(" ".join(memory_texts))

    # 4. 世界 Lore（被触发的条目）
    lore_text = ""
    if world and world.template_id and world.template_id != "custom":
        template = db.query(WorldTemplateModel).filter(WorldTemplateModel.template_id == world.template_id).first()
        if template:
            lore_entries = (
                db.query(WorldLoreEntryModel)
                .filter(WorldLoreEntryModel.world_template_id == template.id)
                .all()
            )
            lore_text = " ".join(e.content for e in lore_entries if e.is_core)
    lore_tokens = count_tokens(lore_text)

    encyclopedia_text = ""
    if world and world.encyclopedia_id:
        encyclopedia_entries = (
            db.query(EncyclopediaEntryModel)
            .filter(EncyclopediaEntryModel.encyclopedia_id == world.encyclopedia_id)
            .order_by(EncyclopediaEntryModel.is_featured.desc(), EncyclopediaEntryModel.sort_order.asc())
            .limit(20)
            .all()
        )
        encyclopedia_text = " ".join(
            f"{entry.title} {entry.summary} {entry.content[:500]}"
            for entry in encyclopedia_entries
            if entry.is_featured or entry.summary or entry.content
        )
    encyclopedia_tokens = count_tokens(encyclopedia_text)

    # 5. 近期历史消息
    messages, _ = get_session_messages_page(db, session_id, None, 40, branch_id=branch_id)
    history_text = "\n".join([m.content for m in messages if m.content])
    history_tokens = count_tokens(history_text)

    # 6. 模型上下文限制（取第一个参与者的模型，或默认值）
    model_name = None
    if participants:
        first_char = db.get(CharacterModel, participants[0].character_id)
        if first_char:
            model_name = first_char.model_name
    model_context_limit = _get_model_context_limit(model_name or "deepseek-chat")

    system_prompt_tokens = system_tokens + character_tokens + lore_tokens + encyclopedia_tokens
    total_tokens = system_prompt_tokens + memory_tokens + history_tokens

    return {
        "system_prompt_tokens": system_prompt_tokens,
        "character_tokens": character_tokens,
        "memory_tokens": memory_tokens,
        "lore_tokens": lore_tokens,
        "encyclopedia_tokens": encyclopedia_tokens,
        "history_tokens": history_tokens,
        "total_tokens": total_tokens,
        "model_context_limit": model_context_limit,
        "remaining_tokens": max(0, model_context_limit - total_tokens),
    }


def _get_model_context_limit(model_name: str) -> int:
    """根据模型名称返回上下文限制 Token 数。"""
    model_map = {
        "deepseek-chat": 65536,
        "deepseek-reasoner": 65536,
        "gpt-4-turbo": 128000,
        "gpt-4o": 128000,
        "gpt-4": 8192,
        "gpt-3.5-turbo": 16384,
        "claude-3.5-sonnet": 200000,
        "claude-3-opus": 200000,
        "claude-3-sonnet": 200000,
        "claude-3-haiku": 200000,
        "gemini-1.5-pro": 1048576,
        "gemini-pro": 32768,
        "qwen-plus": 131072,
        "qwen-turbo": 32768,
        "glm-4v": 131072,
        "glm-4": 131072,
        "moonshot-v1": 131072,
        "yi-34b-chat": 4096,
    }
    for key, limit in model_map.items():
        if key in model_name.lower():
            return limit
    return 8192
