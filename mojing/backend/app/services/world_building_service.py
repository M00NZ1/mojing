from __future__ import annotations

import hashlib
import json
import random
from collections import defaultdict

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import CharacterModel, WorldLoreEntryModel, WorldTemplateModel
from ..schemas import (
    GeneratedNamePack,
    WorldBuildDebugRead,
    WorldGenerationRequest,
    WorldGenerationResponse,
    WorldImportChunkDebugRead,
    WorldImportRequest,
    WorldImportResponse,
    WorldLoreEntryCreate,
    WorldQualityIssueRead,
    WorldQualityReportRead,
    WorldTemplateCreate,
    WorldTemplateRead,
)
from .llm_client import build_async_client, resolve_text_config, resolve_text_model
from .llm_retry import safe_async_non_streaming_call
from .encyclopedia_template import (
    FIRST_RELEASE_LORE_BLUEPRINTS,
    FIRST_RELEASE_MODULES,
    build_public_template_prompt,
)


async def _world_completion(db, character, **kwargs):
    async with build_async_client(character, db) as client:
        return await safe_async_non_streaming_call(client, **kwargs)


NAME_CORPUS = {
    "修仙": {
        "person": ["凌霄", "青鸢", "玄衡", "白璃", "沈砚", "云岚", "裴昭", "苏瑶", "顾长歌", "谢临渊"],
        "place": ["太虚山", "青冥谷", "赤霄城", "落星海", "万剑峰", "归元宗", "寒月潭", "九霄殿"],
        "item": ["玄霜剑", "引雷符", "紫府丹", "镇魂铃", "青木鼎", "天璇玉简", "流火羽衣"],
    },
    "玄幻": {
        "person": ["奥兰", "维洛", "伊瑟拉", "赫兰德", "诺娅", "萨恩", "凯洛斯", "琳缇", "托雷恩"],
        "place": ["银松堡", "灰烬平原", "暮星港", "龙脊山脉", "圣辉城", "霜狼岭", "秘银湖"],
        "item": ["星痕长枪", "龙鳞护符", "月辉法典", "风语短弓", "秘银怀表", "幽焰灯盏"],
    },
    "剑与魔法": {
        "person": ["艾瑞安", "莉薇娅", "托兰", "塞西尔", "米蕾", "奥德里克", "芬恩", "卡萝"],
        "place": ["晨辉王都", "秘法高塔", "白鸦港", "荆棘荒原", "月影森林", "黑曜地下城"],
        "item": ["晨星法杖", "龙骨长剑", "秘银戒指", "元素卷轴", "誓约盾牌", "幽影披风"],
    },
    "都市": {
        "person": ["林川", "顾念", "周屿", "许清禾", "沈知微", "程野", "江妍", "陈牧", "叶岚"],
        "place": ["临江新区", "东栖科技园", "明德大学", "云河证券", "栖梧公寓", "南港创投中心"],
        "item": ["量化终端", "加密硬盘", "董事会纪要", "创业计划书", "股权协议", "限量腕表"],
    },
    "DND": {
        "person": ["Aelric", "Mira", "Thorne", "Selene", "Kael", "Brom", "Nyra", "Vaelis"],
        "place": ["Ravenford", "Silverkeep", "Ember Hollow", "Moonspire", "Gloomfen", "Ironwatch"],
        "item": ["Runeblade", "Wand of Ash", "Goblet of Dawn", "Shadow Sigil", "Traveler's Relic"],
    },
    "战锤": {
        "person": ["卡斯坦", "维罗恩", "赫兹卡", "马洛克", "阿斯翠娅", "德雷文", "诺克图斯"],
        "place": ["铁祷星港", "赤疫区", "圣骸舰坞", "灰烬神座", "无光巢都", "风暴祭坛"],
        "item": ["圣机油印玺", "等离子祷枪", "战团誓骨", "审判官印戒", "灰烬战旗"],
    },
}


def _public_template_prompt() -> str:
    return build_public_template_prompt()


def _anti_cheat_policy() -> str:
    return (
        "任何身份、财富、力量、关系、情报、物品和剧情奖励都必须通过世界内合理过程获得，不能一句话直接达成。"
        "用户手动输入的设定不可被 AI 改写或覆盖；AI 只能补齐空字段，并把补全部分标记为 AI补全/待确认。"
    )


def _has_online_text_config(character: CharacterModel, db: Session) -> bool:
    try:
        resolved = resolve_text_config(character, db)
    except Exception:
        return False
    return bool(resolved.api_key and resolved.base_url and resolved.model)


def _ensure_world_prompt_policy(prompt: str) -> str:
    text = (prompt or "").strip()
    if "用户手动输入" in text or "用户原始设定" in text:
        return text
    return f"{_public_template_prompt()}\n\n{text}".strip()


class MarkovNameGenerator:
    """简化版 Markov 名称生成器，用于本地免费生成名称。"""

    def __init__(self, samples: list[str], order: int = 2):
        self.order = max(order, 1)
        self.chain: dict[str, list[str]] = defaultdict(list)
        self.starts: list[str] = []
        for sample in samples:
            word = f"^{sample.strip()}$"
            if len(word) <= self.order:
                continue
            self.starts.append(word[: self.order])
            for index in range(len(word) - self.order):
                key = word[index : index + self.order]
                self.chain[key].append(word[index + self.order])

    def generate(self, min_length: int = 2, max_length: int = 8) -> str:
        if not self.starts:
            return ""
        for _ in range(40):
            current = random.choice(self.starts)
            result = current.replace("^", "")
            while True:
                next_chars = self.chain.get(current)
                if not next_chars:
                    break
                next_char = random.choice(next_chars)
                if next_char == "$":
                    if min_length <= len(result) <= max_length:
                        return result
                    break
                result += next_char
                current = current[1:] + next_char
                if len(result) > max_length:
                    break
        return random.choice(self.starts).replace("^", "")


