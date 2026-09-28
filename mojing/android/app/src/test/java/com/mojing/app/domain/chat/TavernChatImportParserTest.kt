package com.mojing.app.domain.chat

import java.io.StringReader
import kotlinx.coroutines.runBlocking
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

    @Test
    fun streamsLargeArrayWithoutChangingRowOrderOrBatchId() = runBlocking {
        val json = """{"messages":[${(1..270).joinToString(",") { index ->
            """{"speakerType":"${if (index % 2 == 0) "character" else "user"}","content":"第${index}句"}"""
        }}]}"""
        val streamed = mutableListOf<TavernChatImportParser.Row>()
        TavernChatImportParser.forEachRow(StringReader(json)) { streamed += it }
        val fingerprint = TavernChatImportParser.Fingerprint()
        streamed.forEach(fingerprint::add)

        assertEquals(270, fingerprint.count)
        assertEquals("第1句", streamed.first().content)
        assertEquals("第270句", streamed.last().content)
        assertEquals(TavernChatImportParser.stableBatchId(TavernChatImportParser.parse(json)), fingerprint.batchId())
    }

    @Test
    fun streamsJsonLinesWithComments() = runBlocking {
        val rows = mutableListOf<TavernChatImportParser.Row>()
        TavernChatImportParser.forEachRow(StringReader("# export\n" +
            """{"speakerType":"user","content":"你好"}""" + "\n" +
            """{"role":"assistant","content":"欢迎"}""")) { rows += it }

        assertEquals(listOf("user", "character"), rows.map { it.speaker })
        assertEquals(listOf("你好", "欢迎"), rows.map { it.content })
    }
}
