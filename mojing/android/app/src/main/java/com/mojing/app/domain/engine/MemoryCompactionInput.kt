package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.MessageEntity

/** 分段只划分请求边界，原文逐字覆盖；预算为保守字符权重。 */
internal object MemoryCompactionInput {
    const val RAW_BUDGET = 4000
    const val MEMORY_BUDGET = 2400

    fun weight(text: String): Int = text.sumOf { if (it.code < 128) 1 else 2 }

    fun chunks(messages: List<MessageEntity>): Sequence<String> = sequence {
        val buffer = StringBuilder()
        var used = 0
        for (message in messages) {
            val body = ConversationMessageText.forDerivedContext(message)
            val speaker = when (message.speakerType) { "user" -> "用户"; "character" -> "角色"; else -> "旁白" }
            var offset = 0
            do {
                val header = "\n[$speaker #${message.id}${if (offset > 0) " 接续" else ""}]\n"
                val headerWeight = weight(header)
                if (used + headerWeight + 4 > RAW_BUDGET) {
                    yield(buffer.toString()); buffer.clear(); used = 0
                }
                buffer.append(header); used += headerWeight
                val start = offset
                while (offset < body.length) {
                    val count = Character.charCount(body.codePointAt(offset))
                    val cost = if (body[offset].code < 128) 1 else count * 2
                    if (used + cost > RAW_BUDGET) break
                    buffer.append(body, offset, offset + count)
                    used += cost; offset += count
                }
                if (offset < body.length) {
                    check(offset > start)
                    yield(buffer.toString()); buffer.clear(); used = 0
                }
            } while (offset < body.length)
        }
        if (buffer.isNotEmpty()) yield(buffer.toString())
    }

    fun previous(summaries: List<String>): String {
        val selected = mutableListOf<String>()
        var used = 0
        for (summary in summaries.asReversed()) {
            val cost = weight(summary) + 1
            if (used + cost <= MEMORY_BUDGET) { selected.add(summary); used += cost }
        }
        return selected.asReversed().joinToString("\n")
    }
}