def generate_name_pack(world_type: str, person_count: int, place_count: int, item_count: int) -> GeneratedNamePack:
    corpus = _pick_corpus(world_type)
    person_generator = MarkovNameGenerator(corpus["person"])
    place_generator = MarkovNameGenerator(corpus["place"])
    item_generator = MarkovNameGenerator(corpus["item"])
    return GeneratedNamePack(
        person_names=_unique_generate(person_generator, person_count, corpus["person"]),
        place_names=_unique_generate(place_generator, place_count, corpus["place"]),
        item_names=_unique_generate(item_generator, item_count, corpus["item"]),
    )


async def generate_world_package(db: Session, payload: WorldGenerationRequest) -> WorldGenerationResponse:
    name_pack = generate_name_pack(payload.world_type, payload.person_name_count, payload.place_name_count, payload.item_name_count)
    template, strategy = await _generate_template_with_fallback(db, payload, name_pack)
    lore_entries, lore_strategy = await _generate_lore_entries_with_fallback(db, payload, template, name_pack)
    quality_report = review_world_package(template, lore_entries)

    saved_template = None
    if payload.auto_save:
        saved_template = _save_world_package(db, template, lore_entries)

    return WorldGenerationResponse(
        template=template,
        lore_entries=lore_entries,
        names=name_pack,
        quality_report=quality_report,
        debug=WorldBuildDebugRead(
            strategy=strategy,
            detected_category=template.category,
            chunk_count=1,
            merge_notes=[lore_strategy],
            chunk_debug=[],
        ),
        saved_template=saved_template,
    )


async def import_world_package(db: Session, payload: WorldImportRequest) -> WorldImportResponse:
    template, lore_entries, names, debug = await _extract_world_from_source(db, payload)
    quality_report = review_world_package(template, lore_entries)
    saved_template = None
    if payload.auto_save:
        saved_template = _save_world_package(db, template, lore_entries)
    return WorldImportResponse(
        template=template,
        lore_entries=lore_entries,
        names=names,
        quality_report=quality_report,
        debug=debug,
        saved_template=saved_template,
    )


async def _generate_template_with_fallback(
    db: Session,
    payload: WorldGenerationRequest,
    names: GeneratedNamePack,
) -> tuple[WorldTemplateCreate, str]:
    character = db.get(CharacterModel, payload.character_id) if payload.character_id else None
    if character and _has_online_text_config(character, db):
        try:
            return await _generate_template_with_llm(db, character, payload, names), "在线模型生成世界骨架"
        except Exception:
            pass
    world_type = payload.world_type.strip() or "通用世界"
    label = payload.label.strip() or f"{world_type}世界"
    template_id = payload.template_id.strip() or _slugify_template_id(label)
    summary = f"{world_type}题材世界，围绕“{payload.core_theme or '成长与冲突'}”展开，可长期推进剧情。"
    prompt = (
        f"{_public_template_prompt()}\n\n"
        f"这是一个{world_type}题材的长期互动世界。\n"
        f"核心主题：{payload.core_theme or '成长、冲突与抉择'}。\n"
        f"整体基调：{payload.tone}。\n"
        f"建议重点人物名：{', '.join(names.person_names[:4])}。\n"
        f"建议关键地点：{', '.join(names.place_names[:4])}。\n"
        f"建议关键物品：{', '.join(names.item_names[:4])}。\n"
        f"额外要求：{payload.extra_requirements or '保持逻辑自洽，适合多人物推进。'}\n"
        "AI补全策略：没有被用户明确提供的字段，只能作为待确认草案写入，不能改写用户原始设定。"
    )
    return (
        WorldTemplateCreate(
            template_id=template_id,
            label=label,
            category=world_type,
            summary=summary,
            gameplay_mode="探索成长" if world_type in {"修仙", "玄幻", "剑与魔法", "DND", "战锤"} else "资源经营",
            world_prompt=prompt,
            suggested_choices=_default_choices(world_type, names),
            anti_cheat_prompt=_anti_cheat_policy(),
        ),
        "本地骨架生成",
    )


async def _extract_world_from_source(
    db: Session,
    payload: WorldImportRequest,
) -> tuple[WorldTemplateCreate, list[WorldLoreEntryCreate], GeneratedNamePack, WorldBuildDebugRead]:
    names = generate_name_pack(payload.category_hint or "玄幻", 8, 8, 8)
    character = db.get(CharacterModel, payload.character_id) if payload.character_id else None
    if character and _has_online_text_config(character, db):
        try:
            return await _extract_world_with_llm(db, character, payload)
        except Exception:
            pass

    label = payload.label.strip() or payload.source_filename.rsplit(".", 1)[0] or "导入世界"
    template_id = payload.template_id.strip() or _slugify_template_id(label)
    detected_category = payload.category_hint or _detect_world_category(payload.source_text)
    template = WorldTemplateCreate(
        template_id=template_id,
        label=label,
        category=detected_category,
        summary=_summarize_source_text(payload.source_text),
        gameplay_mode=_default_gameplay_mode(detected_category),
        world_prompt=_compose_import_world_prompt(payload.source_text),
        suggested_choices=_extract_bullet_candidates(payload.source_text, 5) or _default_choices(detected_category, names),
        anti_cheat_prompt=_anti_cheat_policy(),
    )
    lore_entries = _ensure_first_release_lore_entries(
        _extract_lore_entries_heuristic(payload.source_text, detected_category),
        payload=WorldGenerationRequest(world_type=detected_category, core_theme=_summarize_source_text(payload.source_text), lore_entry_count=len(FIRST_RELEASE_LORE_BLUEPRINTS)),
        template=template,
        names=names,
    )
    debug = WorldBuildDebugRead(
        strategy="本地启发式抽取",
        detected_category=detected_category,
        chunk_count=len(_split_source_text(payload.source_text, 2600)),
        merge_notes=["未使用在线模型，按标题、段落和关键词进行启发式拆解。"],
        chunk_debug=[],
    )
    return template, lore_entries, names, debug


