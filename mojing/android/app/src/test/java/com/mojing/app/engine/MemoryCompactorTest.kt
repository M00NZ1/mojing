package com.mojing.app.engine

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.MemoryCompactor
import com.mojing.app.domain.engine.MemoryCompactionStore
import com.mojing.app.domain.engine.MemoryCompactionSnapshot
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
    private val store = mockk<MemoryCompactionStore>(relaxed = true)
    private val compactor = MemoryCompactor(llmRetry, store)

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
        val snapshot = MemoryCompactionSnapshot(7, "edit_42", listOf(previous), 0, nextBatch, 600)
        coEvery { store.read(7L, "edit_42", 600) } returns snapshot
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
        coEvery { store.commit(snapshot, capture(saved)) } returns true

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
        assertEquals("抵达月港", saved.captured.summary)
        assertEquals("[\"门已开启\"]", saved.captured.keyFactsJson)
        assertTrue(requestMessages.captured.last().content.contains("之前摘要：此前剧情"))
        coVerify(exactly = 1) {
            store.commit(snapshot, any())
        }
    }

    @Test
    fun waitsForACompleteBatchWithoutCallingTheModel() = runTest {
        val incomplete = (1L..599L).map { id ->
            MessageEntity(id = id, sessionId = 1L, speakerType = "user", content = "剧情-$id")
        }
        coEvery { store.read(1L, "main", 600) } returns MemoryCompactionSnapshot(1, "main", emptyList(), 0, incomplete, 600)

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
        coVerify(exactly = 0) { store.commit(any(), any()) }
    }

    @Test
    fun cancellationStopsCompactionWithoutPersistingAPartialSegment() = runTest {
        val batch = (1L..10L).map { id ->
            MessageEntity(id = id, sessionId = 2L, speakerType = "user", content = "剧情-$id")
        }
        coEvery { store.read(2L, "main", 10) } returns MemoryCompactionSnapshot(2, "main", emptyList(), 0, batch, 10)
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
        coVerify(exactly = 0) { store.commit(any(), any()) }
    }

    @Test fun malformedOrEmptySummaryDoesNotCoverHistoryAndCanRetry() = runTest {
        val source = MessageEntity(id = 1, sessionId = 2, content = "不能用开头替代全部剧情")
        coEvery { store.read(2, "main", 1) } returns MemoryCompactionSnapshot(2, "main", emptyList(), 0, listOf(source), 1)
        for (response in listOf("服务繁忙", "{}", "{\"summary\":\" \"}", "{\"summary\":123}", "{broken")) {
            coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns response
            org.junit.Assert.assertFalse(compactor.compactIfNeeded(2, "main", "k", "url", "m", 1))
        }
        coVerify(exactly = 0) { store.commit(any(), any()) }
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns """{"summary":"有效摘要"}"""
        coEvery { store.commit(any(), any()) } returns true
        assertTrue(compactor.compactIfNeeded(2, "main", "k", "url", "m", 1))
    }

    @Test fun failedSnapshotReadDoesNotBlockTheChatRound() = runTest {
        coEvery { store.read(any(), any(), any()) } throws IllegalStateException("read failed")
        org.junit.Assert.assertFalse(compactor.compactIfNeeded(2, "main", "k", "url", "m"))
        coVerify(exactly = 0) { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) }
    }
}
