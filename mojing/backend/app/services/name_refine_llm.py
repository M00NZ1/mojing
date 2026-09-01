"""名称生成结果可选 LLM 润色（与百科「名称生成器」对接）。"""

from __future__ import annotations

import json
import re
from typing import Any

from openai import OpenAI
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import CharacterModel


def refine_name_batch_with_llm(
    db: Session,
    items: list[dict[str, Any]],
    style: str,
    name_type: str,
) -> tuple[list[dict[str, Any]], str | None]:
    """
    在已有规则生成结果上做一次 LLM 润色，保持条数不变。
    无可用 LLM 配置时返回原列表与说明字符串。
    """
    if not items:
        return items, None

    api_key = ""
    api_base_url = ""
    model_name = ""

    first_char = db.scalar(
        select(CharacterModel)
        .where(CharacterModel.api_key != "", CharacterModel.api_key != "local")
        .order_by(CharacterModel.id.asc())
        .limit(1)
    )
    if first_char:
        api_key = first_char.api_key or ""
        api_base_url = (first_char.api_base_url or "").strip()
        model_name = (first_char.model_name or "").strip()

    if not api_key or not api_base_url:
        return items, "未配置可用 LLM（请在「人物」中至少保存一个带 API Key 的角色），已返回规则生成结果。"

    try:
        from .crypto_service import decrypt_api_key

        decrypted_key = decrypt_api_key(api_key)
    except Exception:
        decrypted_key = api_key

    names_in = [str(x.get("name", "")).strip() for x in items]
    system = (
        "你是奇幻/科幻作品命名编辑。用户会给你一批程序随机生成的名称。\n"
        "规则：\n"
        "1. 只输出 JSON 数组，不要 Markdown、不要解释。\n"
        "2. 数组长度必须与输入名称条数完全一致。\n"
        "3. 每项为对象：{\"name\": \"...\", \"meaning\": \"一句中文释义\"}。\n"
        "4. 在保持风格（用户会说明）与类型（人物/地名/功法/物品/势力）前提下润色或重写名称，避免粗劣随机感；不要与输入完全相同的概率可高于 50%。\n"
        "5. 名称中不要换行；释义 60 字以内。\n"
    )
    user = (
        f"风格：{style}\n类型：{name_type}\n\n"
        f"待润色名称（按顺序）：{json.dumps(names_in, ensure_ascii=False)}\n\n"
        "请返回 JSON 数组。"
    )

    try:
        client = OpenAI(api_key=decrypted_key, base_url=api_base_url)
        response = client.chat.completions.create(
            model=model_name or "deepseek-chat",
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            temperature=0.75,
            max_tokens=1800,
        )
        raw = (response.choices[0].message.content or "[]").strip()
        if "```" in raw:
            m = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", raw, re.DOTALL)
            if m:
                raw = m.group(1).strip()
        parsed = json.loads(raw)
        if not isinstance(parsed, list) or len(parsed) != len(items):
            return items, "LLM 返回格式或条数不符，已保留规则生成结果。"
        out: list[dict[str, Any]] = []
        for i, row in enumerate(parsed):
            if not isinstance(row, dict):
                return items, "LLM 返回项非对象，已保留规则生成结果。"
            nm = str(row.get("name", "")).strip()
            me = str(row.get("meaning", "")).strip() or items[i].get("meaning", "")
            if not nm:
                out.append(items[i])
            else:
                out.append({"name": nm, "meaning": me})
        return out, "已使用人物中的 LLM 配置完成一轮润色。"
    except json.JSONDecodeError:
        return items, "LLM 返回非合法 JSON，已保留规则生成结果。"
    except Exception as exc:
        return items, f"LLM 润色失败：{exc}；已保留规则生成结果。"