async def _extract_world_with_llm(
    db: Session,
    character: CharacterModel,
    payload: WorldImportRequest,
) -> tuple[WorldTemplateCreate, list[WorldLoreEntryCreate], GeneratedNamePack, WorldBuildDebugRead]:
    source_text = (payload.source_text or "").strip()
    label = payload.label.strip() or payload.source_filename.rsplit(".", 1)[0] or "导入世界"
    template_id = payload.template_id.strip() or _slugify_template_id(label)
    detected_category = payload.category_hint or _detect_world_category(source_text)
    chunks = _split_source_text(source_text, 2600)
    chunk_results: list[dict] = []
    chunk_debug: list[WorldImportChunkDebugRead] = []

    for index, chunk in enumerate(chunks, start=1):
        chunk_result = await _extract_world_chunk_with_llm(db, character, payload, chunk, index, len(chunks), detected_category)
        chunk_results.append(chunk_result)
        chunk_debug.append(
            WorldImportChunkDebugRead(
                chunk_index=index,
                char_length=len(chunk),
                summary=str(chunk_result.get("summary") or "")[:220],
                extracted_titles=[str(item.get("title") or "") for item in (chunk_result.get("lore_entries") or [])[:8] if isinstance(item, dict)],
                extracted_types=_collect_entry_types(chunk_result.get("lore_entries") or []),
                extracted_names=GeneratedNamePack(
                    person_names=[str(item) for item in ((chunk_result.get("names") or {}).get("person_names") or [])][:8],
                    place_names=[str(item) for item in ((chunk_result.get("names") or {}).get("place_names") or [])][:8],
                    item_names=[str(item) for item in ((chunk_result.get("names") or {}).get("item_names") or [])][:8],
                ),
                strategy="在线模型分块抽取",
            )
        )

    merged = await _merge_world_chunks_with_llm(db, character, payload, detected_category, chunk_results)
    template_data = merged.get("template") or {}
    names_data = merged.get("names") or {}
    names = _merge_name_pack_from_sources(
        detected_category,
        [names_data, *[(item.get("names") or {}) for item in chunk_results]],
    )
    template = WorldTemplateCreate(
        template_id=str(template_data.get("template_id") or template_id),
        label=str(template_data.get("label") or label),
        category=str(template_data.get("category") or detected_category),
        summary=str(template_data.get("summary") or _summarize_source_text(source_text)),
        gameplay_mode=str(template_data.get("gameplay_mode") or _default_gameplay_mode(detected_category)),
        world_prompt=_ensure_world_prompt_policy(str(template_data.get("world_prompt") or _compose_import_world_prompt(source_text))),
        suggested_choices=_normalize_string_list(template_data.get("suggested_choices") or []) or _extract_bullet_candidates(source_text, 5) or _default_choices(detected_category, names),
        anti_cheat_prompt=str(template_data.get("anti_cheat_prompt") or _anti_cheat_policy()),
    )
    lore_entries = _coerce_lore_entries(merged.get("lore_entries") or [])
    if len(lore_entries) < 4:
        lore_entries = _merge_lore_entries(lore_entries, _extract_lore_entries_heuristic(source_text, detected_category))
    lore_entries = _ensure_first_release_lore_entries(
        lore_entries,
        payload=WorldGenerationRequest(world_type=detected_category, core_theme=_summarize_source_text(source_text), lore_entry_count=len(FIRST_RELEASE_LORE_BLUEPRINTS)),
        template=template,
        names=names,
    )
    debug = WorldBuildDebugRead(
        strategy="在线模型分块抽取 + 合并",
        detected_category=detected_category,
        chunk_count=len(chunks),
        merge_notes=_normalize_string_list(merged.get("merge_notes") or []) or ["已先按分块抽取，再合并为最终世界模板，降低长文本一次性导入时的信息遗漏。"],
        chunk_debug=chunk_debug,
    )
    return template, lore_entries, names, debug


async def _generate_lore_entries_with_fallback(
    db: Session,
    payload: WorldGenerationRequest,
    template: WorldTemplateCreate,
    names: GeneratedNamePack,
) -> tuple[list[WorldLoreEntryCreate], str]:
    character = db.get(CharacterModel, payload.character_id) if payload.character_id else None
    if character and _has_online_text_config(character, db):
        try:
            entries = await _generate_lore_entries_with_llm(db, character, payload, template, names)
            return _ensure_first_release_lore_entries(entries, payload=payload, template=template, names=names), "在线模型生成 Lore 条目"
        except Exception:
            pass
    entries = _build_first_release_lore_entries(payload, template, names)
    return entries[: max(payload.lore_entry_count, len(FIRST_RELEASE_LORE_BLUEPRINTS))], "本地规则生成 Lore 条目"


