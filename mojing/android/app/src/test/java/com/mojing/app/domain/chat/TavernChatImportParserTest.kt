package com.mojing.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernChatImportParserTest {
    @Test
    fun parsesNativeMessagesExport() {
        val json = """
            {
              "format": "mojing_chat_export",
              "version": 1,
              "messages": [
                {"speakerType":"user","content":"你好"},
                {"speakerType":"character","content":"欢迎回来"}
              ]
            }
        """.trimIndent()

        val rows = TavernChatImportParser.parse(json)

        assertEquals(listOf("user", "character"), rows.map { it.speaker })
        assertEquals(listOf("你好", "欢迎回来"), rows.map { it.content })
        assertTrue(rows[0].raw.has("speakerType"))
    }

    @Test
    fun parsesNativeMessagesJsonLines() {
        val jsonl = """
            {"speakerType":"user","content":"第一句"}
            {"speakerType":"character","content":"第二句"}
        """.trimIndent()

        val rows = TavernChatImportParser.parse(jsonl)

        assertEquals(2, rows.size)
        assertEquals("user", rows[0].speaker)
        assertEquals("character", rows[1].speaker)
    }

    @Test
    fun stableBatchIdIgnoresJsonFormattingAndUnrelatedMetadata() {
        val compact = TavernChatImportParser.parse(
            """{"messages":[{"speakerType":"user","content":"你好"},{"speakerType":"character","content":"欢迎"}]}""",
        )
        val formatted = TavernChatImportParser.parse(
            """
                {
                  "messages": [
                    {"content": "你好", "speakerType": "user", "createdAt": 123},
                    {"content": "欢迎", "speakerType": "character", "model": "local"}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(
            TavernChatImportParser.stableBatchId(compact),
            TavernChatImportParser.stableBatchId(formatted),
        )
        val changed = formatted.toMutableList().apply {
            this[1] = this[1].copy(content = "欢迎回来")
        }
        assertTrue(
            TavernChatImportParser.stableBatchId(compact) !=
                TavernChatImportParser.stableBatchId(changed),
        )
    }
}
