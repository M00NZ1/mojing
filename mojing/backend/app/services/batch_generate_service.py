"""
批量 AI 生成百科条目服务。
按文档第8章需求2实现批量生成端点逻辑。

逐条生成：每次 LLM 调用只生成 1 条，写完再请求下一条，减少跨题材串味。
题材锁：见 encyclopedia_theme_rules.theme_addon_for_encyclopedia
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EncyclopediaEntryModel, WorldEncyclopediaModel
from ..services.llm_client import build_public_text_client
from ..services.llm_retry import safe_non_streaming_call
from .encyclopedia_context_pack import build_entry_reference_digest
from .encyclopedia_theme_rules import theme_addon_for_encyclopedia

logger = logging.getLogger(__name__)

BUILTIN_TEMPLATES_PATH = Path(__file__).parent / "builtin_templates.json"


def load_builtin_template(entry_type: str) -> dict:
    """从内置模板JSON中加载对应类型的模板示例。"""
    if not BUILTIN_TEMPLATES_PATH.exists():
        return {}

    with open(BUILTIN_TEMPLATES_PATH, "r", encoding="utf-8") as f:
        templates = json.load(f)

    return templates.get(entry_type, {})


def _build_template_hint(entry_type: str) -> str:
    template_data = load_builtin_template(entry_type)
    if not template_data:
        return ""
    template_example = f"""标题示例: {template_data.get('title', '')}
摘要示例: {template_data.get('summary', '')[:300]}
meta_json字段数: {len(template_data.get('meta_json', {}))} 个
内容深度: {len(template_data.get('content', ''))} 字"""
    return f"""【字段深度标准】
以下是一个高质量 {entry_type} 条目的完整示例，你生成的内容应达到同等丰富程度：
{template_example}

每个字段的内容长度: 100-2000 字不等。
你必须为所有必要字段生成内容，不能只填1-2个。
空字段数目标: 0（全部填满）。
"""


def _single_entry_prompt(
    *,
    encyclopedia: WorldEncyclopediaModel,
    entry_type: str,
    existing_titles: list[str],
    context_hint: str,
    template_hint: str,
    theme_addon: str,
    index_1based: int,
    total: int,
    reference_digest: str = "",
) -> str:
    wp = (getattr(encyclopedia, "world_prompt", None) or "").strip()
    wp_block = f"世界补充提示（作者填写，须遵守）:\n{wp}\n" if wp else ""
    theme = f"{theme_addon}\n" if theme_addon else ""
    th = f"{template_hint}\n" if template_hint else ""
    digest_block = f"{reference_digest.strip()}\n\n" if (reference_digest or "").strip() else ""
    return f"""你是世界观设计师。请**只为**百科库「{encyclopedia.name}」生成 **恰好 1 个** {entry_type} 条目（第 {index_1based} / {total} 条）。

{th}{wp_block}
{theme}{digest_block}已有条目标题（避免重复）: {json.dumps(existing_titles[-40:], ensure_ascii=False)}
用户额外提示: {context_hint or '无'}
百科库描述: {encyclopedia.description or '无'}
体裁标签: {getattr(encyclopedia, 'genre_tags', '') or '未指定'}

输出格式: **仅**输出 JSON 数组，且数组长度必须为 1。元素格式如下：
[
  {{
    "title": "条目标题(≤100字)",
    "summary": "摘要(≤300字)",
    "content": "详细内容(≤2000字)",
    "meta_json": {{
      "alias": ["别名1", "别名2"],
      "background": "背景描述",
      "appearance": "外观描述",
      "personality": "性格描述",
      "abilities": ["能力1", "能力2"],
      "relationships": "关系描述",
      "source": "AI批量生成"
    }}
  }}
]