def _compose_import_world_prompt(source_text: str) -> str:
    original = (source_text or "").strip()[:8000]
    return (
        f"{_public_template_prompt()}\n\n"
        "【用户原始设定】\n"
        f"{original}\n\n"
        "【导入规则】上方原始设定必须原样保留其事实含义；AI 只能抽取、归类和补空字段，补全内容必须标记为 AI补全/待确认。"
    ).strip()


def _build_first_release_lore_entries(
    payload: WorldGenerationRequest,
    template: WorldTemplateCreate,
    names: GeneratedNamePack,
) -> list[WorldLoreEntryCreate]:
    world_type = payload.world_type.strip() or template.category or "通用世界"
    theme = payload.core_theme.strip() or "成长与冲突"
    people = "、".join(names.person_names[:5]) or "待命名人物"
    places = "、".join(names.place_names[:5]) or "待命名地点"
    items = "、".join(names.item_names[:5]) or "待命名物品"
    content_by_title = {
        "世界观总览": f"这个{world_type}世界围绕“{theme}”推进。用户已输入内容为最高优先级；其他缺失信息均为 AI补全/待确认。",
        "人物": f"AI补全/待确认：首批人物候选包括 {people}。每个人物必须维护身份、阵营、关系、成长线和当前状态。",
        "地点": f"AI补全/待确认：首批地点候选包括 {places}。地点条目必须维护控制权、资源、路线、风险和地图层级。",
        "势力": "AI补全/待确认：至少建立主要盟友、敌对者、中立组织和隐藏势力，并记录领袖、总部、目标、成员和战争记录。",
        "事件": f"AI补全/待确认：围绕“{theme}”建立起因、过程、结果、影响和世界状态变更记录，禁止跳过因果链。",
        "物品": f"AI补全/待确认：首批物品候选包括 {items}。物品必须记录来源、持有者、效果、限制、代价和获得路径。",
        "技能/法术": "AI补全/待确认：能力必须记录学习条件、消耗规则、副作用、阶段、使用者和克制关系。",
        "职业/等级": "AI补全/待确认：职业成长必须记录等级/境界、晋升条件、转职路线、限制、技能池和装备限制。",
        "概念术语": "AI补全/待确认：所有专有名词必须写定义、适用范围、例子、反例和关联规则，避免模型用相似作品常识替代本世界设定。",
        "时间线": "AI补全/待确认：时间线必须记录历法、时代、事件顺序、分支、冲突版本和不确定日期。",
        "关系图谱": "AI补全/待确认：关系必须记录方向、证据、状态、起止事件和可信来源，不能凭语义相近自动建立强关系。",
        "AI生成": "AI补全/待确认：AI 可以生成条目、补全字段、检查冲突和总结，但不能改写用户手动输入，不能把待确认内容当作官方事实。",
        "字段模板": "所有字段默认允许用户留空；用户可新增 custom_fields。AI 只补空字段，补全结果必须带状态和来源说明。",
        "标签筛选": "标签用于检索和筛选，首版至少支持题材、类型、阵营、地点、状态、来源可信度和校验状态。",
        "全局搜索": "全局搜索必须覆盖标题、摘要、正文、标签、来源字段和关键 meta 字段，作为运行时召回百科条目的入口。",
    }
    entries: list[WorldLoreEntryCreate] = []
    for index, blueprint in enumerate(FIRST_RELEASE_LORE_BLUEPRINTS):
        title = blueprint["title"]
        entries.append(
            WorldLoreEntryCreate(
                title=title,
                entry_type=blueprint["entry_type"],
                keywords_json=_normalize_string_list([world_type, *blueprint.get("keywords", [])]),
                content=content_by_title.get(title, f"AI补全/待确认：{title} 模块需要按公用模板补齐。"),
                sort_order=index,
                is_core=index < 10,
            )
        )
    return entries


def _ensure_first_release_lore_entries(
    entries: list[WorldLoreEntryCreate],
    *,
    payload: WorldGenerationRequest,
    template: WorldTemplateCreate,
    names: GeneratedNamePack,
) -> list[WorldLoreEntryCreate]:
    merged = _deduplicate_lore_entries(entries)
    existing_titles = {item.title for item in merged}
    for item in _build_first_release_lore_entries(payload, template, names):
        if item.title in existing_titles:
            continue
        merged.append(item)
        existing_titles.add(item.title)
    return _deduplicate_lore_entries(merged)


async def _extract_world_chunk_with_llm(
    db: Session,
    character: CharacterModel,
    payload: WorldImportRequest,
    chunk_text: str,
    chunk_index: int,
    total_chunks: int,
    detected_category: str,
) -> dict:
    prompt = f"""
你是世界设定分块抽取器。下面给你的是长世界设定中的一个分块，请只提取这个分块内部已经明确出现的稳定信息。

请输出严格 JSON：
{{
  "summary": "",
  "possible_choices": [],
  "anti_cheat_rules": [],
  "lore_entries": [
    {{
      "title": "",
      "entry_type": "",
      "keywords_json": [],
      "content": "",
      "sort_order": 0,
      "is_core": false
    }}
  ],
  "names": {{
    "person_names": [],
    "place_names": [],
    "item_names": []
  }}
}}

【文件名】
{payload.source_filename}

【分块】
{chunk_index}/{total_chunks}

【类别提示】
{payload.category_hint or detected_category}

【要求】
1. 只提取当前分块中已经出现或高度确定的信息，不要虚构整篇没有写明的设定。
2. `summary` 用 2 到 4 句概括当前分块。
3. `lore_entries` 优先拆成人物、地点、势力、规则、物品、剧情这几类，并尽量映射到公用世界观模板。
4. 关键词必须有利于后续命中检索。
5. 命名表优先从原文抽取，没有就留空。
6. 用户原文是最高优先级事实，禁止改写；缺失内容只能留空或标记为待确认。
7. 只能输出 JSON。

【公用模板约束】
{_public_template_prompt()}

【原始文本】
{chunk_text}
""".strip()
    content = await _world_completion(db, character, model=resolve_text_model(character, db), messages=[{"role": "user", "content": prompt}], temperature=0.2, max_tokens=min(max(character.max_tokens, 1400), 4096))
    return _safe_load_json(content, {})


