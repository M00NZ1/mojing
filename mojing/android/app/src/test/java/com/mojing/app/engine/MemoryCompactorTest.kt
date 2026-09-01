package com.mojing.app.engine

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.MemoryCompactor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCompactorTest {
    private val llmRetry = mockk<LlmRetry>()
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val memorySegmentDao = mockk<SessionMemorySegmentDao>(relaxed = true)
    private val compactor = MemoryCompactor(llmRetry, messageDao, memorySegmentDao)

    @Test
    fun continuesFromInheritedCursorBeyondTheModelContextWindow() = runTest {
        val previous = SessionMemorySegmentEntity(
            sessionId = 7L,
            branchId = "main",
            segmentIndex = 0,
            startMessageId = 1L,
            endMessageId = 20L,
            summary = "此前剧情",
        )
        val nextBatch = (21L..620L).map { id ->
            MessageEntity(
                id = id,
                sessionId = 7L,
                branchId = "edit_42",
                speakerType = if (id % 2L == 0L) "character" else "user",
                content = "剧情-$id",
            )
        }
        val saved = slot<SessionMemorySegmentEntity>()
        val requestMessages = slot<List<ChatMessage>>()
        coEvery { memorySegmentDao.getRecentForBranch(7L, "edit_42", 3) } returns listOf(previous)
        coEvery {
            messageDao.getNextStoryContextBatch(7L, "edit_42", 20L, 600)
        } returns nextBatch
        coEvery {
            llmRetry.chatCompletionWithRetry(
                apiKey = "k",
                baseUrl = "https://api.test.com",
                model = "m",
                messages = capture(requestMessages),
                temperature = 0.5f,
                maxTokens = 400,
                maxRetries = 3,
            )
        } returns """{"summary":"抵达月港","emotional_tone":"紧张","key_facts":["门已开启"]}"""
        coEvery { memorySegmentDao.nextSegmentIndex(7L, "edit_42") } returns 4
        coEvery { memorySegmentDao.insert(capture(saved)) } returns 99L

        compactor.compactIfNeeded(
            sessionId = 7L,
            branchId = "edit_42",
            apiKey = "k",
            baseUrl = "https://api.test.com",
            model = "m",
            threshold = 600,
        )

        assertEquals(21L, saved.captured.startMessageId)
        assertEquals(620L, saved.captured.endMessageId)
        assertEquals("edit_42", saved.captured.branchId)
        assertEquals(4, saved.captured.segmentIndex)
        assertEquals("抵达月港", saved.captured.summary)
        assertEquals("[\"门已开启\"]", saved.captured.keyFactsJson)
        assertTrue(requestMessages.captured.last().content.contains("之前摘要：此前剧情"))
        coVerify(exactly = 1) {
            messageDao.getNextStoryContextBatch(7L, "edit_42", 20L, 600)
        }
    }

    @Test
    fun waitsForACompleteBatchWithoutCallingTheModel() = runTest {
        val incomplete = (1L..599L).map { id ->
            MessageEntity(id = id, sessionId = 1L, speakerType = "user", content = "剧情-$id")
        }
        coEvery { memorySegmentDao.getRecentForBranch(1L, "main", 3) } returns emptyList()
        coEvery { messageDao.getNextStoryContextBatch(1L, "main", 0L, 600) } returns incomplete

        compactor.compactIfNeeded(
            sessionId = 1L,
            branchId = "main",
            apiKey = "k",
            baseUrl = "https://api.test.com",
            model = "m",
            threshold = 600,
        )

        coVerify(exactly = 0) {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { memorySegmentDao.insert(any()) }
    }

    @Test
    fun cancellationStopsCompactionWithoutPersistingAPartialSegment() = runTest {
        val batch = (1L..10L).map { id ->
            MessageEntity(id = id, sessionId = 2L, speakerType = "user", content = "剧情-$id")
        }
        coEvery { memorySegmentDao.getRecentForBranch(2L, "main", 3) } returns emptyList()
        coEvery { messageDao.getNextStoryContextBatch(2L, "main", 0L, 10) } returns batch
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any())
        } throws CancellationException("stop")

        var propagated = false
        try {
            compactor.compactIfNeeded(
                sessionId = 2L,
                branchId = "main",
                apiKey = "k",
                baseUrl = "https://api.test.com",
                model = "m",
                threshold = 10,
            )
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        coVerify(exactly = 0) { memorySegmentDao.nextSegmentIndex(any(), any()) }
        coVerify(exactly = 0) { memorySegmentDao.insert(any()) }
    }
}
