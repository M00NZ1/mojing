from __future__ import annotations

import json
import logging
import threading
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session, joinedload

from ..database import SessionLocal
from ..models import (
    CharacterModel,
    CharacterProfileModel,
    ChatSessionModel,
    MessageModel,
    SessionCharacterStateModel,
    SessionMemorySegmentModel,
    SessionParticipantModel,
)
from .llm_client import build_client, resolve_text_model
from .llm_retry import safe_non_streaming_call


DEFAULT_CARD = {
    "basic_info": {},
    "appearance": {},
    "identity": {},
    "personality": {},
    "speaking_style": {},
    "preferences": {},
    "taboos": {},
    "goals": {},
    "relationships": {},
    "abilities": {},
}

logger = logging.getLogger(__name__)
MEMORY_COMPACTION_THRESHOLD = 12
MEMORY_COMPACTION_BATCH_SIZE = 40
_compaction_locks_guard = threading.Lock()
_compaction_locks: dict[tuple[int, str], threading.Lock] = {}


def _get_compaction_lock(session_id: int, branch_id: str) -> threading.Lock:
    key = (session_id, branch_id)
    with _compaction_locks_guard:
        return _compaction_locks.setdefault(key, threading.Lock())


def import_character_profile(db: Session, character_id: int, source_text: str, source_filename: str, merge_into_persona_prompt: bool) -> CharacterProfileModel:
    """首次导入原始人物设定并抽取结构化人物卡。"""

    character = db.get(CharacterModel, character_id)
    if character is None:
        raise ValueError("人物不存在。")
    client = build_client(character, db)
    prompt = f"""
你是人物设定抽取器。请从原始设定中提取稳定信息，输出严格 JSON。

【JSON 顶层结构】
{{
  "basic_info": {{
    "name": "",
    "gender": "",
    "age": "",
    "race": "",
    "height": "",
    "weight": "",
    "body_measurements": "",
    "occupation": "",
    "identity": ""
  }},
  "appearance": {{}},
  "identity": {{}},
  "personality": {{}},
  "speaking_style": {{}},
  "preferences": {{}},
  "taboos": {{}},
  "goals": {{}},
  "relationships": {{}},
  "abilities": {{}}
}}

【要求】
1. 只能输出 JSON，不要 Markdown，不要解释。
2. 没明确写到的信息不要瞎编，可留空。
3. 只提取稳定信息，不要把临时剧情状态写进来。

【原始设定】
{source_text}
""".strip()
    response = client.chat.completions.create(
        model=resolve_text_model(character, db),
        messages=[{"role": "user", "content": prompt}],
        temperature=0.2,
        max_tokens=min(max(character.max_tokens, 1500), 4096),
    )
    raw_json = (response.choices[0].message.content or "").strip()
    data = _safe_load_json(raw_json, DEFAULT_CARD.copy())
    markdown = render_character_card_markdown(character.name, data)

    profile = db.scalar(select(CharacterProfileModel).where(CharacterProfileModel.character_id == character_id))
    if profile is None:
        profile = CharacterProfileModel(character_id=character_id)
        db.add(profile)

    profile.source_filename = source_filename
    profile.raw_persona_text = source_text
    profile.character_card_json = data
    profile.character_card_markdown = markdown
    profile.extracted_at = datetime.utcnow()

    if merge_into_persona_prompt:
        character.persona_prompt = build_runtime_persona_prompt(character.persona_prompt, data)

    db.commit()
    db.refresh(profile)
    for session_id in db.scalars(select(SessionParticipantModel.session_id).where(SessionParticipantModel.character_id == character_id)):
        ensure_session_character_state(db, int(session_id), character_id)
    return profile


