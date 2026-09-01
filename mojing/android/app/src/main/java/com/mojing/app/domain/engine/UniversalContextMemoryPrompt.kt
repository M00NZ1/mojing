package com.mojing.app.domain.engine

object UniversalContextMemoryPrompt {
    fun buildUpdatePrompt(
        oldMemoryText: String,
        conversationText: String,
        worldText: String,
        activeCharacterNames: List<String>,
    ): String = buildString {
        appendLine("你是通用长对话记忆压缩器，不参与剧情创作。")
        appendLine("你的任务是根据旧记忆和新增对话，更新一份可复用于任意题材的高密度连续性记忆。")
        appendLine("不得编造对话中没有的信息。信息不明确时使用空字符串或空数组。")
        appendLine("必须保留：已发生事件、角色关系、用户状态、角色已知/未知信息、承诺、冲突、伏笔、未完成任务、连续性约束。")
        appendLine("只返回 JSON 对象，不要 Markdown，不要解释。")
        appendLine()
        appendLine("【当前活跃角色】")
        appendLine(activeCharacterNames.joinToString("、").ifBlank { "无明确记录" })
        appendLine()
        appendLine("【世界/会话设定】")
        appendLine(worldText.ifBlank { "无明确记录" })
        appendLine()
        appendLine("【旧记忆】")
        appendLine(oldMemoryText.ifBlank { "无旧记忆" })
        appendLine()
        appendLine("【新增对话】")
        appendLine(conversationText)
        appendLine()
        appendLine("返回 JSON 字段必须包含：")
        appendLine("globalSummary, userState, characterStates, relationshipStates, worldState, recentCompressedTimeline, openThreads, continuityRules")
    }
}