async def _merge_world_chunks_with_llm(
    db: Session,
    character: CharacterModel,
    payload: WorldImportRequest,
    detected_category: str,
    chunk_results: list[dict],
) -> dict:
    chunk_summaries = []
    for index, item in enumerate(chunk_results, start=1):
        names = item.get("names") or {}
        chunk_summaries.append(
            {
                "chunk_index": index,
                "summary": item.get("summary") or "",
                "possible_choices": item.get("possible_choices") or [],
                "anti_cheat_rules": item.get("anti_cheat_rules") or [],
                "lore_entries": item.get("lore_entries") or [],
                "names": {
                    "person_names": (names.get("person_names") or [])[:8],
                    "place_names": (names.get("place_names") or [])[:8],
                    "item_names": (names.get("item_names") or [])[:8],
                },
            }
        )

    prompt = f"""
你是世界设定总编。下面是长世界设定按分块抽取后的中间结果。请把这些分块结果合并成一个可以直接用于互动剧情系统的最终世界模板。

请输出严格 JSON：
{{
  "template": {{
    "template_id": "",
    "label": "",
    "category": "",
    "summary": "",
    "gameplay_mode": "",
    "world_prompt": "",
    "suggested_choices": [],
    "anti_cheat_prompt": ""
  }},
  "lore_entries": [
    {{
      "title": "",
      "entry_type": "",
      "keywords_json": [],
      "content": "",
      "sort_order": 0,
      "is_core": true
    }}
  ],
  "names": {{
    "person_names": [],
    "place_names": [],
    "item_names": []
  }},
  "merge_notes": []
}}

【文件名】
{payload.source_filename}

【类别提示】
{payload.category_hint or detected_category}

【输出要求】
1. `world_prompt` 只保留运行时最重要的世界规则、阵营、资源、地点、冲突与当前局势，不要抄整篇。
2. `summary` 要能让用户一眼看懂这个世界怎么玩。
3. `suggested_choices` 给 3 到 5 个可长期复用的行动方向。
4. `anti_cheat_prompt` 要明确限制“一句话直接获得身份、资源、力量、情报”的越权行为。
5. `lore_entries` 至少覆盖首版必上模块：{"、".join(FIRST_RELEASE_MODULES)}。原文没有的信息写成 AI补全/待确认。
6. `merge_notes` 用来说明合并时发现的重点或缺口。
7. 用户原文是最高优先级事实，禁止改写；只能抽取、归类、补空字段。
8. 只能输出 JSON。

【公用模板约束】
{_public_template_prompt()}

【分块抽取结果】
{json.dumps(chunk_summaries, ensure_ascii=False, indent=2)}
""".strip()
    content = await _world_completion(db, character, model=resolve_text_model(character, db), messages=[{"role": "user", "content": prompt}], temperature=0.3, max_tokens=min(max(character.max_tokens, 2200), 4096))
    return _safe_load_json(content or "", {})


async def _generate_template_with_llm(db: Session, character: CharacterModel, payload: WorldGenerationRequest, names: GeneratedNamePack) -> WorldTemplateCreate:
    prompt = f"""
你是世界观策划师。请输出严格 JSON：
{{
  "template_id": "",
  "label": "",
  "category": "",
  "summary": "",
  "gameplay_mode": "",
  "world_prompt": "",
  "suggested_choices": [],
  "anti_cheat_prompt": ""
}}

【目标世界类型】
{payload.world_type}

【核心主题】
{payload.core_theme}

【整体基调】
{payload.tone}

【额外要求】
{payload.extra_requirements}

【可参考命名】
人名：{", ".join(names.person_names)}
地名：{", ".join(names.place_names)}
物品名：{", ".join(names.item_names)}

【公用模板约束】
{_public_template_prompt()}

【要求】
1. 世界适合长期互动叙事。
2. `world_prompt` 写成可直接给模型使用的世界总设定，并按公用模板组织。
3. `suggested_choices` 给 3 到 5 个通用行动。
4. 用户输入的目标类型、主题、基调和额外要求不得被改写；缺失字段可以补全但必须标为待确认。
5. 只能输出 JSON。
""".strip()
    content = await _world_completion(db, character, model=resolve_text_model(character, db), messages=[{"role": "user", "content": prompt}], temperature=0.7, max_tokens=min(max(character.max_tokens, 1400), 4096))
    raw = (content or "").strip()
    data = _safe_load_json(raw, {})
    return WorldTemplateCreate(
        template_id=data.get("template_id") or payload.template_id.strip() or _slugify_template_id(payload.world_type),
        label=data.get("label") or payload.label.strip() or f"{payload.world_type}世界",
        category=data.get("category") or payload.world_type,
        summary=data.get("summary") or f"{payload.world_type}题材互动世界",
        gameplay_mode=data.get("gameplay_mode") or "自由剧情",
        world_prompt=_ensure_world_prompt_policy(data.get("world_prompt") or ""),
        suggested_choices=list(data.get("suggested_choices") or []),
        anti_cheat_prompt=data.get("anti_cheat_prompt") or _anti_cheat_policy(),
    )


