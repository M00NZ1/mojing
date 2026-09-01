"""
安全宏动作注册表 — 白名单声明式动作系统。

每个动作是一个命名函数，注册到 ACTION_REGISTRY 后可通过 API 安全调用。
不允许任意代码执行，只允许白名单动作。
"""
from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any, Callable

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import (
    EncyclopediaEntryModel,
    EntryVersionModel,
    MessageModel,
    WorldEncyclopediaModel,
)
from .chat_service import BranchContextError, get_session_messages_page

logger = logging.getLogger(__name__)

# ── Action Registry ──────────────────────────────────────────────

ActionFn = Callable[..., dict]
ActionEntry = dict[str, Any]

ACTION_REGISTRY: dict[str, ActionEntry] = {}


def register_action(
    name: str,
    label: str,
    description: str,
    params_schema: list[dict],
    fn: ActionFn,
    scope: str = "session",
):
    """注册一个白名单动作。"""
    ACTION_REGISTRY[name] = {
        "name": name,
        "label": label,
        "description": description,
        "params_schema": params_schema,
        "fn": fn,
        "scope": scope,
    }


def list_actions() -> list[dict]:
    """列出所有可用动作（不含 fn 实现）。"""
    return [
        {
            "name": a["name"],
            "label": a["label"],
            "description": a["description"],
            "params_schema": a["params_schema"],
            "scope": a["scope"],
        }
        for a in ACTION_REGISTRY.values()
    ]


def execute_action(name: str, params: dict, db: Session) -> dict:
    """执行一个白名单动作。"""
    entry = ACTION_REGISTRY.get(name)
    if not entry:
        raise ValueError(f"未知动作: {name}")
    return entry["fn"](params, db)


# ══════════════════════════════════════════════════════════════════
#  内置动作实现
# ══════════════════════════════════════════════════════════════════


def _action_create_entry(params: dict, db: Session) -> dict:
    """从消息内容创建百科条目。消息内容作为条目的初始描述。"""
    session_id = params.get("session_id")
    message_id = params.get("message_id")
    encyclopedia_id = params.get("encyclopedia_id")
    entry_type = params.get("entry_type", "event")

    if not session_id and not message_id:
        raise ValueError("session_id 或 message_id 至少需要一个")

    content_source = ""
    title = params.get("title", "").strip()

    if message_id:
        msg = db.get(MessageModel, message_id)
        if msg:
            content_source = msg.content or ""
            if not title:
                title = f"从消息 #{message_id} 提取"
    elif session_id:
        msgs = list(
            db.scalars(
                select(MessageModel)
                .where(MessageModel.session_id == session_id, MessageModel.speaker_type == "character")
                .order_by(MessageModel.id.desc())
                .limit(5)
            )
        )
        content_source = "\n".join(m.content for m in reversed(msgs) if m.content)
        if not title:
            title = f"从会话 #{session_id} 沉淀"

    if not content_source:
        raise ValueError("无法获取消息内容")

    meta = params.get("meta_json", {})
    meta["auto_extracted"] = True
    meta["extraction_time"] = datetime.utcnow().isoformat()
    meta["verification_status"] = "pending"
    meta["source_kind"] = "action_auto_extract"

    if encyclopedia_id:
        enc = db.get(WorldEncyclopediaModel, encyclopedia_id)
        if not enc:
            raise ValueError("百科库不存在")
    else:
        enc = WorldEncyclopediaModel(
            name=f"自动沉淀 #{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}",
            description="由安全宏动作自动创建",
            is_official=0,
        )
        db.add(enc)
        db.flush()
        encyclopedia_id = enc.id

    entry = EncyclopediaEntryModel(
        encyclopedia_id=encyclopedia_id,
        title=title[:300],
        entry_type=entry_type,
        summary=params.get("summary", content_source[:100]),
        content=content_source,
        meta_json=meta,
    )
    db.add(entry)
    db.flush()

    db.add(EntryVersionModel(
        entry_id=entry.id,
        title=title[:300],
        content=content_source,
        meta_snapshot_json=meta,
        change_note="安全宏自动创建",
        created_by="action:create_entry",
    ))
    db.commit()

    return {"ok": True, "entry_id": entry.id, "title": entry.title, "encyclopedia_id": encyclopedia_id}


def _action_check_conflicts(params: dict, db: Session) -> dict:
    """检查指定百科库中条目的潜在冲突。基于标题和标签重叠检测。"""
    encyclopedia_id = params.get("encyclopedia_id")
    if not encyclopedia_id:
        raise ValueError("encyclopedia_id 不能为空")

    entries = list(
        db.scalars(
            select(EncyclopediaEntryModel)
            .where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
            .order_by(EncyclopediaEntryModel.id.asc())
        )
    )
    if len(entries) < 2:
        return {"ok": True, "conflicts": [], "message": "条目不足 2 条，无需检查"}

    entry_by_id = {entry.id: entry for entry in entries}

    def describe_entries(ids: list[int]) -> list[dict]:
        return [
            {
                "id": entry.id,
                "title": entry.title,
                "entry_type": entry.entry_type,
                "summary": entry.summary or "",
            }
            for entry_id in ids
            if (entry := entry_by_id.get(entry_id)) is not None
        ]

    conflicts = []
    seen_titles: dict[str, int] = {}
    for e in entries:
        t = e.title.strip().lower()
        if t in seen_titles:
            entry_ids = [seen_titles[t], e.id]
            conflicts.append({
                "type": "duplicate_title",
                "title": e.title,
                "entry_ids": entry_ids,
                "entries": describe_entries(entry_ids),
                "message": f"标题「{e.title}」重复",
            })
        seen_titles[t] = e.id

    tags_map: dict[str, list[int]] = {}
    for e in entries:
        if e.tags:
            for tag in e.tags.split(","):
                tag = tag.strip().lower()
                if tag:
                    tags_map.setdefault(tag, []).append(e.id)
    for tag, ids in tags_map.items():
        if len(ids) > 5:
            entry_ids = ids[:10]
            conflicts.append({
                "type": "tag_overload",
                "tag": tag,
                "entry_ids": entry_ids,
                "entries": describe_entries(entry_ids),
                "message": f"标签「{tag}」被 {len(ids)} 个条目使用，可能过于宽泛",
            })

    return {"ok": True, "conflicts": conflicts, "conflict_count": len(conflicts)}


