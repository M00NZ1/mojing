"""
分段记忆与事件树服务 — 替代全量覆盖式记忆压缩。
按文档第3章要求实现 create_memory_segment、build_context_memory、extract_event_nodes。
"""

import json
from collections.abc import Callable
from sqlalchemy import and_, func, not_, or_, select
from sqlalchemy.orm import Session

from ..models import (
    MessageModel,
    SessionMemorySegmentModel,
    SessionMemoryCorrectionModel,
    SessionEventNodeModel,
    SessionCharacterStateModel,
    SessionWorldModel,
    EncyclopediaEntryModel,
)
from ..services.llm_retry import safe_non_streaming_call


MEMORY_INPUT_CHARS = 4000


def _memory_input_chunks(messages: list[MessageModel]):
    """Yield every source character with a speaker and message marker."""
    buffer = ""
    for message in messages:
        body = message.content or ""
        offset = 0
        while True:
            marker = f"\n[{message.speaker_type} #{message.id}{' 接续' if offset else ''}]\n"
            if len(buffer) + len(marker) >= MEMORY_INPUT_CHARS:
                yield buffer
                buffer = ""
            available = MEMORY_INPUT_CHARS - len(buffer) - len(marker)
            part = body[offset:offset + available]
            buffer += marker + part
            offset += len(part)
            if offset >= len(body):
                break
            yield buffer
            buffer = ""
    if buffer:
        yield buffer


def get_active_memory_corrections(db: Session, session_id: int, branch_id: str) -> list[SessionMemoryCorrectionModel]:
    """返回当前会话全局纠正与当前精确分支纠正，不继承其他分支。"""
    normalized_branch_id = (branch_id or "main").strip() or "main"
    return list(db.scalars(
        select(SessionMemoryCorrectionModel).where(
            SessionMemoryCorrectionModel.session_id == session_id,
            or_(SessionMemoryCorrectionModel.branch_id.is_(None), SessionMemoryCorrectionModel.branch_id == normalized_branch_id),
        ).order_by(SessionMemoryCorrectionModel.created_at.asc(), SessionMemoryCorrectionModel.id.asc())
    ))


def get_visible_memory_segments(
    db: Session,
    session_id: int,
    branch_id: str,
    *,
    limit: int | None = None,
) -> list[SessionMemorySegmentModel]:
    """按消息分支 owner 返回当前故事线可见的自动摘要。"""
    # 延迟导入避免 chat_service 在构建 Prompt 时加载本模块形成循环导入。
    from .chat_service import resolve_branch_context

    branch_context = resolve_branch_context(db, session_id, branch_id)
    visibility = []
    for visible_branch_id, cutoff in branch_context.cutoffs.items():
        clause = SessionMemorySegmentModel.branch_id == visible_branch_id
        if cutoff is not None:
            clause = and_(clause, SessionMemorySegmentModel.end_message_id <= cutoff)
        visibility.append(clause)
    excluded_ranges = [
        not_(
            and_(
                SessionMemorySegmentModel.start_message_id <= message_id,
                SessionMemorySegmentModel.end_message_id >= message_id,
            )
        )
        for message_id in branch_context.excluded_message_ids
    ]
    stmt = select(SessionMemorySegmentModel).where(
        SessionMemorySegmentModel.session_id == session_id,
        or_(*visibility),
        *excluded_ranges,
    )
    if limit is not None:
        rows = list(
            db.scalars(
                stmt.order_by(
                    SessionMemorySegmentModel.end_message_id.desc(),
                    SessionMemorySegmentModel.id.desc(),
                ).limit(limit)
            )
        )
        rows.reverse()
        return rows
    return list(
        db.scalars(
            stmt.order_by(
                SessionMemorySegmentModel.end_message_id.asc(),
                SessionMemorySegmentModel.id.asc(),
            )
        )
    )