async def _generate_lore_entries_with_llm(
    db: Session,
    character: CharacterModel,
    payload: WorldGenerationRequest,
    template: WorldTemplateCreate,
    names: GeneratedNamePack,
) -> list[WorldLoreEntryCreate]:
    prompt = f"""
你是世界 Lorebook 设计师。请输出严格 JSON 数组，每项结构如下：
{{
  "title": "",
  "entry_type": "",
  "keywords_json": [],
  "content": "",
  "sort_order": 0,
  "is_core": false
}}

【世界类型】
{payload.world_type}

【世界设定】
{template.world_prompt}

【可参考命名】
人名：{", ".join(names.person_names)}
地名：{", ".join(names.place_names)}
物品名：{", ".join(names.item_names)}

【公用模板约束】
{_public_template_prompt()}

【要求】
1. 生成至少 {max(payload.lore_entry_count, len(FIRST_RELEASE_LORE_BLUEPRINTS))} 条。
2. 必须覆盖首版必上模块：{"、".join(FIRST_RELEASE_MODULES)}。
3. 关键词要便于后续触发检索。
4. 用户已经输入的内容不得改写；缺失信息写成 AI补全/待确认。
5. 只能输出 JSON 数组。
""".strip()
    content = await _world_completion(db, character, model=resolve_text_model(character, db), messages=[{"role": "user", "content": prompt}], temperature=0.6, max_tokens=min(max(character.max_tokens, 1600), 4096))
    raw = (content or "").strip()
    items = _safe_load_json(raw, [])
    if not isinstance(items, list):
        return []
    return _coerce_lore_entries(items)


def _save_world_package(
    db: Session,
    template: WorldTemplateCreate,
    lore_entries: list[WorldLoreEntryCreate],
) -> WorldTemplateRead:
    existing = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template.template_id))
    if existing is not None:
        raise ValueError("自动保存失败：模板标识已存在，请修改模板 ID 后重试。")
    row = WorldTemplateModel(
        template_id=template.template_id,
        label=template.label,
        category=template.category,
        summary=template.summary,
        gameplay_mode=template.gameplay_mode,
        world_prompt=template.world_prompt,
        suggested_choices_json=template.suggested_choices,
        anti_cheat_prompt=template.anti_cheat_prompt,
        is_builtin=False,
    )
    db.add(row)
    db.flush()
    for item in lore_entries:
        db.add(
            WorldLoreEntryModel(
                world_template_id=row.id,
                title=item.title,
                entry_type=item.entry_type,
                keywords_json=item.keywords_json,
                content=item.content,
                sort_order=item.sort_order,
                is_core=item.is_core,
            )
        )
    db.commit()
    db.refresh(row)
    return WorldTemplateRead(
        id=row.id,
        template_id=row.template_id,
        label=row.label,
        category=row.category,
        summary=row.summary,
        gameplay_mode=row.gameplay_mode,
        world_prompt=row.world_prompt,
        suggested_choices=list(row.suggested_choices_json or []),
        anti_cheat_prompt=row.anti_cheat_prompt,
        is_builtin=row.is_builtin,
    )


def review_world_package(template: WorldTemplateCreate, lore_entries: list[WorldLoreEntryCreate]) -> WorldQualityReportRead:
    strengths: list[str] = []
    risks: list[str] = []
    issues: list[WorldQualityIssueRead] = []
    score = 100

    if len(template.summary.strip()) >= 20:
        strengths.append("世界摘要长度基本够用。")
    else:
        score -= 10
        issues.append(WorldQualityIssueRead(level="warn", code="summary_short", message="世界摘要过短，难以快速理解世界卖点。", suggestion="补充世界的核心冲突、资源和氛围。"))

    if len(template.world_prompt.strip()) >= 180:
        strengths.append("运行时世界设定信息量基本足够。")
    else:
        score -= 15
        issues.append(WorldQualityIssueRead(level="error", code="world_prompt_thin", message="世界主设定过薄，模型后续会大量脑补。", suggestion="补充规则、阵营、资源、地点和当前局势。"))

    lore_types = {item.entry_type for item in lore_entries}
    if len(lore_entries) >= len(FIRST_RELEASE_LORE_BLUEPRINTS):
        strengths.append("Lorebook 已覆盖首版公用模板入口。")
    else:
        score -= 12
        issues.append(WorldQualityIssueRead(level="warn", code="lore_count_low", message="Lore 条目偏少，可玩性和检索命中会受影响。", suggestion="至少补齐首版 15 个公用模板入口。"))

    required_types = {item["entry_type"] for item in FIRST_RELEASE_LORE_BLUEPRINTS}
    missing_types = sorted(required_types - lore_types)
    if missing_types:
        score -= 10
        issues.append(WorldQualityIssueRead(level="warn", code="public_template_missing", message=f"Lore 条目缺少公用模板入口：{'、'.join(missing_types)}。", suggestion="按首版模块补齐世界观总览、人物、地点、势力、事件、物品、技能、职业、术语、时间线、关系图谱和检索入口。"))
    else:
        strengths.append("Lore 条目类型覆盖了公用模板关键知识层。")

    if not template.suggested_choices:
        score -= 8
        risks.append("缺少默认行动建议，旁白更容易给出空泛选项。")
        issues.append(WorldQualityIssueRead(level="warn", code="choices_missing", message="默认建议行动为空。", suggestion="给出 3 到 5 个能长期复用的行动方向。"))
    else:
        strengths.append("已具备默认行动建议。")

    if not template.anti_cheat_prompt.strip():
        score -= 8
        risks.append("缺少防作弊规则，玩家越权输入更容易污染剧情。")
        issues.append(WorldQualityIssueRead(level="warn", code="anti_cheat_missing", message="防作弊规则为空。", suggestion="明确声明身份、力量、财富、情报不能一句话直接获得。"))

    if _looks_generic(template.world_prompt):
        score -= 12
        risks.append("世界设定中存在较多空泛描述。")
        issues.append(WorldQualityIssueRead(level="warn", code="generic_language", message="世界设定较空泛，缺少具体资源、地点或冲突锚点。", suggestion="增加具体势力、地点、资源、规则与代价。"))

    if "用户手动输入" not in template.world_prompt and "用户原始设定" not in template.world_prompt:
        score -= 8
        risks.append("缺少用户输入保护策略，AI 后续可能覆盖玩家手填设定。")
        issues.append(WorldQualityIssueRead(level="warn", code="preserve_user_input_missing", message="世界模板没有声明用户输入不可覆盖。", suggestion="在世界设定和防作弊规则中加入“用户输入优先，AI 只补空字段”。"))

    verdict = "可直接试玩" if score >= 85 else "可用但建议补强" if score >= 70 else "不建议直接投入主流程"
    return WorldQualityReportRead(score=max(min(score, 100), 0), verdict=verdict, strengths=strengths, risks=risks, issues=issues)


