"""
发言调度器 — 控制多角色会话中谁在何时发言。

策略：
- natural: 根据提及+活跃度+talkativeness 评分
- list: 按参与者列表顺序轮流
- pooled: 尽量均分发言次数
"""
from __future__ import annotations

import random
from collections import Counter
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session, joinedload, selectinload

from ..models import MessageModel, SessionParticipantModel


def select_speakers(
    db: Session,
    session_id: int,
    user_message: str,
    max_speakers: int = 2,
    branch_id: str = "main",
) -> tuple[list[int], str]:
    """按当前会话配置的发言策略选出本轮发言人。"""

    participants = list(
        db.scalars(
            select(SessionParticipantModel)
            .options(selectinload(SessionParticipantModel.character))
            .where(SessionParticipantModel.session_id == session_id)
            .order_by(SessionParticipantModel.sort_order.asc(), SessionParticipantModel.id.asc())
        )
    )
    if not participants:
        return [], "当前会话没有可发言人物。"

    # 过滤禁言角色
    active = [p for p in participants if not p.muted]
    if not active:
        return [], "所有参与者均被禁言。"

    # force_next 优先
    forced = [p for p in active if p.force_next]
    if forced:
        ids = [p.character_id for p in forced[:max_speakers]]
        for p in forced[:max_speakers]:
            p.force_next = False
        db.commit()
        return ids, "检测到强制发言标记，优先发言。"

    # 检测策略分组
    strategy_groups = {}
    for p in active:
        st = p.speaker_strategy or "natural"
        strategy_groups.setdefault(st, []).append(p)

    # 策略分发（按第一个参与者的策略为主）
    primary_strategy = active[0].speaker_strategy or "natural"

    lowered = (user_message or "").lower().strip()

    if primary_strategy == "list" or strategy_groups.get("list"):
        return _select_list(active, max_speakers)
    elif primary_strategy == "pooled" or strategy_groups.get("pooled"):
        return _select_pooled(db, session_id, active, max_speakers, branch_id)

    return _select_natural(db, session_id, active, user_message, max_speakers, branch_id)


def _select_list(participants: list, max_speakers: int) -> tuple[list[int], str]:
    """按参与者顺序选择。"""
    ids = [p.character_id for p in participants[:max_speakers]]
    return ids, "按参与者列表顺序选择。"


def _select_pooled(
    db: Session, session_id: int, participants: list, max_speakers: int, branch_id: str
) -> tuple[list[int], str]:
    """尽量均分发言机会。"""
    counts: dict[int, int] = Counter()
    recent = list(
        db.scalars(
            select(MessageModel.character_id)
            .where(
                MessageModel.session_id == session_id,
                MessageModel.speaker_type == "character",
                MessageModel.branch_id == (branch_id or "main"),
                MessageModel.include_in_context == True,
            )
            .order_by(MessageModel.id.desc())
            .limit(100)
        )
    )
    for cid in recent:
        if cid:
            counts[cid] += 1

    sorted_p = sorted(
        participants, key=lambda p: (counts.get(p.character_id, 0), -p.sort_order)
    )
    ids = [p.character_id for p in sorted_p[:max_speakers]]
    return ids, "按发言次数均衡分配，优先选择发言较少的角色。"


def _select_natural(
    db: Session, session_id: int, participants: list,
    user_message: str, max_speakers: int, branch_id: str
) -> tuple[list[int], str]:
    """按提及+活跃度+talkativeness 评分。"""
    lowered = (user_message or "").lower().strip()
    scored: list[tuple[float, object]] = []
    reasons: list[str] = []

    # 获取最近发言时间
    latest_times: dict[int, datetime | None] = {}
    for p in participants:
        msg = db.scalar(
            select(MessageModel)
            .where(
                MessageModel.session_id == session_id,
                MessageModel.character_id == p.character_id,
                MessageModel.speaker_type == "character",
                MessageModel.include_in_context == True,
            )
            .order_by(MessageModel.id.desc())
        )
        latest_times[p.character_id] = msg.created_at if msg else None

    now = datetime.utcnow()

    for p in participants:
        score = p.talkativeness if hasattr(p, 'talkativeness') else 0.7  # default
        mention_bonus = 0.0
        mention_reason = ""

        # 点名加分
        if lowered and p.character and p.character.name:
            name_lower = p.character.name.lower()
            if name_lower in lowered:
                mention_bonus = 5.0
                mention_reason = f"点名+5"

        # 提及关键词加分
        if lowered and p.character and p.character.name:
            for kw in lowered.split():
                if len(kw) > 1 and kw in (p.character.name.lower()):
                    mention_bonus = max(mention_bonus, 3.0)
                    mention_reason = f"提及+3"

        # 最近发言降权
        last = latest_times.get(p.character_id)
        recency_penalty = 0.0
        if last and now:
            hours_since = (now - last).total_seconds() / 3600
            if hours_since < 0.5:  # 30分钟内刚说过
                recency_penalty = -2.0
            elif hours_since < 2:
                recency_penalty = -1.0

        total = score + mention_bonus + recency_penalty
        scored.append((total, p))

    scored.sort(key=lambda x: x[0], reverse=True)
    ids = [p.character_id for _, p in scored[:max_speakers]]
    detail = ", ".join(
        f"{p.character.name}({s:.1f})" for s, p in scored[:max_speakers]
    )
    return ids, f"Natural 策略选择: {detail}"