def _action_summarize_session(params: dict, db: Session) -> dict:
    """将会话最近消息压缩为百科事件条目。"""
    session_id = params.get("session_id")
    encyclopedia_id = params.get("encyclopedia_id")
    branch_id = (params.get("branch_id") or "main").strip() or "main"
    if not session_id:
        raise ValueError("session_id 不能为空")

    try:
        messages, _ = get_session_messages_page(db, session_id, None, 30, branch_id)
    except BranchContextError as exc:
        raise ValueError(str(exc)) from exc
    if not messages:
        return {"ok": False, "message": "会话无消息"}

    summary_lines = []
    for msg in messages:
        speaker = msg.speaker_type
        if msg.character:
            speaker = msg.character.name
        text = (msg.content or "")[:200]
        summary_lines.append(f"[{speaker}] {text}")

    summary_text = "\n".join(summary_lines)
    title = params.get("title", f"会话摘要 #{session_id}_{datetime.utcnow().strftime('%H%M%S')}")

    if not encyclopedia_id:
        enc = WorldEncyclopediaModel(
            name=f"会话摘要 {datetime.utcnow().strftime('%Y%m%d')}",
            description="由安全宏自动创建",
            is_official=0,
        )
        db.add(enc)
        db.flush()
        encyclopedia_id = enc.id

    entry = EncyclopediaEntryModel(
        encyclopedia_id=encyclopedia_id,
        title=title[:300],
        entry_type="event",
        summary=f"会话 #{session_id} 的 {len(messages)} 条消息摘要",
        content=summary_text,
        tags="会话摘要,自动生成",
        meta_json={
            "auto_extracted": True,
            "source_session_id": session_id,
            "source_branch_id": branch_id,
            "verification_status": "pending",
        },
    )
    db.add(entry)
    db.commit()

    return {"ok": True, "entry_id": entry.id, "title": entry.title, "message_count": len(messages)}


def _action_auto_fill_entry(params: dict, db: Session) -> dict:
    """对百科条目的空字段补占位提示。实际 AI 补全需要由前端触发 LLM 调用。"""
    entry_id = params.get("entry_id")
    if not entry_id:
        raise ValueError("entry_id 不能为空")

    entry = db.get(EncyclopediaEntryModel, entry_id)
    if not entry:
        raise ValueError("条目不存在")

    changes = {}
    meta = entry.meta_json or {}
    if not entry.summary:
        entry.summary = (entry.content or "")[:100]
        changes["summary"] = "已从内容截取前 100 字"
    if not entry.tags:
        entry.tags = "待分类"
        changes["tags"] = "已设置默认标签"
    if not meta.get("verification_status"):
        meta["verification_status"] = "pending"
        meta["auto_filled_at"] = datetime.utcnow().isoformat()
        entry.meta_json = meta
        changes["verification_status"] = "已设置为 pending"

    db.commit()
    return {"ok": True, "entry_id": entry_id, "changes": changes, "message": "空字段已补全" if changes else "无空字段需要补全"}


# ── 注册内置动作 ─────────────────────────────────────────────────

register_action(
    "create_entry_from_message",
    "沉淀为百科条目",
    "从聊天消息中提取关键信息，自动创建百科条目",
    [
        {"key": "message_id", "label": "消息 ID", "type": "int", "required": False},
        {"key": "encyclopedia_id", "label": "目标百科库 ID", "type": "int", "required": False},
        {"key": "entry_type", "label": "条目类型", "type": "select", "options": ["event", "concept", "character", "faction", "location"], "required": False},
        {"key": "title", "label": "标题", "type": "string", "required": False},
    ],
    _action_create_entry,
    scope="message",
)

register_action(
    "check_setting_conflicts",
    "检查设定冲突",
    "对比百科库中条目标题和标签的重复/冲突情况",
    [
        {"key": "encyclopedia_id", "label": "百科库 ID", "type": "int", "required": True},
    ],
    _action_check_conflicts,
    scope="encyclopedia",
)

register_action(
    "summarize_session_events",
    "总结本次事件",
    "将本次对话压缩为百科事件条目",
    [
        {"key": "session_id", "label": "会话 ID", "type": "int", "required": True},
        {"key": "encyclopedia_id", "label": "目标百科库 ID", "type": "int", "required": False},
        {"key": "branch_id", "label": "故事线分支 ID", "type": "string", "required": False},
        {"key": "title", "label": "标题", "type": "string", "required": False},
    ],
    _action_summarize_session,
    scope="session",
)

register_action(
    "auto_fill_entry_fields",
    "一键补齐",
    "对百科条目的空字段（摘要/标签/校验状态）自动补全",
    [
        {"key": "entry_id", "label": "条目 ID", "type": "int", "required": True},
    ],
    _action_auto_fill_entry,
    scope="entry",
)