def _pick_corpus(world_type: str) -> dict[str, list[str]]:
    lowered = (world_type or "").lower()
    if "修仙" in world_type:
        return NAME_CORPUS["修仙"]
    if "玄幻" in world_type:
        return NAME_CORPUS["玄幻"]
    if "剑与魔法" in world_type or "fantasy" in lowered:
        return NAME_CORPUS["剑与魔法"]
    if "都市" in world_type:
        return NAME_CORPUS["都市"]
    if "dnd" in lowered or "龙与地下城" in world_type:
        return NAME_CORPUS["DND"]
    if "战锤" in world_type or "warhammer" in lowered:
        return NAME_CORPUS["战锤"]
    return NAME_CORPUS["玄幻"]


def _unique_generate(generator: MarkovNameGenerator, count: int, fallback: list[str]) -> list[str]:
    result: list[str] = []
    tried = 0
    while len(result) < max(count, 1) and tried < max(count, 1) * 20:
        candidate = generator.generate()
        tried += 1
        if not candidate or candidate in result:
            continue
        result.append(candidate)
    for item in fallback:
        if len(result) >= max(count, 1):
            break
        if item not in result:
            result.append(item)
    return result[: max(count, 1)]


def _default_choices(world_type: str, names: GeneratedNamePack) -> list[str]:
    if "都市" in world_type:
        return ["调查一条新线索", "拜访关键人物", "推进手头工作或项目", "复盘当前资源和关系"]
    if "修仙" in world_type:
        return ["进入新区域探索", "寻找关键人物或前辈", "整理当前资源并修炼", "围绕关键物品展开行动"]
    return [f"前往{name}" for name in names.place_names[:2]] + ["调查关键人物动向", "围绕关键物品展开行动"]


def _detect_world_category(source_text: str) -> str:
    text = source_text or ""
    if any(token in text for token in ["地下城", "法术位", "圣武士", "巨龙", "dnd", "龙与地下城", "srd"]):
        return "DND"
    if any(token in text for token in ["战锤", "帝皇", "星际战士", "阿斯塔特", "审判庭"]):
        return "战锤"
    if any(token in text for token in ["灵根", "宗门", "秘境", "飞升", "灵石"]):
        return "修仙"
    if any(token in text for token in ["公司", "证券", "董事会", "项目", "城市"]):
        return "都市"
    return "玄幻"


def _default_gameplay_mode(category: str) -> str:
    if category in {"DND", "修仙", "玄幻", "战锤", "剑与魔法"}:
        return "探索成长"
    if category == "都市":
        return "资源经营"
    return "自由剧情"


def _summarize_source_text(source_text: str, max_length: int = 120) -> str:
    cleaned = " ".join((source_text or "").split())
    return cleaned[:max_length] if cleaned else "导入的世界设定"


def _extract_bullet_candidates(source_text: str, limit: int) -> list[str]:
    lines = [line.strip("-* \t") for line in (source_text or "").splitlines()]
    return [line for line in lines if 4 <= len(line) <= 30][:limit]


def _extract_lore_entries_heuristic(source_text: str, category: str) -> list[WorldLoreEntryCreate]:
    sections: list[tuple[str, str]] = []
    current_title = "世界总览"
    buffer: list[str] = []
    for raw_line in (source_text or "").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("#") or line.endswith("：") or line.endswith(":"):
            if buffer:
                sections.append((current_title, "\n".join(buffer)))
                buffer = []
            current_title = line.strip("#：: ").strip() or "未命名条目"
            continue
        buffer.append(line)
    if buffer:
        sections.append((current_title, "\n".join(buffer)))

    result: list[WorldLoreEntryCreate] = []
    for index, (title, content) in enumerate(sections[:10]):
        result.append(
            WorldLoreEntryCreate(
                title=title,
                entry_type=_guess_lore_entry_type(title, content),
                keywords_json=[item for item in [title, category] if item],
                content=content[:1200],
                sort_order=index,
                is_core=index < 4,
            )
        )
    if not result:
        result.append(
            WorldLoreEntryCreate(
                title="世界总览",
                entry_type="设定",
                keywords_json=[category, "世界总览"],
                content=(source_text or "").strip()[:1600],
                sort_order=0,
                is_core=True,
            )
        )
    return result


