package com.mojing.app.engine

import com.mojing.app.data.local.dao.SessionEventNodeDao
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
    private val eventNodeDao = mockk<SessionEventNodeDao>(relaxed = true)
    private val manager = MemoryV2Manager(llmRetry, eventNodeDao)

    @Test
    fun extractedEventsKeepBranchAndValidatedSourceMessage() = runTest {
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
        coVerify(exactly = 2) {
            eventNodeDao.insert(match { it.sessionId == 7L && it.branchId == "edit_42" })
        }
    }
}