def build_runtime_persona_prompt(existing_prompt: str, character_card: dict) -> str:
    """把人物卡抽取结果压成更短、更稳定的运行时提示词。"""

    basic = character_card.get("basic_info", {})
    lines = [
        existing_prompt.strip(),
        "",
        "【结构化人物卡摘要】",
    ]
    for label, key in [
        ("姓名", "name"),
        ("性别", "gender"),
        ("年龄", "age"),
        ("种族", "race"),
        ("身高", "height"),
        ("体型/三围", "body_measurements"),
        ("职业", "occupation"),
        ("身份", "identity"),
    ]:
        value = basic.get(key, "")
        if value:
            lines.append(f"- {label}：{value}")
    for block_name, block_value in character_card.items():
        if block_name == "basic_info" or not isinstance(block_value, dict):
            continue
        flat_items = [f"{key}={value}" for key, value in block_value.items() if value]
        if flat_items:
            lines.append(f"- {block_name}：{'；'.join(flat_items[:8])}")
    return "\n".join(line for line in lines if line is not None).strip()


def render_character_card_markdown(name: str, card: dict) -> str:
    """把结构化人物卡渲染成人类可读 Markdown。"""

    lines = [f"# {name} 人物卡", ""]
    for section_name, section_value in card.items():
        lines.append(f"## {section_name}")
        if isinstance(section_value, dict) and section_value:
            for key, value in section_value.items():
                if value:
                    lines.append(f"- {key}: {value}")
        else:
            lines.append("- 暂无")
        lines.append("")
    return "\n".join(lines).strip() + "\n"


def ensure_session_character_state(db: Session, session_id: int, character_id: int) -> SessionCharacterStateModel:
    """确保会话中的人物状态存在。"""

    state = db.scalar(
        select(SessionCharacterStateModel).where(
            SessionCharacterStateModel.session_id == session_id,
            SessionCharacterStateModel.character_id == character_id,
        )
    )
    if state is None:
        state = SessionCharacterStateModel(
            session_id=session_id,
            character_id=character_id,
            dynamic_state_json={"current_status": "", "location": "", "mood": "", "carried_items": []},
            relations_json={},
            private_facts_json=[],
            event_log_json=[],
        )
        db.add(state)
        db.commit()
        db.refresh(state)
    return state


def compact_session_memory(session_id: int) -> None:
    """按会话刷新摘要与人物记忆。"""

    db = SessionLocal()
    try:
        session = db.get(ChatSessionModel, session_id)
        if session is None:
            return
        participant_ids = list(
            db.scalars(
                select(SessionParticipantModel.character_id)
                .where(SessionParticipantModel.session_id == session_id)
                .order_by(SessionParticipantModel.sort_order.asc())
            )
        )
        if not participant_ids:
            return

        _refresh_session_summary(db, session_id, participant_ids[0])
        for character_id in participant_ids:
            _refresh_character_memory(db, session_id, character_id)
    finally:
        db.close()


def maybe_compact_session_memory(session_id: int, threshold: int = 12) -> None:
    """消息达到一定数量后自动触发一次后台压缩。"""

    db = SessionLocal()
    try:
        count = db.scalar(select(func.count()).where(MessageModel.session_id == session_id).select_from(MessageModel)) or 0
    finally:
        db.close()
    if count > 0 and count % threshold == 0:
        compact_session_memory(session_id)


def _refresh_session_summary(db: Session, session_id: int, summarizer_character_id: int) -> None:
    character = db.get(CharacterModel, summarizer_character_id)
    session = db.get(ChatSessionModel, session_id)
    if character is None or session is None:
        return

    recent_messages = list(
        db.scalars(
            select(MessageModel)
            .options(joinedload(MessageModel.character))
            .where(MessageModel.session_id == session_id)
            .order_by(MessageModel.id.desc())
            .limit(30)
        )
    )
    recent_messages.reverse()
    history_text = "\n".join(
        f"[{msg.character.name if msg.character else msg.speaker_type}] {msg.content}" for msg in recent_messages
    )
    prompt = f"""
请把以下剧情记录压缩成供后续续写使用的阶段摘要。

【旧摘要】
{session.summary or "暂无"}

【新增记录】
{history_text}

【要求】
1. 输出 6 到 12 条关键事实。
2. 重点保留人物关系变化、任务目标、冲突、身份信息、地点变化。
3. 不要写流水账。
""".strip()
    client = build_client(character, db)
    response = client.chat.completions.create(
        model=resolve_text_model(character, db),
        messages=[{"role": "user", "content": prompt}],
        temperature=0.3,
        max_tokens=min(max(character.max_tokens, 900), 2048),
    )
    session.summary = (response.choices[0].message.content or "").strip()
    db.commit()


