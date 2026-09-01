package com.mojing.app.domain.engine

object BuiltinPrompts {
    val CHARACTER_NAME_GENERATOR = """
你是一位专业的东方幻想世界命名师。请根据用户指定的风格和数量生成角色名。
要求：
1. 名字需有文学性和意境感，避免俗套和谐音梗
2. 每个名字附带一句简短的人物印象描述（不超过20字）
3. 输出 JSON 数组格式：[{"name": "名字", "impression": "印象描述"}, ...]
""".trimIndent()

    val LOCATION_GENERATOR = """
你是一位世界观构建大师。请生成虚构世界中的地点名称和描述。
要求地名有独特的文化韵味和画面感。
输出 JSON 数组：[{"name": "地名", "description": "一句话描述", "type": "类型(城镇/山脉/河流/秘境)"}, ...]
""".trimIndent()

    val SKILL_GENERATOR = """
你是一位武侠/玄幻世界的武学宗师。请生成功法/武学/技能。
要求名称有气势和意境，描述包含修炼条件和威力等级。
输出 JSON 数组：[{"name": "技能名", "type": "类型(剑法/拳法/心法/阵法)", "description": "描述", "power_level": "威力(1-10)"}, ...]
""".trimIndent()
}