硬性要求：
- 只生成 1 条；多写视为错误
- 标题不能与已有条目重复
- meta_json 至少包含 5 个字段，且每个键对应非空、有信息量的字符串或数组（禁止全用「待补充」「TBD」等敷衍占位）
- title、summary、content 必须为非空字符串，长度与信息密度须达到可直接入库使用的标准
- 内容必须严格契合本百科库题材与描述，禁止混入其它世界观的等级/力量体系
"""


def batch_generate_entries(
    db: Session,
    encyclopedia_id: int,
    entry_type: str,
    count: int = 10,
    context_hint: str = "",
    reference_style: str = "builtin",
    sequential: bool = True,
    query_override: str | None = None,
    digest_token_budget: int | None = 900,
) -> dict:
    """
    批量 AI 生成百科条目。

    sequential=True（默认）：每条单独一次 LLM 调用，降低串味；完成一条再生成下一条。
    sequential=False：单次调用生成多条（旧行为，更快但易混题材）。
    """
    del reference_style  # 预留参数，与历史 API 兼容

    encyclopedia = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if not encyclopedia:
        return {"created": 0, "entries": [], "error": "百科库不存在"}

    count = max(1, min(count, 20))

    template_hint = _build_template_hint(entry_type) if load_builtin_template(entry_type) else ""
    theme_addon = theme_addon_for_encyclopedia(encyclopedia)

    if not sequential:
        return _batch_generate_legacy_single_call(
            db=db,
            encyclopedia=encyclopedia,
            entry_type=entry_type,
            count=count,
            context_hint=context_hint,
            template_hint=template_hint,
            theme_addon=theme_addon,
            query_override=query_override,
            digest_token_budget=digest_token_budget,
        )

    created = 0
    results: list[dict] = []
    errors: list[str] = []

    try:
        client, model = build_public_text_client(db)
    except Exception as e:
        logger.error("批量生成：无法创建 LLM 客户端: %s", e)
        return {"created": 0, "entries": [], "sequential": True, "error": str(e)}

    for i in range(count):
        existing_titles = [
            t[0]
            for t in db.execute(
                select(EncyclopediaEntryModel.title).where(
                    EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id,
                    EncyclopediaEntryModel.entry_type == entry_type,
                ).limit(80)
            )
        ]
        reference_digest = build_entry_reference_digest(
            db,
            encyclopedia_id,
            entry_type,
            context_hint,
            query_override=query_override,
            digest_token_budget=digest_token_budget,
        )
        prompt = _single_entry_prompt(
            encyclopedia=encyclopedia,
            entry_type=entry_type,
            existing_titles=existing_titles,
            context_hint=context_hint,
            template_hint=template_hint,
            theme_addon=theme_addon,
            index_1based=i + 1,
            total=count,
            reference_digest=reference_digest,
        )
        try:
            raw = safe_non_streaming_call(
                client,
                model,
                [{"role": "user", "content": prompt}],
                temperature=0.75,
                max_tokens=4000,
            )
        except Exception as e:
            logger.error("批量生成LLM调用失败: %s", e)
            errors.append(str(e))
            break

        try:
            content = raw if isinstance(raw, str) else str(raw)
            json_start = content.find("[")
            json_end = content.rfind("]") + 1
            if json_start == -1 or json_end <= 0:
                errors.append("LLM返回格式异常")
                continue
            entries_data = json.loads(content[json_start:json_end])
        except (json.JSONDecodeError, KeyError, TypeError) as e:
            errors.append(f"JSON解析失败: {e}")
            continue

        if not isinstance(entries_data, list) or len(entries_data) == 0:
            errors.append("未返回数组或为空")
            continue

        entry_data = entries_data[0]
        title = (entry_data.get("title") or "").strip()
        if not title or title in existing_titles:
            errors.append(f"跳过无效或重复标题: {title!r}")
            continue

        new_entry = EncyclopediaEntryModel(
            encyclopedia_id=encyclopedia_id,
            title=title,
            entry_type=entry_type,
            summary=entry_data.get("summary", ""),
            content=entry_data.get("content", ""),
            confidence="inferred",
            meta_json=entry_data.get("meta_json", {}),
            tags="AI批量生成",
        )
        db.add(new_entry)
        db.commit()
        results.append({"title": title, "entry_type": entry_type})
        created += 1

    out: dict = {"created": created, "entries": results, "sequential": True}
    if errors:
        out["warnings"] = errors[:12]
    return out


def _batch_generate_legacy_single_call(
    *,
    db: Session,
    encyclopedia: WorldEncyclopediaModel,
    entry_type: str,
    count: int,
    context_hint: str,
    template_hint: str,
    theme_addon: str,
    query_override: str | None = None,
    digest_token_budget: int | None = 900,
) -> dict:
    """单次请求生成多条（旧逻辑）。"""
    encyclopedia_id = encyclopedia.id

    existing_titles = [
        t[0]
        for t in db.execute(
            select(EncyclopediaEntryModel.title).where(
                EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id,
                EncyclopediaEntryModel.entry_type == entry_type,
            ).limit(20)
        )
    ]

    theme = f"\n{theme_addon}\n" if theme_addon else ""
    wp = (getattr(encyclopedia, "world_prompt", None) or "").strip()
    wp_block = f"世界补充提示（作者填写，须遵守）:\n{wp}\n\n" if wp else ""
    reference_digest = build_entry_reference_digest(
        db,
        encyclopedia_id,
        entry_type,
        context_hint,
        query_override=query_override,
        digest_token_budget=digest_token_budget,
    )
    digest_block = f"{reference_digest}\n\n" if reference_digest.strip() else ""
    prompt = f"""你是世界观设计师。请为「{encyclopedia.name}」生成 {count} 个{entry_type}条目。

