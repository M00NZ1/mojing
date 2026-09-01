package com.mojing.app.domain.chat

import com.mojing.app.data.local.entity.MessageEntity
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBranchChatExportWriterTest {
    @Test
    fun writesCompatibleJsonWithoutLoadingMoreThanOnePage() = runBlocking {
        val messages = (1L..600L).map { id ->
            MessageEntity(
                id = id,
                sessionId = 9L,
                speakerType = if (id % 2L == 0L) "character" else "user",
                characterId = if (id % 2L == 0L) 3L else null,
                content = "第 $id 条 <剧情>",
                structuredContentJson = "{\"turn\":$id}",
                includeInContext = id % 3L != 0L,
                createdAt = 1_000L + id,
            )
        }
        val requestedLimits = mutableListOf<Int>()
        val output = ByteArrayOutputStream()

        val count = MainBranchChatExportWriter.write(
            output = output,
            sessionId = 9L,
            exportedAt = 1234L,
            maxMessageId = 600L,
        ) { after, max, limit ->
            requestedLimits += limit
            messages.filter { it.id > after && it.id <= max }.take(limit)
        }

        assertEquals(600L, count)
        assertEquals(listOf(256, 256, 256), requestedLimits)
        val root = JsonParser.parseString(output.toString(Charsets.UTF_8)).asJsonObject
        assertEquals("mojing_chat_export", root.get("format").asString)
        assertEquals(1, root.get("version").asInt)
        assertEquals(9L, root.get("sessionId").asLong)
        assertEquals(1234L, root.get("exportedAt").asLong)
        val rows = root.getAsJsonArray("messages")
        assertEquals(600, rows.size())
        assertEquals("第 1 条 <剧情>", rows[0].asJsonObject.get("content").asString)
        assertFalse(rows[0].asJsonObject.has("characterId"))
        assertEquals(3L, rows[1].asJsonObject.get("characterId").asLong)
        assertEquals(600L, rows.last().asJsonObject.get("id").asLong)
        assertTrue(output.toString(Charsets.UTF_8).contains("\\u003c剧情\\u003e"))
    }

    @Test
    fun fixedUpperBoundExcludesMessagesInsertedAfterExportStarts() = runBlocking {
        val source = (1L..4L).map { id -> MessageEntity(id = id, sessionId = 1L, content = "$id") }
        val output = ByteArrayOutputStream()

        MainBranchChatExportWriter.write(output, 1L, 1L, maxMessageId = 3L) { after, max, limit ->
            source.filter { it.id > after && it.id <= max }.take(limit)
        }

        val rows = JsonParser.parseString(output.toString(Charsets.UTF_8))
            .asJsonObject.getAsJsonArray("messages")
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.asJsonObject.get("id").asLong })
    }

    @Test
    fun propagatesWriteFailureSoCallerCanReportPartialDocument() {
        val failure = assertThrows(IOException::class.java) {
            runBlocking {
                MainBranchChatExportWriter.write(
                    output = FailingOutputStream(maxBytes = 32),
                    sessionId = 1L,
                    exportedAt = 1L,
                    maxMessageId = 1L,
                ) { _, _, _ -> listOf(MessageEntity(id = 1L, sessionId = 1L, content = "正文")) }
            }
        }

        assertTrue(failure.message.orEmpty().contains("synthetic"))
    }

    @Test
    fun observesCancellationAfterLoadingAPage() = runTest {
        lateinit var exportJob: Job
        val failure = CompletableDeferred<Throwable?>()
        exportJob = launch {
            failure.complete(
                runCatching {
                    MainBranchChatExportWriter.write(
                        output = ByteArrayOutputStream(),
                        sessionId = 1L,
                        exportedAt = 1L,
                        maxMessageId = 1L,
                    ) { _, _, _ ->
                        exportJob.cancel()
                        listOf(MessageEntity(id = 1L, sessionId = 1L, content = "正文"))
                    }
                }.exceptionOrNull(),
            )
        }
        exportJob.join()

        assertTrue(failure.await() is CancellationException)
    }

    private class FailingOutputStream(private val maxBytes: Int) : OutputStream() {
        private var written = 0

        override fun write(value: Int) {
            if (written >= maxBytes) throw IOException("synthetic write failure")
            written += 1
        }
    }
}
