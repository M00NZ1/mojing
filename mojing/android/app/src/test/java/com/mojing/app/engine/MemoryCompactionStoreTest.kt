package com.mojing.app.engine

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.domain.engine.MemoryCompactionSnapshot
import com.mojing.app.domain.engine.MemoryCompactionStore
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
}
