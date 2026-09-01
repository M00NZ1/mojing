package com.mojing.app.ui.chat

/**
 * 与后端 `macro_service.get_available_macros()` 列表一致，供聊天「+」菜单展示。
 */
data class ChatMacroDefinition(
    val macro: String,
    val label: String,
    val description: String,
)

object ChatMacroDefinitions {
    val ALL: List<ChatMacroDefinition> = listOf(
        ChatMacroDefinition("{{user}}", "你的名字", "插入你在「个人资料」里填的称呼"),
        ChatMacroDefinition("{{user_description}}", "你的简介", "插入你在「个人资料」里写的自我介绍"),
        ChatMacroDefinition("{{char}}", "当前角色名", "插入正在说话的角色的名字"),
        ChatMacroDefinition("{{char_description}}", "当前角色简介", "插入该角色的设定摘要"),
        ChatMacroDefinition("{{time}}", "现在几点", "插入当前时间，如 14:30"),
        ChatMacroDefinition("{{date}}", "今天日期", "插入今天日期，如 2026-05-13"),
        ChatMacroDefinition("{{session_id}}", "本局编号", "插入这场对话在软件里的编号"),
        ChatMacroDefinition("{{model}}", "用的模型名", "插入该角色绑定的对话模型名称"),
        ChatMacroDefinition("{{random}}", "随机数", "插入 1～100 的随机整数"),
    )
}
