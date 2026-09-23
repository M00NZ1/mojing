package com.mojing.app.engine

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.MemoryCompactor
import com.mojing.app.domain.engine.MemoryCompactionStore
import com.mojing.app.domain.engine.MemoryCompactionSnapshot
import com.mojing.app.domain.engine.MemoryCompactionCheckpoint
import com.mojing.app.domain.engine.sourceFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCompactorTest {
    private val llmRetry = mockk<LlmRetry>()
    private val store = mockk<MemoryCompactionStore>(relaxed = true)
    private val compactor = MemoryCompactor(llmRetry, store)

    @Test fun longMessageResumesAfterOneChunkPerChatRound() = runTest {
        val source = MessageEntity(id = 1, sessionId = 2, speakerType = "user", content = "长篇剧情".repeat(4000))
        val base = MemoryCompactionSnapshot(2, "main", emptyList(), 0, listOf(source), 1)
        var checkpoint: MemoryCompactionCheckpoint? = null
        val requests = mutableListOf<List<ChatMessage>>()
        val progress = mutableListOf<Int>()
        coEvery { store.read(2, "main", 1) } coAnswers { base.copy(checkpoint = checkpoint) }
        coEvery { store.saveCheckpoint(any(), any()) } coAnswers { checkpoint = secondArg() }
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), capture(requests), any(), any(), any()) } returns
            """{"summary":"延续剧情","key_facts":["约定仍有效"]}"""
        coEvery { store.commit(any(), any()) } returns true

        var finished = false
        repeat(12) {
            if (!finished) finished = compactor.compactIfNeeded(2, "main", "k", "url", "m", 1, progress::add, 1)
        }
        assertTrue(finished)
        assertTrue(requests.size > 2)
        assertEquals((1..requests.size).toList(), progress)
        assertTrue(requests.last().last().content.contains("长篇剧情"))
        coVerify(exactly = requests.size - 1) { store.saveCheckpoint(any(), any()) }
        coVerify(exactly = 1) { store.commit(any(), any()) }
    }

    @Test fun changedSourceRejectsSavedChunkCursor() = runTest {
        val oldSource = MessageEntity(id = 1, sessionId = 2, content = "旧剧情".repeat(2500))
        val oldSnapshot = MemoryCompactionSnapshot(2, "main", emptyList(), 0, listOf(oldSource), 1)
        val changedSource = oldSource.copy(content = "新剧情".repeat(2500))
        val stale = MemoryCompactionCheckpoint(sourceFingerprint = oldSnapshot.sourceFingerprint(),
            nextChunkIndex = 1, carriedSummary = "旧段摘要")
        val current = oldSnapshot.copy(sources = listOf(changedSource), checkpoint = stale)
        val progress = mutableListOf<Int>()
        coEvery { store.read(2, "main", 1) } returns current
        coEvery { store.saveCheckpoint(any(), any()) } returns Unit
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns
            """{"summary":"新段摘要"}"""

        assertFalse(compactor.compactIfNeeded(2, "main", "k", "url", "m", 1, progress::add, 1))
        assertEquals(listOf(1), progress)
        coVerify(exactly = 0) { store.commit(any(), any()) }
    }

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
        val requestMessages = mutableListOf<List<ChatMessage>>()
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
            maxChunksPerRun = 1000,
        )

        assertEquals(21L, saved.captured.startMessageId)
        assertEquals(620L, saved.captured.endMessageId)
        assertEquals("edit_42", saved.captured.branchId)
        assertEquals("抵达月港", saved.captured.summary)
        assertEquals("[\"门已开启\"]", saved.captured.keyFactsJson)
        assertTrue(requestMessages.first().last().content.contains("之前摘要：此前剧情"))
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

    @Test fun longMessageTailReachesModelBeforeOneFinalCommit() = runTest {
        val source = MessageEntity(id = 1, sessionId = 2, speakerType = "user", content = "正文".repeat(3500) + "结尾的约定")
        val snapshot = MemoryCompactionSnapshot(2, "main", emptyList(), 0, listOf(source), 1)
        coEvery { store.read(2, "main", 1) } returns snapshot
        val requests = mutableListOf<List<ChatMessage>>()
        val progress = mutableListOf<Int>()
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), capture(requests), any(), any(), any()) } returns """{"summary":"保留约定","key_facts":["尚待履行"]}"""
        coEvery { store.commit(snapshot, any()) } returns true
        assertTrue(compactor.compactIfNeeded(2, "main", "k", "url", "m", 1, progress::add))
        assertTrue(requests.size > 1)
        assertTrue(requests.last().last().content.contains("结尾的约定"))
        assertEquals((1..requests.size).toList(), progress)
        assertTrue(requests.all { com.mojing.app.domain.engine.MemoryCompactionInput.weight(it.joinToString { message -> message.content }) < 7200 })
        coVerify(exactly = 1) { store.commit(snapshot, any()) }
    }

    @Test fun lateChunkFailureOrCancellationNeverAdvancesCoverage() = runTest {
        val source = MessageEntity(id = 1, sessionId = 2, content = "长消息".repeat(2000))
        coEvery { store.read(2, "main", 1) } returns MemoryCompactionSnapshot(2, "main", emptyList(), 0, listOf(source), 1)
        for (cancel in listOf(false, true)) {
            var count = 0
            coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } answers {
                if (++count == 1) """{"summary":"第一段"}"""
                else if (cancel) throw CancellationException("stop") else "broken"
            }
            try {
                org.junit.Assert.assertFalse(compactor.compactIfNeeded(2, "main", "k", "url", "m", 1))
                org.junit.Assert.assertFalse(cancel)
            } catch (_: CancellationException) { assertTrue(cancel) }
        }
        coVerify(exactly = 0) { store.commit(any(), any()) }
    }

    @Test fun manuallyCompactsShortHistoricalGapBeforeTheNextCoveredSegment() = runTest {
        val next = SessionMemorySegmentEntity(id = 8, sessionId = 2, branchId = "main",
            startMessageId = 20, endMessageId = 40, summary = "后续剧情")
        val source = MessageEntity(id = 12, sessionId = 2, content = "漏掉的旧剧情")
        val snapshot = MemoryCompactionSnapshot(2, "main", listOf(next), 0, listOf(source), 1,
            checkpoint = null, cursorAfterMessageId = 0, nextCoveredSegment = next, contextSegments = emptyList())
        coEvery { store.read(2, "main", 20, true) } returns snapshot
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns
            """{"summary":"找回旧剧情"}"""
        coEvery { store.commit(snapshot, any()) } returns true

        assertEquals(MemoryCompactor.PendingBatch(1, 1), compactor.pendingBatch(2, "main", 20))
        assertTrue(compactor.compactIfNeeded(2, "main", "k", "url", "m", threshold = 20,
            scanHistoricalGaps = true))
        coVerify(exactly = 1) { store.commit(snapshot, match { it.startMessageId == 12L && it.endMessageId == 12L }) }
    }
}
