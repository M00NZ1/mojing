from __future__ import annotations

import random
from datetime import datetime


MACRO_REGISTRY: dict[str, str] = {
    "{{user}}": "玩家",
    "{{time}}": "",
    "{{date}}": "",
    "{{random}}": "",
    "{{char}}": "",
    "{{model}}": "",
    "{{char_description}}": "",
    "{{session_id}}": "",
}


def get_available_macros() -> list[dict]:
    """返回可用宏变量列表，供前端选择器使用。"""
    return [
        {"macro": "{{user}}", "label": "玩家名称", "description": "展开为用户人设名称"},
        {"macro": "{{user_description}}", "label": "玩家描述", "description": "展开为用户人设描述"},
        {"macro": "{{char}}", "label": "当前角色名", "description": "展开为当前发言角色的名称"},
        {"macro": "{{char_description}}", "label": "角色人设", "description": "展开为当前角色的人设提示"},
        {"macro": "{{time}}", "label": "当前时间", "description": "展开为 HH:MM 格式的当前时间"},
        {"macro": "{{date}}", "label": "当前日期", "description": "展开为 YYYY-MM-DD 格式的当前日期"},
        {"macro": "{{session_id}}", "label": "会话 ID", "description": "展开为当前会话的数字 ID"},
        {"macro": "{{model}}", "label": "模型名称", "description": "展开为当前角色使用的模型名"},
        {"macro": "{{random}}", "label": "随机数", "description": "展开为 1-100 的随机整数"},
    ]


def expand_macros(text: str, context: dict | None = None) -> str:
    """展开提示词中的所有宏变量。

    context 支持以下键：
    - character_name: 当前角色名
    - character_persona: 当前角色人设
    - model_name: 当前模型名
    - session_id: 当前会话 ID
    - user_name: 用户人设名称（覆盖默认「玩家」）
    - user_description: 用户人设描述
    """
    if not text:
        return text

    ctx = context or {}
    now = datetime.now()

    user_name = ctx.get("user_name", "玩家")
    user_desc = ctx.get("user_description", "")

    replacements = {
        "{{user}}": user_name,
        "{{user_description}}": user_desc,
        "{{char}}": str(ctx.get("character_name", "角色")),
        "{{char_description}}": str(ctx.get("character_persona", "")),
        "{{time}}": now.strftime("%H:%M"),
        "{{date}}": now.strftime("%Y-%m-%d"),
        "{{session_id}}": str(ctx.get("session_id", "")),
        "{{model}}": str(ctx.get("model_name", "")),
        "{{random}}": str(random.randint(1, 100)),
    }

    result = text
    for macro, value in replacements.items():
        if macro in result:
            result = result.replace(macro, value)
    return result
