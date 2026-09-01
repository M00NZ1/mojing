"""酒馆式聊天记录单向导入：JSON 根级 mes[] 或 JSONL 每行一条（调研 §4.2 C）。"""

from __future__ import annotations

import json
from typing import Any


def _append_mes_item(out: list[dict], m: dict) -> None:
    if not isinstance(m, dict):
        return
    content = str(m.get("mes") or m.get("message") or "").strip()
    if not content:
        return
    is_user = bool(m.get("is_user")) or str(m.get("role", "")).lower() == "user"
    out.append(
        {
            "speaker": "user" if is_user else "character",
            "content": content,
            "raw": m,
        }
    )


def parse_tavern_chat_file(text: str) -> list[dict]:
    text = text.strip()
    if not text:
        return []
    out: list[dict] = []
    try:
        root = json.loads(text)
    except json.JSONDecodeError:
        root = None
    if isinstance(root, dict) and isinstance(root.get("mes"), list):
        for m in root["mes"]:
            _append_mes_item(out, m if isinstance(m, dict) else {})
        return out
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            obj: Any = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            if "mes" in obj:
                _append_mes_item(out, obj)
            elif "content" in obj and ("role" in obj or "is_user" in obj):
                role = str(obj.get("role", "")).lower()
                is_user = bool(obj.get("is_user")) or role in ("user", "human")
                content = str(obj.get("content") or "").strip()
                if content:
                    out.append({"speaker": "user" if is_user else "character", "content": content, "raw": obj})
    return out
