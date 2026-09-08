package com.mojing.app.engine

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.MemoryCompactionInput
import org.junit.Assert.*
import org.junit.Test

class MemoryCompactionInputTest {
    @Test fun longMixedMessagesAreCoveredExactlyWithinBudget() {
        val texts = listOf("开头\n" + "中文🙂 and words\n".repeat(2000) + "结尾秘密", "", "下一条消息")
        val messages = texts.mapIndexed { index, text -> MessageEntity(id = index + 1L, sessionId = 1, speakerType = "user", content = text) }
        val chunks = MemoryCompactionInput.chunks(messages).toList()
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { MemoryCompactionInput.weight(it) <= MemoryCompactionInput.RAW_BUDGET })
        val reconstructed = chunks.joinToString("") { it.replace(Regex("\n\\[用户 #\\d+( 接续)?]\\n"), "") }
        assertEquals(texts.joinToString(""), reconstructed)
        assertTrue(chunks.dropLast(1).none { it.last().isHighSurrogate() })
    }

    @Test fun shortMessagesShareOneRequestAndRetainSpeakerLabels() {
        val messages = listOf(MessageEntity(id = 1, sessionId = 1, content = "问候", speakerType = "user"),
            MessageEntity(id = 2, sessionId = 1, content = "回应", speakerType = "character"))
        val chunk = MemoryCompactionInput.chunks(messages).single()
        assertTrue(chunk.contains("用户 #1"))
        assertTrue(chunk.contains("角色 #2"))
        assertTrue(chunk.contains("问候") && chunk.contains("回应"))
    }

    @Test fun previousSummariesAreKeptWholeWithinBudget() {
        val selected = MemoryCompactionInput.previous(listOf("旧".repeat(2000), "近期摘要", "最新事实"))
        assertEquals("近期摘要\n最新事实", selected)
        assertTrue(MemoryCompactionInput.weight(selected) <= MemoryCompactionInput.MEMORY_BUDGET)
    }
}