def _refresh_character_memory(db: Session, session_id: int, character_id: int) -> None:
    character = db.get(CharacterModel, character_id)
    session = db.get(ChatSessionModel, session_id)
    if character is None or session is None:
        return
    state = ensure_session_character_state(db, session_id, character_id)
    profile = db.scalar(select(CharacterProfileModel).where(CharacterProfileModel.character_id == character_id))

    recent_messages = list(
        db.scalars(
            select(MessageModel)
            .options(joinedload(MessageModel.character))
            .where(
                MessageModel.session_id == session_id,
                MessageModel.id > (state.last_compacted_message_id or 0),
            )
            .order_by(MessageModel.id.asc())
            .limit(40)
        )
    )
    if not recent_messages:
        return

    transcript = "\n".join(
        f"[{msg.character.name if msg.character else msg.speaker_type}] {msg.content}" for msg in recent_messages
    )
    prompt = f"""
你在维护角色“{character.name}”的长期记忆，请输出严格 JSON：
{{
  "dynamic_state_json": {{
    "current_status": "",
    "location": "",
    "mood": "",
    "carried_items": []
  }},
  "relations_json": {{}},
  "private_facts_json": [],
  "event_log_json": []
}}

【人物卡】
{json.dumps((profile.character_card_json if profile else {}), ensure_ascii=False)}

【旧动态状态】
{json.dumps(state.dynamic_state_json or {}, ensure_ascii=False)}

【旧关系记忆】
{json.dumps(state.relations_json or {}, ensure_ascii=False)}

【旧私有记忆】
{json.dumps(state.private_facts_json or [], ensure_ascii=False)}

【新增对话记录】
{transcript}

【要求】
1. 只更新这个人物真正应该记住的东西。
2. 不确定的信息不要写死。
3. 事件日志只保留关键事件。
4. 只能输出 JSON。
""".strip()
    client = build_client(character, db)
    raw_json = safe_non_streaming_call(client, model=resolve_text_model(character, db), messages=[{"role": "user", "content": prompt}], temperature=0.2, max_tokens=min(max(character.max_tokens, 1200), 4096))
    raw_json = (raw_json or "").strip()
    data = _safe_load_json(
        raw_json,
        {
            "dynamic_state_json": state.dynamic_state_json or {},
            "relations_json": state.relations_json or {},
            "private_facts_json": state.private_facts_json or [],
            "event_log_json": state.event_log_json or [],
        },
    )

    state.dynamic_state_json = data.get("dynamic_state_json", state.dynamic_state_json or {})
    state.relations_json = data.get("relations_json", state.relations_json or {})
    state.private_facts_json = _dedupe_list(state.private_facts_json or [], data.get("private_facts_json", []))
    state.event_log_json = _dedupe_list(state.event_log_json or [], data.get("event_log_json", []), limit=60)
    state.last_compacted_message_id = recent_messages[-1].id
    db.commit()


def _safe_load_json(raw_text: str, fallback: dict) -> dict:
    text = raw_text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:].strip()
    try:
        data = json.loads(text)
        if isinstance(data, dict):
            return data
    except Exception:
        pass
    return fallback


def _dedupe_list(existing: list, incoming: list, limit: int = 40) -> list:
    merged = existing + incoming
    seen = []
    for item in merged:
        if item not in seen:
            seen.append(item)
    return seen[-limit:]