def create_memory_segment(
    db: Session,
    session_id: int,
    branch_id: str,
    start_msg_id: int,
    end_msg_id: int,
    *,
    client,
    model: str,
) -> SessionMemorySegmentModel | None:
    """
    为一段消息范围创建分段摘要。
    不覆盖旧分段，追加新分段。
    """
    messages = _get_messages_in_range(db, session_id, branch_id, start_msg_id, end_msg_id)
    if not messages:
        return None

    last_segment = db.scalar(
        select(func.max(SessionMemorySegmentModel.segment_index)).where(
            SessionMemorySegmentModel.session_id == session_id,
            SessionMemorySegmentModel.branch_id == branch_id,
        )
    ) or 0

    summary = ""
    key_facts: list[str] = []
    key_characters: list[str] = []
    emotional_tone = "中性"
    for chunk in _memory_input_chunks(messages):
        carried = json.dumps(
            {"summary": summary, "key_facts": key_facts[-8:], "key_characters": key_characters[-8:]},
            ensure_ascii=False,
        ) if summary else ""
        prompt = f"""请将以下对话段落压缩为结构化摘要。消息标记中的接续属于同一条消息；保留已有事实和未完成事项。

对话内容：
{chunk}
{f'前文承接：{carried}' if carried else ''}
请输出一个 JSON 对象：
{{
  "summary": "段落摘要(≤300字)",
  "key_facts": ["事实1", "事实2"],
  "key_characters": ["角色名1"],
  "emotional_tone": "情感基调(紧张/温馨/冲突/平静/悬疑/悲伤/欢乐/混合)"
}}
"""
        response = safe_non_streaming_call(
            client,
            model=model,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.3,
            max_tokens=1000,
        )
        try:
            content = response.get("content", "") if isinstance(response, dict) else str(response)
            json_start = content.find("{")
            json_end = content.rfind("}") + 1
            if json_start == -1 or json_end <= 0:
                return None
            result = json.loads(content[json_start:json_end])
            if not isinstance(result, dict):
                return None
        except (json.JSONDecodeError, KeyError, TypeError):
            return None

        next_summary = result.get("summary")
        if not isinstance(next_summary, str) or not next_summary.strip() or len(next_summary) > 1000:
            return None
        summary = next_summary.strip()
        for field, target, width in (("key_facts", key_facts, 300), ("key_characters", key_characters, 120)):
            values = result.get(field)
            if isinstance(values, list):
                for value in values:
                    if isinstance(value, str) and value.strip() and value[:width] not in target:
                        target.append(value[:width])
                del target[:-20]
        emotional_tone = str(result.get("emotional_tone") or "中性")[:60]

    segment = SessionMemorySegmentModel(
        session_id=session_id,
        branch_id=branch_id,
        segment_index=last_segment + 1,
        start_message_id=start_msg_id,
        end_message_id=end_msg_id,
        summary=summary,
        key_facts=key_facts,
        key_characters=key_characters,
        emotional_tone=emotional_tone,
    )
    db.add(segment)
    return segment


def build_context_memory(
    db: Session,
    session_id: int,
    branch_id: str,
    query_text: str = "",
    token_budget: int = 2000,
) -> str:
    """动态组装记忆上下文：中期(分段摘要) + 长期(百科条目)。"""
    corrections = get_active_memory_corrections(db, session_id, branch_id)
    segments = get_visible_memory_segments(db, session_id, branch_id, limit=6)

    context_parts = []

    if corrections:
        context_parts.append("【用户锁定记忆（冲突时优先）】")
        context_parts.extend(f"· {item.content}" for item in corrections)

    if segments:
        context_parts.append("【中期记忆 — 分阶段摘要】")
        for seg in segments:
            context_parts.append(
                f"阶段{seg.segment_index}: {seg.summary}"
                f"（情感: {seg.emotional_tone}）"
            )
            if seg.key_facts:
                context_parts.append(f"  关键事实: {'; '.join(seg.key_facts[:5])}")

    world = db.scalar(
        select(SessionWorldModel).where(SessionWorldModel.session_id == session_id)
    )
    if world and world.encyclopedia_id:
        entries = list(
            db.scalars(
                select(EncyclopediaEntryModel)
                .where(EncyclopediaEntryModel.encyclopedia_id == world.encyclopedia_id)
                .where(EncyclopediaEntryModel.last_referenced_at.isnot(None))
                .order_by(EncyclopediaEntryModel.last_referenced_at.desc())
                .limit(5)
            )
        )
        if entries:
            context_parts.append("【长期记忆 — 相关百科条目】")
            for entry in entries:
                context_parts.append(f"· {entry.title}: {entry.summary[:200]}")

    return "\n\n".join(context_parts)


