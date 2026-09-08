package com.mojing.app.data.local.dao

import com.mojing.app.data.local.entity.MessageEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessageSummaryInvalidationTest {
    private val queries = mockk<MessageDao>(relaxed = true)
    private val owner = object : MessageDao by queries {
        override suspend fun selectSwipeVariantForBranch(sessionId: Long, branchId: String, gid: String, messageId: Long) =
            super<MessageDao>.selectSwipeVariantForBranch(sessionId, branchId, gid, messageId)
        override suspend fun insertAndSelectSwipeVariant(entity: MessageEntity, branchId: String, targetMessageId: Long) =
            super<MessageDao>.insertAndSelectSwipeVariant(entity, branchId, targetMessageId)
        override suspend fun updateContent(id: Long, content: String) = super<MessageDao>.updateContent(id, content)
    }
    private val old = MessageEntity(id = 8, sessionId = 42, branchId = "main", content = "旧回复", swipeGroupId = "g")
    private val new = old.copy(id = 12, content = "新回复", includeInContext = false)

    @Test fun switchingInEitherDirectionRewindsFromEarlierSelectedSource() = runTest {
        coEvery { queries.getMainSwipeGroupMessages(42, "g") } returns listOf(old, new)
        assertEquals(1, owner.selectSwipeVariantForBranch(42, "main", "g", 12))
        coVerify { queries.deleteMemorySegmentTail(42, "main", 8) }
        coEvery { queries.getMainSwipeGroupMessages(42, "g") } returns listOf(old.copy(includeInContext = false), new.copy(includeInContext = true))
        assertEquals(1, owner.selectSwipeVariantForBranch(42, "main", "g", 8))
        coVerify(exactly = 2) { queries.deleteMemorySegmentTail(42, "main", 8) }
    }

    @Test fun repeatedOrInvalidSelectionDoesNotClearSummaries() = runTest {
        coEvery { queries.getMainSwipeGroupMessages(42, "g") } returns listOf(old, new)
        assertEquals(1, owner.selectSwipeVariantForBranch(42, "main", "g", 8))
        assertEquals(0, owner.selectSwipeVariantForBranch(42, "main", "g", 99))
        coVerify(exactly = 0) { queries.deleteMemorySegmentTail(any(), any(), any()) }
    }

    @Test fun branchSelectionPreservesOtherStorylineRows() = runTest {
        coEvery { queries.getVisibleSwipeGroupMessages(42, "A", "g") } returns listOf(old, new)
        owner.selectSwipeVariantForBranch(42, "A", "g", 12)
        coVerify(exactly = 1) { queries.deleteMemorySegmentTail(42, "A", 8) }
        coVerify(exactly = 0) { queries.deleteMemorySegmentTail(42, "main", any()) }
    }

    @Test fun regeneratedReplyRewindsPreviouslySelectedSource() = runTest {
        coEvery { queries.getByIdInSession(8, 42) } returns old
        coEvery { queries.getMainSwipeGroupMessages(42, "g") } returns listOf(old, new)
        coEvery { queries.insert(any()) } returns 15
        assertEquals(15L, owner.insertAndSelectSwipeVariant(new.copy(id = 0), "main", 8))
        coVerify { queries.deleteMemorySegmentTail(42, "main", 8) }
    }

    @Test fun directContentEditInvalidatesVisibleBranchesAndSkipsNoOp() = runTest {
        val message = old.copy(swipeGroupId = null)
        coEvery { queries.getById(8) } returns message
        coEvery { queries.getStorylinesSeeingSourceMessage(42, "main", 8) } returns listOf("A")
        coEvery { queries.updateContentRaw(8, any(), any(), any()) } returns 1
        owner.updateContent(8, message.content)
        coVerify(exactly = 0) { queries.deleteMemorySegmentTail(any(), any(), any()) }
        owner.updateContent(8, "更正后的事实")
        coVerify { queries.deleteMemorySegmentTail(42, "main", 8) }
        coVerify { queries.deleteMemorySegmentTail(42, "A", 8) }
    }

    @Test fun failedSummaryDeletePropagatesToTransactionOwner() = runTest {
        coEvery { queries.getMainSwipeGroupMessages(42, "g") } returns listOf(old, new)
        coEvery { queries.deleteMemorySegmentTail(any(), any(), any()) } throws IllegalStateException("write failed")
        try { owner.selectSwipeVariantForBranch(42, "main", "g", 12); fail("must abort") }
        catch (_: IllegalStateException) { }
        coVerify(exactly = 0) { queries.invalidateContextMemoryForBranch(any(), any(), any()) }
    }
}
