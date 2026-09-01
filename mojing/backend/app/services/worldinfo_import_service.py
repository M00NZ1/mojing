"""SillyTavern / Lorebook 风格 WorldInfo JSON → 百科条目字段（调研 §4.2 B）。"""

from __future__ import annotations

import json
from typing import Any


def _as_list(x: Any) -> list:
    if x is None:
        return []
    if isinstance(x, list):
        return x
    if isinstance(x, str) and x.strip():
        return [x.strip()]
    return []


def iter_worldinfo_entries(payload: Any) -> list[dict]:
    """从常见 ST 导出结构中取出条目 dict 列表。"""
    if payload is None:
        return []
    if isinstance(payload, list):
        return [e for e in payload if isinstance(e, dict)]
    if isinstance(payload, dict):
        for key in ("entries", "lorebook", "data"):
            inner = payload.get(key)
            if isinstance(inner, list):
                return [e for e in inner if isinstance(e, dict)]
        if isinstance(payload.get("entries"), dict):
            return [e for e in payload["entries"].values() if isinstance(e, dict)]
    return []


def worldinfo_entry_to_row(entry: dict, index: int) -> dict:
    """单条 WI → EncyclopediaEntryModel 可用字段 dict。"""
    keys = entry.get("keys") or entry.get("key") or []
    if isinstance(keys, str):
        keys = [keys] if keys.strip() else []
    if not isinstance(keys, list):
        keys = _as_list(keys)
    sec = entry.get("secondary_keys") or []
    if isinstance(sec, str):
        sec = [sec] if sec.strip() else []
    if not isinstance(sec, list):
        sec = _as_list(sec)

    comment = (entry.get("comment") or entry.get("name") or "").strip()
    content = str(entry.get("content") or "").strip()
    title = comment[:300] if comment else (",".join(str(k) for k in keys[:8])[:120] or f"WorldInfo-{index}")

    tags = ",".join(str(k) for k in keys if str(k).strip())[:500]
    summary = content[:400] + ("…" if len(content) > 400 else "")
    meta = {
        "worldinfo_import": True,
        "wi_keys": [str(k) for k in keys],
        "wi_secondary_keys": [str(k) for k in sec],
        "wi_constant": entry.get("constant"),
        "wi_selective": entry.get("selective"),
        "wi_depth": entry.get("depth"),
        "wi_order": entry.get("order"),
        "wi_uid": entry.get("uid"),
        "wi_position": entry.get("position"),
    }
    ext = entry.get("extensions")
    if isinstance(ext, dict):
        meta["wi_extensions"] = ext
    return {
        "title": title[:300],
        "content": content,
        "summary": summary,
        "tags": tags,
        "meta_json": meta,
        "entry_type": "concept",
        "sort_order": int(entry.get("order") or index),
    }


def parse_worldinfo_file_text(text: str) -> list[dict]:
    data = json.loads(text)
    entries = iter_worldinfo_entries(data)
    return [worldinfo_entry_to_row(e, i) for i, e in enumerate(entries)]
