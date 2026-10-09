package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.MessageEntity
import javax.inject.Inject

class SlidingWindowBuilder @Inject constructor() {
    /** A message-count window must also retain the human turn that triggered a long reply chain. */
    fun takeRecentPreservingTurn(messages: List<MessageEntity>, maxMessages: Int): List<MessageEntity> {
        require(maxMessages > 0)
        val usualStart = (messages.size - maxMessages).coerceAtLeast(0)
        val latestUser = messages.indexOfLast { it.speakerType == "user" }
        val start = if (latestUser >= 0) minOf(usualStart, latestUser) else usualStart
        return messages.drop(start)
    }

    fun buildWindow(
        messages: List<MessageEntity>,
        maxTokens: Int
    ): List<MessageEntity> {
        if (messages.isEmpty()) return emptyList()
        // 输入已按分支、采用版本及上下文排除规则投影。短期配额只裁剪更早历史，
        // 不能丢掉当前完整输入，或让多角色接续失去触发本轮的用户消息。
        val latestUser = messages.indexOfLast { it.speakerType == "user" }
        var start = if (latestUser >= 0) latestUser else messages.lastIndex
        var remaining = maxTokens.toLong()
        for (index in start..messages.lastIndex) {
            remaining -= TokenCounter.estimate(messages[index].content)
        }
        while (start > 0 && remaining > 0) {
            val cost = TokenCounter.estimate(messages[start - 1].content)
            if (cost > remaining) break
            remaining -= cost
            start--
        }
        return messages.subList(start, messages.size).toList()
    }
}
