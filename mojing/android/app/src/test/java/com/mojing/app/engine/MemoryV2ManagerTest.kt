package com.mojing.app.engine

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.MemoryV2Manager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ManagerTest {
    private val llmRetry = mockk<LlmRetry>()
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val manager = MemoryV2Manager(llmRetry, messageDao)

    @Test fun cancellationPropagatesWithoutWritingEvents() = runTest {
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } throws kotlinx.coroutines.CancellationException("stopped")
        var cancelled = false
        try { manager.extractEventNodes(7, "main", null, listOf(MessageEntity(id = 1, sessionId = 7)), "k", "url", "m") }
        catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
        coVerify(exactly = 0) { messageDao.commitDerivedEvents(any(), any(), any(), any()) }
    }

    @Test fun malformedLaterItemCannotSaveAnEarlierPartialEvent() = runTest {
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns """[{"title":"有效标题"},"损坏项"]"""
        assertTrue(manager.extractEventNodes(7, "main", null, listOf(MessageEntity(id = 1, sessionId = 7)), "k", "url", "m").isEmpty())
        coVerify(exactly = 0) { messageDao.commitDerivedEvents(any(), any(), any(), any()) }
    }

    @Test fun eventCountAndImportanceAreBoundedBeforeOneAtomicWrite() = runTest {
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns
            (1..30).joinToString(prefix = "[", postfix = "]") { """{"title":"事件$it","importance":999999,"message_id":1}""" }
        coEvery { messageDao.commitDerivedEvents(any(), any(), any(), any()) } coAnswers { arg<List<SessionEventNodeEntity>>(3) }
        val result = manager.extractEventNodes(7, "main", null, listOf(MessageEntity(id = 1, sessionId = 7)), "k", "url", "m")
        assertEquals(5, result.size)
        assertTrue(result.all { it.importance == 5 })
        coVerify(exactly = 1) { messageDao.commitDerivedEvents(7, "main", any(), any()) }
    }

    @Test
    fun extractedEventsKeepBranchAndValidatedSourceMessage() = runTest {
        coEvery { messageDao.commitDerivedEvents(any(), any(), any(), any()) } coAnswers { arg<List<SessionEventNodeEntity>>(3) }
        val requestMessages = slot<List<ChatMessage>>()
        coEvery {
            llmRetry.chatCompletionWithRetry(
                apiKey = any(),
                baseUrl = any(),
                model = any(),
                messages = capture(requestMessages),
                temperature = any(),
                maxTokens = any(),
                maxRetries = any(),
            )
        } returns """
            [
              {"event_type":"discovery","title":"发现门扉","description":"在墙后发现门扉","importance":4,"message_id":100},
              {"event_type":"action","title":"继续前进","description":"向门扉走去","importance":2,"message_id":999}
            ]
        """.trimIndent()

        val result = manager.extractEventNodes(
            sessionId = 7L,
            branchId = "edit_42",
            characterId = 8L,
            messages = listOf(
                MessageEntity(id = 100L, sessionId = 7L, content = "发现门扉"),
                MessageEntity(
                    id = 101L,
                    sessionId = 7L,
                    speakerType = "character",
                    content = "<SPEECH>继续前进</SPEECH><CHOICES><OPTION>先停下</OPTION></CHOICES>",
                ),
            ),
            apiKey = "k",
            baseUrl = "https://api.test.com",
            model = "m",
        )

        assertEquals(listOf(100L, 101L), result.map { it.messageId })
        assertEquals(listOf("edit_42", "edit_42"), result.map { it.branchId })
        val prompt = requestMessages.captured.joinToString("\n") { it.content }
        assertTrue(prompt.contains("继续前进"))
        assertFalse(prompt.contains("先停下"))
        assertFalse(prompt.contains("<OPTION"))
        coVerify(exactly = 1) { messageDao.commitDerivedEvents(7L, "edit_42", any(), match { it.size == 2 }) }
    }
}
