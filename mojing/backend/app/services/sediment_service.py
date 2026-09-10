"""
百科自动沉淀服务 — 将会话对话产生的新事实自动回写到百科库。
按文档第2章要求实现 auto_sediment_facts 和 promote_entry_confidence 两个核心方法。
"""

import json
import logging
from datetime import datetime, timezone
from sqlalchemy import select, func, or_
from sqlalchemy.orm import Session

from ..models import (
    MessageModel,
    EncyclopediaEntryModel,
    EntryVersionModel,
    WorldEncyclopediaModel,
    SessionWorldModel,
)
from ..services.llm_client import build_public_text_client
from ..services.llm_retry import safe_non_streaming_call

logger = logging.getLogger(__name__)

def now_utc():
    return datetime.now(timezone.utc).replace(tzinfo=None)


def auto_sediment_facts(db: Session, session_id: int, encyclopedia_id: int) -> dict:
    """
    自动从会话最近对话中提取事实，沉淀为百科条目。

    入参:
        session_id: int - 会话ID
        encyclopedia_id: int - 目标百科库ID
    出参:
        dict - {"created": int, "updated": int, "skipped": int, "entries": list[dict]}
    """
    encyclopedia = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if not encyclopedia:
        return {"created": 0, "updated": 0, "skipped": 0, "entries": [], "error": "百科库不存在"}

    recent_messages = list(
        db.scalars(
            select(MessageModel)
            .where(MessageModel.session_id == session_id)
            .order_by(MessageModel.id.desc())
            .limit(30)
        )
    )
    recent_messages.reverse()

    if not recent_messages:
        return {"created": 0, "updated": 0, "skipped": 0, "entries": []}

    dialogue_text = "\n".join(
        f"[{msg.speaker_type}] {msg.content[:500]}" for msg in recent_messages
    )

    existing_entries = list(
        db.scalars(
            select(EncyclopediaEntryModel)
            .where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
        )
    )
    existing_titles = {entry.title.lower(): entry for entry in existing_entries}

    prompt = f"""你是世界观数据库管理员。请从以下对话中提取新发现的世界设定事实。

百科库：{encyclopedia.name}
已有条目标题（避免重复）：{json.dumps(list(existing_titles.keys()), ensure_ascii=False)}

对话内容：
{dialogue_text[:4000]}

请输出一个 JSON 对象，格式如下：
{{
  "facts": [
    {{
      "title": "事实标题(≤50字)",
      "entry_type": "条目类型(character/faction/location/item/concept/event/skill/rule/culture/profession/creature)",
      "summary": "一句话摘要(≤200字)",
      "content": "详细描述(≤500字)",
      "confidence": "推断置信度(inferred/speculative)",
      "is_new": true
    }}
  ]
}}

规则：
- 只提取对话中明确出现的新事实，不臆造
- 如果事实与已有条目标题高度相似，设置 is_new=false
- 至少提取1个事实，最多10个
"""

    try:
        client, model = build_public_text_client(db)
        raw = safe_non_streaming_call(
            client,
            model,
            [{"role": "user", "content": prompt}],
            temperature=0.3,
            max_tokens=2000,
        )
    except Exception as e:
        logger.error(f"沉淀LLM调用失败: {e}")
        return {"created": 0, "updated": 0, "skipped": 0, "entries": [], "error": str(e)}

    try:
        content = raw if isinstance(raw, str) else str(raw)
        json_start = content.find("{")
        json_end = content.rfind("}") + 1
        if json_start == -1 or json_end <= 0:
            return {"created": 0, "updated": 0, "skipped": 0, "entries": [], "error": "LLM返回格式异常"}
        result = json.loads(content[json_start:json_end])
        facts = result.get("facts", [])
    except (json.JSONDecodeError, KeyError) as e:
        logger.error(f"沉淀JSON解析失败: {e}")
        return {"created": 0, "updated": 0, "skipped": 0, "entries": [], "error": f"JSON解析失败: {e}"}

    created = 0
    updated = 0
    skipped = 0
    entry_results = []

    for fact in facts:
        title = fact.get("title", "").strip()
        if not title:
            skipped += 1
            continue

        title_lower = title.lower()

        if title_lower in existing_titles:
            existing_entry = existing_titles[title_lower]
            if existing_entry.content:
                existing_entry.content += "\n\n[自动沉淀] " + fact.get("content", "")
            else:
                existing_entry.content = fact.get("content", "")
            existing_entry.updated_at = now_utc()
            _create_entry_version(db, existing_entry, "自动沉淀追加")
            updated += 1
            entry_results.append({"id": existing_entry.id, "title": title, "status": "updated"})
        else:
            new_entry = EncyclopediaEntryModel(
                encyclopedia_id=encyclopedia_id,
                title=title,
                entry_type=fact.get("entry_type", "concept"),
                summary=fact.get("summary", ""),
                content=fact.get("content", ""),
                confidence=fact.get("confidence", "inferred"),
                source_session_id=session_id,
                tags="自动沉淀",
            )
            db.add(new_entry)
            db.flush()
            _create_entry_version(db, new_entry, "自动沉淀创建")
            created += 1
            entry_results.append({"id": new_entry.id, "title": title, "status": "created"})

    db.commit()
    return {"created": created, "updated": updated, "skipped": skipped, "entries": entry_results}


def confirm_sediment_entries(db: Session, encyclopedia_id: int, entry_ids: list[int]) -> int:
    """确认所选沉淀资料，并在同一事务中保留版本记录。"""
    try:
        entries = list(db.scalars(select(EncyclopediaEntryModel).where(
            EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id,
            EncyclopediaEntryModel.id.in_(entry_ids),
            EncyclopediaEntryModel.confidence != "confirmed",
            or_(EncyclopediaEntryModel.confidence == "inferred", EncyclopediaEntryModel.source_session_id.isnot(None)),
        )))
        timestamp = now_utc()
        for entry in entries:
            previous = entry.confidence
            entry.confidence = "confirmed"
            entry.updated_at = timestamp
            _create_entry_version(db, entry, f"置信度变更: {previous} → confirmed")
        db.commit()
        return len(entries)
    except Exception:
        db.rollback()
        raise


def promote_entry_confidence(db: Session, entry_id: int, new_confidence: str) -> None:
    """
    用户确认后提升条目置信度。

    入参:
        entry_id: int
        new_confidence: str - 'confirmed' | 'inferred' | 'speculative'
    """
    entry = db.get(EncyclopediaEntryModel, entry_id)
    if not entry:
        raise ValueError(f"条目 {entry_id} 不存在")

    old_confidence = entry.confidence
    entry.confidence = new_confidence
    entry.updated_at = now_utc()
    _create_entry_version(db, entry, f"置信度变更: {old_confidence} → {new_confidence}")
    db.commit()


def _create_entry_version(db: Session, entry: EncyclopediaEntryModel, change_note: str) -> EntryVersionModel:
    """创建条目版本快照。"""
    latest = db.scalar(
        select(func.max(EntryVersionModel.version)).where(
            EntryVersionModel.entry_id == entry.id
        )
    ) or 0

    version = EntryVersionModel(
        entry_id=entry.id,
        version=latest + 1,
        title=entry.title,
        summary=entry.summary,
        content=entry.content,
        tags=entry.tags,
        meta_snapshot_json=dict(entry.meta_json or {}),
        change_note=change_note,
        created_by="sediment_service",
    )
    db.add(version)
    return version
