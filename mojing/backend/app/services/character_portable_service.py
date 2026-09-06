"""Portable character share: JSON/TXT/DOCX round-trip without exporting secrets."""

from __future__ import annotations

import json
import re
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import CharacterModel, CharacterProfileModel

PORTABLE_KIND = "mojing_character_portable"
LEGACY_PORTABLE_KIND = "mojing_character_portable"
PORTABLE_KINDS = frozenset({PORTABLE_KIND, LEGACY_PORTABLE_KIND})
PORTABLE_VERSION = 1
TXT_HEADER = f"# {PORTABLE_KIND} v{PORTABLE_VERSION}"
LEGACY_TXT_HEADER = f"# {LEGACY_PORTABLE_KIND} v{PORTABLE_VERSION}"
TXT_SEP = "\n---\n"


def build_portable_payload(
    character: CharacterModel,
    profile: CharacterProfileModel | None,
) -> dict[str, Any]:
    """Share-safe document (no API keys)."""
    base = {
        "kind": PORTABLE_KIND,
        "version": PORTABLE_VERSION,
        "name": character.name or "",
        "persona_prompt": character.persona_prompt or "",
        "model_name": character.model_name or "",
        "api_base_url": character.api_base_url or "",
        "temperature": float(character.temperature if character.temperature is not None else 0.9),
        "max_tokens": int(character.max_tokens or 1200),
        "avatar_color": character.avatar_color or "",
        "notes": "导入后请在人物中自行填写 API Key；本包不含任何密钥。",
    }
    if profile:
        base["profile"] = {
            "source_filename": profile.source_filename or "",
            "raw_persona_text": profile.raw_persona_text or "",
            "character_card_markdown": profile.character_card_markdown or "",
            "character_card_json": profile.character_card_json or {},
        }
    return base


def portable_to_txt(payload: dict[str, Any]) -> str:
    meta = {k: v for k, v in payload.items() if k != "persona_prompt"}
    head = json.dumps(meta, ensure_ascii=False, indent=2)
    persona = payload.get("persona_prompt") or ""
    return f"{TXT_HEADER}\n{head}{TXT_SEP}{persona}"


def portable_from_txt(text: str) -> dict[str, Any]:
    raw = (text or "").strip()
    if not raw:
        raise ValueError("空文本")
    header = TXT_HEADER if raw.startswith(TXT_HEADER) else LEGACY_TXT_HEADER if raw.startswith(LEGACY_TXT_HEADER) else None
    if header is not None:
        rest = raw[len(header) :].lstrip()
        if TXT_SEP not in rest:
            raise ValueError("TXT 格式无效：缺少 --- 分隔")
        meta_part, persona = rest.split(TXT_SEP, 1)
        meta = json.loads(meta_part.strip())
        if meta.get("kind") not in PORTABLE_KINDS:
            raise ValueError("不是墨境导出的便携 TXT")
        meta["persona_prompt"] = persona.strip()
        return meta
    # 宽松：首行作名称，其余作 persona
    lines = raw.split("\n")
    name = lines[0].strip()[:120] if lines else "未命名"
    persona = "\n".join(lines[1:]).strip() if len(lines) > 1 else ""
    return {
        "kind": PORTABLE_KIND,
        "version": PORTABLE_VERSION,
        "name": name or "未命名",
        "persona_prompt": persona,
    }


def portable_json_dumps(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, indent=2)


def _extract_json_object(raw: str) -> dict[str, Any]:
    s = (raw or "").strip()
    if "```" in s:
        m = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", s, re.DOTALL)
        if m:
            s = m.group(1).strip()
    return json.loads(s)


SUMMARY_SYSTEM_PROMPT = """你是「角色卡便携导出」助手。用户要把角色设定整理成可分享给他人、并能被墨境再导入的结构。

硬性规则：
1. 只输出一个 JSON 对象，不要 Markdown、不要解释。
2. JSON 必须符合以下键（缺失的用空字符串）：
   - kind: 固定字符串 "mojing_character_portable"
   - version: 固定数字 1
   - name: 角色名称（简短）
   - persona_prompt: 合并后的完整角色设定正文，用中文；保留重要设定，可删减重复与对话示例；总长度建议不超过 8000 字。
   - model_name, api_base_url: 若原文有且适合分享则填入，否则填空字符串（不要编造 Key）。
   - notes: 一两句给导入者的说明（中文），提醒对方导入后自行配置 API Key。
3. 绝对不要输出任何 api key、token、密码字段。
4. persona_prompt 必须自洽、可直接用于角色扮演。"""


def summarize_portable_with_public_llm(
    *,
    client: Any,
    model: str,
    source_dump: str,
) -> dict[str, Any]:
    user = (
        "以下是当前角色的原始资料（可能很长）。请生成符合规则的 JSON：\n\n"
        f"{source_dump[:120000]}"
    )
    response = client.chat.completions.create(
        model=model or "deepseek-chat",
        messages=[
            {"role": "system", "content": SUMMARY_SYSTEM_PROMPT},
            {"role": "user", "content": user},
        ],
        temperature=0.4,
        max_tokens=8000,
    )
    raw = response.choices[0].message.content or "{}"
    data = _extract_json_object(raw)
    if data.get("kind") not in PORTABLE_KINDS:
        data["kind"] = PORTABLE_KIND
    data["version"] = PORTABLE_VERSION
    data.setdefault("name", "")
    data.setdefault("persona_prompt", "")
    data.setdefault("model_name", "")
    data.setdefault("api_base_url", "")
    data.setdefault("notes", "")
    return data


def collect_character_dump_for_summary(db: Session, character: CharacterModel) -> str:
    parts: list[str] = [
        f"名称: {character.name}",
        f"性格设定(persona_prompt):\n{character.persona_prompt or ''}",
        f"模型: {character.model_name}\nBase: {character.api_base_url}",
    ]
    prof = db.scalar(
        select(CharacterProfileModel).where(CharacterProfileModel.character_id == character.id)
    )
    if prof:
        parts.append(f"原始设定文件名: {prof.source_filename}")
        if prof.raw_persona_text:
            parts.append(f"原始设定文本:\n{prof.raw_persona_text[:80000]}")
        if prof.character_card_markdown:
            parts.append(f"人物卡 Markdown:\n{prof.character_card_markdown[:20000]}")
    return "\n\n".join(parts)


def allocate_unique_character_name(db: Session, base: str) -> str:
    base = (base or "未命名").strip()[:120] or "未命名"
    if not db.scalar(select(CharacterModel).where(CharacterModel.name == base)):
        return base
    for i in range(2, 5000):
        cand = f"{base}_{i}"[:120]
        if not db.scalar(select(CharacterModel).where(CharacterModel.name == cand)):
            return cand
    import time

    return f"{base[:100]}_{int(time.time())}"
