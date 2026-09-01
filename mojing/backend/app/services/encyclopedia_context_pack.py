"""
百科批量 / 扩展生成用的「节选上下文包」：在可控 token 预算内向模型提供已有条目摘要，
与对话侧 `_get_encyclopedia_hits` 使用相同的 tiktoken/字符回退估算。
"""

from __future__ import annotations

import re

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from ..models import EncyclopediaEntryModel


def count_tokens_approx(text: str) -> int:
    """与 `chat_service._count_tokens` 对齐，避免从 chat_service 循环导入。"""
    if not text:
        return 0
    try:
        import tiktoken

        enc = tiktoken.get_encoding("cl100k_base")
        return len(enc.encode(text))
    except ImportError:
        return int(len(text) * 1.5)


def digest_context_keywords(text: str, max_keys: int = 14) -> list[str]:
    raw = (text or "").strip()
    if not raw:
        return []
    parts = re.split(r"[\s,，。;；、]+", raw)
    out: list[str] = []
    for p in parts:
        t = p.strip().lower()
        if len(t) >= 2 and t not in out:
            out.append(t)
        if len(out) >= max_keys:
            break
    return out


def entry_digest_score(entry: EncyclopediaEntryModel, keys: list[str], target_entry_type: str) -> float:
    hay = f"{entry.title or ''} {entry.summary or ''} {entry.tags or ''}".lower()
    score = 0.0
    for k in keys:
        if k and k in hay:
            score += 1.2
    if entry.is_featured:
        score += 3.0
    if (entry.entry_type or "") == target_entry_type:
        score += 1.0
    return score


def build_entry_reference_digest(
    db: Session,
    encyclopedia_id: int,
    entry_type: str,
    context_hint: str,
    *,
    query_override: str | None = None,
    digest_token_budget: int | None = 900,
    max_chars_safety: int = 6000,
) -> str:
    """
    :param query_override: 与 context_hint 合并后用于拆词 + 数据库 ilike 补捞（如分类名、用户短指令）。
    :param digest_token_budget: None 时仅用 max_chars_safety 做字符上限（旧行为近似）；否则按 token 估算截断。
    """
    merged = " ".join(x for x in (context_hint or "", query_override or "") if (x or "").strip()).strip()
    keys = digest_context_keywords(merged)
    rows = list(
        db.scalars(
            select(EncyclopediaEntryModel)
            .where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
            .order_by(
                EncyclopediaEntryModel.is_featured.desc(),
                EncyclopediaEntryModel.updated_at.desc(),
            )
            .limit(160)
        )
    )
    seen: set[int] = {e.id for e in rows}
    extras: list[EncyclopediaEntryModel] = []
    for kw in keys[:4]:
        if len(kw) < 2:
            continue
        pat = f"%{kw}%"
        hits = list(
            db.scalars(
                select(EncyclopediaEntryModel)
                .where(
                    EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id,
                    or_(
                        EncyclopediaEntryModel.title.ilike(pat),
                        EncyclopediaEntryModel.summary.ilike(pat),
                        EncyclopediaEntryModel.content.ilike(pat),
                    ),
                )
                .limit(24)
            )
        )
        for h in hits:
            if h.id not in seen:
                seen.add(h.id)
                extras.append(h)
    combined = rows + extras
    if not combined:
        return ""
    scored = sorted(
        combined,
        key=lambda e: (
            entry_digest_score(e, keys, entry_type),
            1 if e.is_featured else 0,
            e.updated_at.timestamp() if e.updated_at else 0.0,
        ),
        reverse=True,
    )
    lines: list[str] = []
    used_chars = 0
    used_tokens = 0
    for e in scored:
        summ = (e.summary or "").strip()
        body = (e.content or "").strip()
        snippet = (summ[:240] if summ else body[:180]).replace("\n", " ").strip()
        title = (e.title or "").strip()
        if not snippet and not title:
            continue
        line = f"- [{e.entry_type}] {title[:120]}：{snippet}"
        if used_chars + len(line) + 1 > max_chars_safety:
            break
        if digest_token_budget is not None:
            t_add = count_tokens_approx(line + "\n")
            if used_tokens + t_add > digest_token_budget and lines:
                break
            used_tokens += t_add
        used_chars += len(line) + 1
        lines.append(line)
    if not lines:
        return ""
    return (
        "【现有条目参考（仅节选摘要/开头，供语气与世界观对齐；禁止复述或照抄已有标题）】\n"
        + "\n".join(lines)
    )
