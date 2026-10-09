package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage

/** Final-text estimate, not a provider tokenizer. Never truncates protected text. */
object RequestContextBudget {
    sealed class Result {
        data class Ready(val messages: List<ChatMessage>, val estimatedInput: Long, val removedMessages: Int, val removedPromptBlocks: Int = 0) : Result()
        data class TooLarge(val estimatedInput: Long, val outputTokens: Int, val capacity: Int) : Result() {
            fun message(): String = "当前设定和本轮完整对话估算需要 $estimatedInput 输入 Token，另预留 $outputTokens 输出 Token，" +
                "超过已设置的上下文总容量 $capacity。未发送请求；原文已保留。请核对平台模型容量，或调整回复上限与设定后重试。"
        }
    }

    // Allow for role markers, message wrappers and protocol fields. This is still an estimate.
    fun estimateInput(messages: List<ChatMessage>): Long = 256L + messages.sumOf { message ->
        var weighted = 0L
        for (char in message.content) weighted += if (char.code > 127) 2L else 1L
        weighted * 4 / 5 + 16L
    }

    fun fit(messages: List<ChatMessage>, capacity: Int?, outputTokens: Int): Result {
        if (capacity == null) return Result.Ready(messages, estimateInput(messages), 0)
        require(capacity > 0 && outputTokens > 0)
        val historyIndices = messages.indices.filter { messages[it].role != "system" }
        val latestUser = historyIndices.lastOrNull { messages[it].role == "user" }
        val protectedStart = latestUser ?: historyIndices.lastOrNull() ?: messages.size
        val removable = historyIndices.filter { it < protectedStart }
        var estimate = estimateInput(messages)
        var removed = 0
        while (estimate + outputTokens > capacity && removed < removable.size) {
            val message = messages[removable[removed]]
            // Difference removes exactly this message's body and wrapper, without rescanning all text.
            estimate -= estimateInput(listOf(message)) - 256L
            removed++
        }
        if (estimate + outputTokens > capacity) return Result.TooLarge(estimate, outputTokens, capacity)
        val removedIndices = removable.take(removed).toSet()
        return Result.Ready(messages.filterIndexed { index, _ -> index !in removedIndices }, estimate, removed)
    }

    /** Classification comes only from source assembly, never from labels inside rendered text. */
    fun fit(messages: List<ChatMessage>, capacity: Int?, outputTokens: Int, document: PromptDocument?): Result {
        val systemIndex = messages.indexOfFirst { it.role == "system" }
        if (capacity == null || document == null || systemIndex < 0 ||
            messages[systemIndex].content != document.render()) return fit(messages, capacity, outputTokens)
        require(capacity > 0 && outputTokens > 0)
        val retained = document.blocks.toMutableList()
        val candidates = document.blocks.filter { it.kind == PromptBlock.Kind.AUTOMATIC_SUMMARY }
        var fittedMessages = messages
        var removed = 0
        for (block in candidates) {
            if (estimateInput(fittedMessages) + outputTokens <= capacity) break
            retained.remove(block)
            val systemText = PromptDocument(retained).render()
            fittedMessages = messages.mapIndexed { index, message ->
                if (index == systemIndex) message.copy(content = systemText) else message
            }
            removed++
        }
        val result = fit(fittedMessages, capacity, outputTokens)
        return if (result is Result.Ready) result.copy(removedPromptBlocks = removed) else result
    }
}