{template_hint}
{theme}{wp_block}{digest_block}已有条目（避免重复）: {json.dumps(existing_titles, ensure_ascii=False)}
用户提示: {context_hint or '无特殊要求'}
百科库描述: {encyclopedia.description or '无'}
体裁标签: {getattr(encyclopedia, 'genre_tags', '') or '未指定'}

输出格式: JSON 数组，每个元素格式如下：
[
  {{
    "title": "条目标题(≤100字)",
    "summary": "摘要(≤300字)",
    "content": "详细内容(≤2000字)",
    "meta_json": {{
      "alias": ["别名1", "别名2"],
      "background": "背景描述",
      "appearance": "外观描述",
      "personality": "性格描述",
      "abilities": ["能力1", "能力2"],
      "relationships": "关系描述",
      "source": "AI批量生成"
    }}
  }}
]

要求：
- 生成 {count} 个不同的、有创意的条目
- 标题不能与已有条目重复
- 每个条目的 meta_json 至少包含 5 个字段，且值须有实质内容（禁止全用「待补充」类占位）
- 每条 title、summary、content 必须非空且达到可入库使用的信息密度
- 内容应契合百科库的设定和体裁，禁止混入其它题材世界的等级或专有名词
"""

    try:
        client, model = build_public_text_client(db)
    except Exception as e:
        logger.error("批量生成（单次）：无法创建 LLM 客户端: %s", e)
        return {"created": 0, "entries": [], "error": str(e), "sequential": False}

    try:
        raw = safe_non_streaming_call(
            client,
            model,
            [{"role": "user", "content": prompt}],
            temperature=0.8,
            max_tokens=6000,
        )
    except Exception as e:
        logger.error(f"批量生成LLM调用失败: {e}")
        return {"created": 0, "entries": [], "error": str(e), "sequential": False}

    try:
        content = raw if isinstance(raw, str) else str(raw)
        json_start = content.find("[")
        json_end = content.rfind("]") + 1
        if json_start == -1 or json_end <= 0:
            return {"created": 0, "entries": [], "error": "LLM返回格式异常", "sequential": False}
        entries_data = json.loads(content[json_start:json_end])
    except (json.JSONDecodeError, KeyError) as e:
        return {"created": 0, "entries": [], "error": f"JSON解析失败: {e}", "sequential": False}

    created = 0
    results = []
    for entry_data in entries_data:
        title = entry_data.get("title", "").strip()
        if not title or title in existing_titles:
            continue

        new_entry = EncyclopediaEntryModel(
            encyclopedia_id=encyclopedia_id,
            title=title,
            entry_type=entry_type,
            summary=entry_data.get("summary", ""),
            content=entry_data.get("content", ""),
            confidence="inferred",
            meta_json=entry_data.get("meta_json", {}),
            tags="AI批量生成",
        )
        db.add(new_entry)
        results.append({"title": title, "entry_type": entry_type})
        created += 1

    db.commit()
    return {"created": created, "entries": results, "sequential": False}
