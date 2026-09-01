"""SillyTavern WorldInfo JSON → 百科条目草稿（服务端批量落库）。"""

from __future__ import annotations

import json
from typing import Any

from .worldinfo_import_service import iter_worldinfo_entries, worldinfo_entry_to_row


def parse_worldinfo_payload(raw: str) -> list[dict[str, Any]]:
    """
    解析酒馆 WorldInfo 导出 JSON。
    兼容：顶层数组、`{ "entries": [...] }`、`entries` 为 dict（uid→条目）等（见 worldinfo_import_service）。
    """
    text = (raw or "").strip()
    if not text:
        raise ValueError("JSON 为空")
    data = json.loads(text)
    entries = iter_worldinfo_entries(data)
    rows = [worldinfo_entry_to_row(e, i) for i, e in enumerate(entries) if isinstance(e, dict)]
    if not rows:
        raise ValueError("未解析出任何 WorldInfo 条目")
    return [
        {
            "title": r["title"],
            "entry_type": r.get("entry_type", "concept"),
            "summary": r.get("summary", ""),
            "content": r.get("content", ""),
            "tags": r.get("tags", ""),
            "meta_json": r.get("meta_json") or {},
            "confidence": "confirmed",
        }
        for r in rows
    ]