def compact_session_memory_v2(session_id: int, branch_id: str = "main") -> bool:
    """按分支追加阶段摘要与共享事件；失败不推进游标，也不阻塞聊天。"""

    normalized_branch_id = (branch_id or "main").strip() or "main"
    with _get_compaction_lock(session_id, normalized_branch_id):
        db = SessionLocal()
        try:
            session = db.get(ChatSessionModel, session_id)
            if session is None:
                return True

            participant_ids = list(
                db.scalars(
                    select(SessionParticipantModel.character_id)
                    .where(SessionParticipantModel.session_id == session_id)
                    .order_by(SessionParticipantModel.sort_order.asc())
                )
            )
            if not participant_ids:
                return True

            last_end_id = db.scalar(
                select(func.max(SessionMemorySegmentModel.end_message_id)).where(
                    SessionMemorySegmentModel.session_id == session_id,
                    SessionMemorySegmentModel.branch_id == normalized_branch_id,
                )
            ) or 0
            recent_messages = list(
                db.scalars(
                    select(MessageModel)
                    .where(
                        MessageModel.session_id == session_id,
                        MessageModel.branch_id == normalized_branch_id,
                        MessageModel.include_in_context == True,
                        MessageModel.id > last_end_id,
                    )
                    .order_by(MessageModel.id.asc())
                    .limit(MEMORY_COMPACTION_BATCH_SIZE)
                )
            )
            if len(recent_messages) < MEMORY_COMPACTION_THRESHOLD:
                return True

            summarizer = db.get(CharacterModel, participant_ids[0])
            if summarizer is None:
                logger.warning("记忆压缩跳过：会话 %s 缺少可用参与角色", session_id)
                return False

            from .memory_v2_service import create_memory_segment, extract_event_nodes

            client = build_client(summarizer, db)
            model = resolve_text_model(summarizer, db)
            segment = create_memory_segment(
                db,
                session_id,
                normalized_branch_id,
                recent_messages[0].id,
                recent_messages[-1].id,
                client=client,
                model=model,
            )
            if segment is None:
                db.rollback()
                logger.warning(
                    "记忆压缩未推进：会话 %s 分支 %s 的分段摘要格式无效",
                    session_id,
                    normalized_branch_id,
                )
                return False

            events = extract_event_nodes(
                db,
                session_id,
                None,
                recent_messages,
                client=client,
                model=model,
            )
            if events is None:
                db.rollback()
                logger.warning(
                    "记忆压缩未推进：会话 %s 分支 %s 的事件提取格式无效",
                    session_id,
                    normalized_branch_id,
                )
                return False

            if normalized_branch_id == "main":
                # 只维护主线兼容摘要；非主线不能把分支事实写进全局摘要。
                segment_count = db.scalar(
                    select(func.count()).select_from(SessionMemorySegmentModel).where(
                        SessionMemorySegmentModel.session_id == session_id,
                        SessionMemorySegmentModel.branch_id == "main",
                    )
                ) or 0
                first_segment = db.scalar(
                    select(SessionMemorySegmentModel)
                    .where(
                        SessionMemorySegmentModel.session_id == session_id,
                        SessionMemorySegmentModel.branch_id == "main",
                    )
                    .order_by(SessionMemorySegmentModel.segment_index.asc())
                    .limit(1)
                )
                last_segment = db.scalar(
                    select(SessionMemorySegmentModel)
                    .where(
                        SessionMemorySegmentModel.session_id == session_id,
                        SessionMemorySegmentModel.branch_id == "main",
                    )
                    .order_by(SessionMemorySegmentModel.segment_index.desc())
                    .limit(1)
                )
                if first_segment and last_segment:
                    session.summary = (
                        f"共{segment_count}个分段。开场：{first_segment.summary[:100]}"
                        f"……最近：{last_segment.summary[:100]}"
                    )[:200]

            db.commit()
            return True
        except Exception:
            db.rollback()
            logger.exception(
                "v2压缩失败 session_id=%s branch_id=%s",
                session_id,
                normalized_branch_id,
            )
            return False
        finally:
            db.close()
