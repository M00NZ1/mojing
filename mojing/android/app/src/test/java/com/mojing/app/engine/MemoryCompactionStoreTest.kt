package com.mojing.app.engine

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.domain.engine.MemoryCompactionSnapshot
import com.mojing.app.domain.engine.MemoryCompactionStore
import com.mojing.app.domain.engine.scanMemoryCoveragePage
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MemoryCompactionStoreTest {
    private val messages = mockk<MessageDao>(relaxed = true)
    private val segments = mockk<SessionMemorySegmentDao>(relaxed = true)
    private val previous = SessionMemorySegmentEntity(id = 2, sessionId = 7, startMessageId = 1, endMessageId = 10, summary = "此前")
    private val sources = listOf(MessageEntity(id = 11, sessionId = 7, content = "完整原文"), MessageEntity(id = 12, sessionId = 7, content = "新的发现"))
    private val snapshot = MemoryCompactionSnapshot(7, "A", listOf(previous), 0, sources, 2)
    private val candidate = SessionMemorySegmentEntity(sessionId = 7, branchId = "A", startMessageId = 11, endMessageId = 12, summary = "新摘要")

    @Test fun coverageScanFindsOnlyVisibleStoryMessagesOutsideCoveredIntervals() = runTest {
        val first = previous.copy(startMessageId = 1, endMessageId = 10)
        val overlap = previous.copy(id = 3, startMessageId = 8, endMessageId = 15)
        val later = previous.copy(id = 4, startMessageId = 20, endMessageId = 30)
        val segments = listOf(first, overlap, later)
        val visible = listOf(1L, 12L, 18L, 20L)
        val gap = scanMemoryCoveragePage(0, segments) { after -> visible.firstOrNull { it > after } }
        assertEquals(15L, gap.throughMessageId)
        assertEquals(later, gap.gapBefore)

        val noGap = scanMemoryCoveragePage(0, segments) { after ->
            listOf(1L, 12L, 20L).firstOrNull { it > after }
        }
        assertEquals(30L, noGap.throughMessageId)
        assertNull(noGap.gapBefore)
        val beforeFirst = scanMemoryCoveragePage(0, listOf(later)) { 18L }
        assertEquals(0L, beforeFirst.throughMessageId)
        assertEquals(later, beforeFirst.gapBefore)
    }

    private fun validState() {
        coEvery { messages.getContextMemoryForInvalidation(7, "A") } returns null
        coEvery { segments.getRecentForBranch(7, "A", 3) } returns listOf(previous)
        coEvery { messages.getNextStoryContextBatch(7, "A", 10, 2) } returns sources
    }

    @Test fun rejectsDeletedEditedOrNewlyVisibleSources() = runTest {
        validState()
        val alternatives = listOf(sources.drop(1), listOf(sources[0].copy(content = "改写"), sources[1]),
            listOf(sources[0].copy(structuredContentJson = "{\"speech\":\"已改写\"}"), sources[1]),
            listOf(sources[0].copy(id = 13), sources[1]))
        for (current in alternatives) {
            coEvery { messages.getNextStoryContextBatch(7, "A", 10, 2) } returns current
            assertFalse(MemoryCompactionStore.commitValidated(snapshot, candidate, messages, segments))
        }
        coVerify(exactly = 0) { segments.insertCompacted(any()) }
    }

    @Test fun rejectsEarlierInvalidationEvenWhenCurrentSourcesStillMatch() = runTest {
        validState()
        coEvery { messages.getContextMemoryForInvalidation(7, "A") } returns SessionContextMemoryEntity(sessionId = 7, branchId = "A", revision = 1)
        assertFalse(MemoryCompactionStore.commitValidated(snapshot, candidate, messages, segments))
        coVerify(exactly = 0) { segments.insertCompacted(any()) }
    }

    @Test fun rejectsChangedSummaryAndAnotherCompactionWinningTheCursor() = runTest {
        validState()
        for (current in listOf(emptyList(), listOf(previous.copy(summary = "新概览")), listOf(candidate.copy(id = 3), previous))) {
            coEvery { segments.getRecentForBranch(7, "A", 3) } returns current
            assertFalse(MemoryCompactionStore.commitValidated(snapshot, candidate, messages, segments))
        }
        coVerify(exactly = 0) { segments.nextSegmentIndex(any(), any()) }
    }

    @Test fun savesUsingCurrentBranchIndexOnlyAfterBoundedValidation() = runTest {
        validState()
        coEvery { segments.nextSegmentIndex(7, "A") } returns 4
        assertTrue(MemoryCompactionStore.commitValidated(snapshot, candidate, messages, segments))
        coVerify(exactly = 1) { messages.getNextStoryContextBatch(7, "A", 10, 2) }
        coVerify(exactly = 1) { segments.insertCompacted(candidate.copy(segmentIndex = 4)) }
        coVerify(exactly = 0) { segments.insert(any()) }
    }

    @Test fun historicalGapOnlyCommitsWhileItsBoundaryAndOriginalMessagesStillMatch() = runTest {
        val next = SessionMemorySegmentEntity(id = 3, sessionId = 7, branchId = "A",
            startMessageId = 20, endMessageId = 30, summary = "后段")
        val gapSources = listOf(MessageEntity(id = 11, sessionId = 7, content = "遗漏原文"),
            MessageEntity(id = 12, sessionId = 7, content = "遗漏后文"))
        val gapSnapshot = snapshot.copy(sources = gapSources, cursorAfterMessageId = 10,
            nextCoveredSegment = next, contextSegments = listOf(previous))
        val gapCandidate = candidate.copy(startMessageId = 11, endMessageId = 12)
        validState()
        coEvery { segments.getCoverageAfter(7, "A", 10, 1) } returns listOf(next)
        coEvery { segments.getRecentBefore(7, "A", 10, 3) } returns listOf(previous)
        coEvery { messages.getStoryContextBetween(7, "A", 10, 20, 2) } returns gapSources
        coEvery { segments.nextSegmentIndex(7, "A") } returns 4

        assertTrue(MemoryCompactionStore.commitValidated(gapSnapshot, gapCandidate, messages, segments))
        coVerify(exactly = 1) { segments.insertCompacted(gapCandidate.copy(segmentIndex = 4)) }
        coVerify(exactly = 0) { messages.getNextStoryContextBatch(7, "A", 10, 2) }

        coEvery { messages.getStoryContextBetween(7, "A", 10, 20, 2) } returns gapSources.drop(1)
        assertFalse(MemoryCompactionStore.commitValidated(gapSnapshot, gapCandidate, messages, segments))
        coEvery { messages.getStoryContextBetween(7, "A", 10, 20, 2) } returns gapSources
        coEvery { segments.getCoverageAfter(7, "A", 10, 1) } returns listOf(next.copy(startMessageId = 19))
        assertFalse(MemoryCompactionStore.commitValidated(gapSnapshot, gapCandidate, messages, segments))
        coEvery { segments.getCoverageAfter(7, "A", 10, 1) } returns listOf(next)
        coEvery { segments.getRecentBefore(7, "A", 10, 3) } returns emptyList()
        assertFalse(MemoryCompactionStore.commitValidated(gapSnapshot, gapCandidate, messages, segments))
        coVerify(exactly = 1) { segments.insertCompacted(any()) }
    }
}
