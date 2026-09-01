"""
统一 AI 补全服务 — 百科条目、人物、世界模板的智能字段补全。
按文档第5章要求实现统一的 ai_complete 入口。
"""

import json
import logging
from sqlalchemy.orm import Session

from ..models import CharacterModel
from ..services.llm_client import build_client
from ..services.llm_retry import safe_non_streaming_call
from ..services.system_config_service import get_local_config

logger = logging.getLogger(__name__)

COMPLETE_CONFIGS = {
    "encyclopedia_entry": {
        "context_builder": "_build_encyclopedia_context",
        "fields_map": {
            "world": ["alias", "genre", "canon_scope", "timeline_model", "geography_model", "power_system", "core_conflict", "hard_rules"],
            "character": ["alias", "race", "gender", "age", "background", "personality", "abilities", "relations"],
            "faction": ["alias", "type", "founder", "leader", "headquarters", "hierarchy", "goals", "members"],
            "location": ["alias", "region", "climate", "population", "architecture", "landmarks", "significance"],
            "item": ["alias", "type", "origin", "abilities", "limitations", "current_holder", "history"],
            "event": ["alias", "date", "participants", "causes", "consequences", "significance"],
            "skill": ["alias", "type", "requirements", "effects", "limitations", "learning_method"],
            "creature": ["alias", "species", "habitat", "abilities", "behavior", "weaknesses"],
            "profession": ["alias", "type", "requirements", "duties", "ranks", "skills"],
            "concept": ["alias", "definition", "scope", "mechanism", "limits", "examples"],
        },
    },
    "character": {
        "context_builder": "_build_character_context",
        "fields": ["persona_prompt"],
    },
    "world_template": {
        "context_builder": "_build_world_template_context",
        "fields": ["world_prompt", "anti_cheat_prompt", "suggested_choices"],
    },
}


def ai_complete(
    db: Session,
    target_type: str,
    target_data: dict,
    fields_to_complete: list[str] | None = None,
    character_id: int | None = None,
    extra_context: str = "",
) -> dict:
    """
    统一 AI 补全入口。

    出参:
        {
            "completed_fields": {"summary": "...", "alias": ["..."]},
            "model_used": "deepseek-chat",
            "token_usage": {"prompt": 500, "completion": 200}
        }
    """
    config = COMPLETE_CONFIGS.get(target_type)
    if not config:
        raise ValueError(f"不支持的补全类型: {target_type}")

    context = _build_universal_context(db, target_type, target_data, fields_to_complete or [], extra_context)

    prompt = f"""{context}

请输出一个 JSON 对象，键名为字段名，值为补全后的内容：
{{"字段名1": "补全内容1", "字段名2": "补全内容2"}}

规则：
- 对多值字段（如alias、tags），值使用 JSON 数组格式
- 不要填入已有内容，只补全新字段
- 内容应自然流畅，符合世界观设定
"""

    char: CharacterModel | None = db.get(CharacterModel, character_id) if character_id else None
    if char is None:
        char = CharacterModel(
            name="_ai_complete_public_",
            persona_prompt="",
            api_key="",
            api_base_url="",
            model_name="",
        )
    cfg = get_local_config(db)
    model = (char.model_name or "").strip() or (cfg.get("public_text_model") or "").strip() or "deepseek-chat"

    try:
        client = build_client(char, db)
        kwargs: dict = {}
        if char.top_k or char.repetition_penalty != 1.0:
            kwargs["extra_body"] = {"top_k": char.top_k, "repetition_penalty": char.repetition_penalty}
        content = safe_non_streaming_call(
            client,
            model,
            [{"role": "user", "content": prompt}],
            temperature=0.7,
            max_tokens=2000,
            **kwargs,
        )
    except Exception as e:
        logger.error(f"AI补全调用失败: {e}")
        return {"completed_fields": {}, "model_used": "", "token_usage": {}, "error": str(e)}

    try:
        text = content if isinstance(content, str) else str(content)
        json_start = text.find("{")
        json_end = text.rfind("}") + 1
        if json_start == -1 or json_end <= 0:
            return {"completed_fields": {}, "model_used": "", "token_usage": {}, "error": "LLM返回格式异常"}
        completed = json.loads(text[json_start:json_end])
    except (json.JSONDecodeError, KeyError) as e:
        return {"completed_fields": {}, "model_used": "", "token_usage": {}, "error": f"JSON解析失败: {e}"}

    model_used = model
    usage: dict = {}

    completed["_ai_completed_fields"] = list(completed.keys())

    return {
        "completed_fields": completed,
        "model_used": model_used,
        "token_usage": usage,
    }


def _build_universal_context(
    db: Session,
    target_type: str,
    target_data: dict,
    fields_to_complete: list[str],
    extra_context: str,
) -> str:
    """构建通用补全上下文。"""
    config = COMPLETE_CONFIGS.get(target_type, {})
    target_name = target_data.get("title") or target_data.get("name") or "未知"

    entry_type = target_data.get("entry_type", "concept")
    fields_map = config.get("fields_map", {})
    fields = config.get("fields", fields_map.get(entry_type, []))

    if fields_to_complete:
        fields = fields_to_complete

    context = f"""你是一个专业的世界观设计师。请为以下内容补全缺失字段。

当前条目：{target_name}
目标类型：{target_type}
条目子类型：{entry_type}
需要补全的字段：{json.dumps(fields, ensure_ascii=False)}
已有内容：{json.dumps(target_data, ensure_ascii=False)[:1500]}

"""
    if extra_context:
        context += f"额外提示：{extra_context}\n"

    context += "请为每个字段生成专业、详细的内容。"
    return context