def _split_source_text(text: str, chunk_size: int) -> list[str]:
    normalized = (text or "").strip()
    if len(normalized) <= chunk_size:
        return [normalized] if normalized else [""]
    paragraphs = [item.strip() for item in normalized.split("\n\n") if item.strip()]
    chunks: list[str] = []
    current = ""
    for paragraph in paragraphs:
        next_value = f"{current}\n\n{paragraph}".strip() if current else paragraph
        if len(next_value) <= chunk_size:
            current = next_value
            continue
        if current:
            chunks.append(current)
        if len(paragraph) <= chunk_size:
            current = paragraph
            continue
        for start in range(0, len(paragraph), chunk_size):
            piece = paragraph[start : start + chunk_size].strip()
            if piece:
                chunks.append(piece)
        current = ""
    if current:
        chunks.append(current)
    return chunks or [normalized]


def _merge_name_pack_from_sources(world_type: str, sources: list[dict]) -> GeneratedNamePack:
    fallback = generate_name_pack(world_type, 8, 8, 8)
    person_names = _merge_unique_strings([source.get("person_names") or [] for source in sources], fallback.person_names, 12)
    place_names = _merge_unique_strings([source.get("place_names") or [] for source in sources], fallback.place_names, 12)
    item_names = _merge_unique_strings([source.get("item_names") or [] for source in sources], fallback.item_names, 12)
    return GeneratedNamePack(person_names=person_names, place_names=place_names, item_names=item_names)


def _merge_unique_strings(candidate_groups: list[list], fallback: list[str], limit: int) -> list[str]:
    result: list[str] = []
    for group in candidate_groups:
        for item in group:
            text = str(item).strip()
            if not text or text in result:
                continue
            result.append(text)
            if len(result) >= limit:
                return result[:limit]
    for item in fallback:
        if item not in result:
            result.append(item)
        if len(result) >= limit:
            break
    return result[:limit]


def _collect_entry_types(items: list[dict]) -> list[str]:
    result: list[str] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        entry_type = str(item.get("entry_type") or "").strip()
        if entry_type and entry_type not in result:
            result.append(entry_type)
    return result


def _coerce_lore_entries(items: list) -> list[WorldLoreEntryCreate]:
    result: list[WorldLoreEntryCreate] = []
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            continue
        title = str(item.get("title") or f"条目 {index + 1}").strip()
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        result.append(
            WorldLoreEntryCreate(
                title=title,
                entry_type=str(item.get("entry_type") or _guess_lore_entry_type(title, content)).strip() or "设定",
                keywords_json=_normalize_string_list(item.get("keywords_json") or []),
                content=content[:1200],
                sort_order=int(item.get("sort_order") or index),
                is_core=bool(item.get("is_core", False)),
            )
        )
    return _deduplicate_lore_entries(result)


def _merge_lore_entries(primary: list[WorldLoreEntryCreate], fallback: list[WorldLoreEntryCreate]) -> list[WorldLoreEntryCreate]:
    merged = list(primary)
    existing_titles = {item.title for item in merged}
    for item in fallback:
        if item.title in existing_titles:
            continue
        merged.append(item)
        existing_titles.add(item.title)
    return _deduplicate_lore_entries(merged)


def _deduplicate_lore_entries(items: list[WorldLoreEntryCreate]) -> list[WorldLoreEntryCreate]:
    seen: set[str] = set()
    result: list[WorldLoreEntryCreate] = []
    for index, item in enumerate(items):
        key = f"{item.entry_type}:{item.title}".strip().lower()
        if key in seen:
            continue
        seen.add(key)
        result.append(WorldLoreEntryCreate(title=item.title, entry_type=item.entry_type, keywords_json=_normalize_string_list(item.keywords_json), content=item.content, sort_order=index, is_core=item.is_core))
    return result


def _guess_lore_entry_type(title: str, content: str) -> str:
    joined = f"{title}\n{content}"
    if any(token in joined for token in ["人物", "角色", "英雄", "npc", "主角", "反派"]):
        return "人物"
    if any(token in joined for token in ["地点", "区域", "城市", "国家", "地下城", "神域", "星区"]):
        return "地点"
    if any(token in joined for token in ["规则", "法则", "体系", "制度", "检定", "代价"]):
        return "规则"
    if any(token in joined for token in ["势力", "组织", "阵营", "宗门", "军团", "教派"]):
        return "势力"
    if any(token in joined for token in ["道具", "物品", "装备", "遗物", "神器"]):
        return "物品"
    if any(token in joined for token in ["剧情", "主线", "冲突", "目标", "危机", "任务"]):
        return "剧情"
    return "设定"


def _normalize_string_list(values: list) -> list[str]:
    result: list[str] = []
    for value in values:
        text = str(value).strip()
        if not text or text in result:
            continue
        result.append(text)
    return result


def _looks_generic(text: str) -> bool:
    generic_tokens = ["神秘", "宏大", "复杂", "众多", "丰富", "充满机遇", "危机四伏"]
    hit_count = sum(text.count(token) for token in generic_tokens)
    return hit_count >= 3 or len(text.strip()) < 180


def _slugify_template_id(value: str) -> str:
    raw = (value or "").strip() or "world_template"
    normalized = "".join(ch.lower() if ch.isascii() and ch.isalnum() else "_" for ch in raw)
    while "__" in normalized:
        normalized = normalized.replace("__", "_")
    slug = normalized.strip("_")
    if slug:
        return slug
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]
    return f"world_{digest}"


def _safe_load_json(raw_text: str, fallback):
    text = (raw_text or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:].strip()
    try:
        return json.loads(text)
    except Exception:
        return fallback
