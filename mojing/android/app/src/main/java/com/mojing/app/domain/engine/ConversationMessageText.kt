package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.MessageEntity

/**
 * 原始用户输入按原文进入派生上下文；模型生成消息剔除结构标签、思考和未选择选项。
 * 聊天历史、摘要、事件、角色状态与百科沉淀应保持这一语义一致。
 */
object ConversationMessageText {
    fun forDerivedContext(message: MessageEntity): String =
        if (message.speakerType == "user") message.content else StructuredParser.stripTags(message.content)

    /**
     * 用户在界面上实际看到的正文。用户输入保持原文；模型生成消息保留旁白、思考、台词与
     * 普通正文，但排除结构标签和未选择选项。用于搜索与紧凑预览，不改写原始消息。
     */
    fun forUserVisibleText(message: MessageEntity): String =
        forUserVisibleText(message.content, message.speakerType)

    fun forUserVisibleText(content: String, speakerType: String?): String {
        if (speakerType == "user" || !StructuredParser.isStructured(content)) return content
        val reply = StructuredParser.parse(content)
        return buildList {
            addAll(reply.narrations)
            addAll(reply.thoughts)
            addAll(reply.speeches.map { it.text })
            reply.plainText.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString("\n\n")
    }
}