def extract_event_nodes(
    db: Session,
    session_id: int,
    character_id: int | None,
    messages: list[MessageModel],
    *,
    client,
    model: str,
    before_write: Callable[[], None] | None = None,
) -> list[SessionEventNodeModel] | None:
    """从消息中提取事件节点，构建因果树。"""
    if not messages:
        return []

    dialogue_text = "\n".join(
        f"[{msg.speaker_type}] {msg.content[:300]}" for msg in messages[-20:]
    )

    branch_id = messages[0].branch_id if messages else "main"

    character_scope = (
        SessionEventNodeModel.character_id.is_(None)
        if character_id is None
        else SessionEventNodeModel.character_id == character_id
    )
    with db.no_autoflush:
        existing_events = list(
            db.scalars(
                select(SessionEventNodeModel)
                .where(
                    SessionEventNodeModel.session_id == session_id,
                    SessionEventNodeModel.branch_id == branch_id,
                    character_scope,
                )
                .order_by(SessionEventNodeModel.created_at.desc())
                .limit(20)
            )
        )
    existing_titles = [ev.title for ev in existing_events]

    prompt = f"""从以下对话中提取关键事件节点。

已有事件（避免重复）：{json.dumps(existing_titles, ensure_ascii=False)}

对话内容：
{dialogue_text[:4000]}

请输出 JSON 数组：
[
  {{
    "title": "事件标题(≤60字)",
    "event_type": "事件类型(action/discovery/relationship_change/world_change/combat/dialogue_key)",
    "description": "事件描述(≤200字)",
    "importance": "重要程度1-5(5=剧情节點)",
    "parent_event_title": "父事件标题(如果是某事件的后续，否则null)"
  }}
]
只提取明确发生的新事件，最多5个。
"""

    response = safe_non_streaming_call(
        client,
        model=model,
        messages=[{"role": "user", "content": prompt}],
        temperature=0.3,
        max_tokens=1500,
    )

    try:
        content = response.get("content", "") if isinstance(response, dict) else str(response)
        json_start = content.find("[")
        json_end = content.rfind("]") + 1
        if json_start == -1 or json_end <= 0:
            return None
        events_data = json.loads(content[json_start:json_end])
        if not isinstance(events_data, list):
            return None
    except (json.JSONDecodeError, KeyError, TypeError):
        return None

    if before_write is not None:
        before_write()

    new_nodes = []
    title_to_id = {}
    for ev in existing_events:
        title_to_id[(ev.title or "").lower()] = ev.id

    for ev in events_data[:5]:
        if not isinstance(ev, dict):
            continue
        title = str(ev.get("title") or "").strip()[:200]
        if not title or title.lower() in title_to_id:
            continue

        parent_id = None
        parent_title = str(ev.get("parent_event_title") or "").strip()
        if parent_title:
            parent_id = title_to_id.get(parent_title.lower())

        try:
            importance = max(1, min(5, int(ev.get("importance", 2))))
        except (TypeError, ValueError):
            importance = 2
        node = SessionEventNodeModel(
            session_id=session_id,
            character_id=character_id,
            branch_id=branch_id,
            parent_event_id=parent_id,
            event_type=str(ev.get("event_type") or "action")[:60],
            title=title,
            description=str(ev.get("description") or "")[:2000],
            importance=importance,
            message_id=messages[-1].id if messages else None,
        )
        db.add(node)
        new_nodes.append(node)
        # 同一批次中的后续事件可引用刚创建的父事件；flush 只分配主键，
        # 最终仍由调用方统一 commit/rollback。
        db.flush()
        title_to_id[title.lower()] = node.id

    return new_nodes


def get_active_event_chain(
    db: Session,
    session_id: int,
    character_id: int,
    branch_id: str = "main",
    limit: int = 10,
) -> list[SessionEventNodeModel]:
    """获取角色相关的活跃事件链（未解决的事件，按重要性排序）。"""
    return list(
        db.scalars(
            select(SessionEventNodeModel)
            .where(
                SessionEventNodeModel.session_id == session_id,
                SessionEventNodeModel.branch_id == branch_id,
                or_(
                    SessionEventNodeModel.character_id.is_(None),
                    SessionEventNodeModel.character_id == character_id,
                ),
                SessionEventNodeModel.resolved == 0,
            )
            .order_by(SessionEventNodeModel.importance.desc())
            .limit(limit)
        )
    )


def format_event_chain(events: list[SessionEventNodeModel]) -> str:
    """将事件列表格式化为文本。"""
    if not events:
        return ""

    lines = ["【活跃事件链】"]
    for ev in sorted(events, key=lambda e: e.importance, reverse=True):
        prefix = "  " if ev.parent_event_id else "▶ "
        lines.append(f"{prefix}[{ev.event_type}] {ev.title}（重要度{ev.importance}）")
        if ev.description:
            lines.append(f"    {ev.description[:100]}")

    return "\n".join(lines)


def _get_messages_in_range(
    db: Session, session_id: int, branch_id: str, start_id: int, end_id: int
) -> list[MessageModel]:
    """获取指定范围内的消息列表。"""
    return list(
        db.scalars(
            select(MessageModel)
            .where(
                MessageModel.session_id == session_id,
                MessageModel.branch_id == branch_id,
                MessageModel.id >= start_id,
                MessageModel.id <= end_id,
                MessageModel.include_in_context == True,
            )
            .order_by(MessageModel.id.asc())
        )
    )
