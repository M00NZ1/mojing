package com.mojing.app.ui.chat

import com.mojing.app.data.MessageEditDraft
import com.mojing.app.data.MessageEditDraftScope
import com.mojing.app.data.MessageEditDraftStore
import com.mojing.app.data.local.branch.BranchVisibilityIndexManager
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.entity.MessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MessageEditDraftControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun openRestoresDraftOnlyWhenSourceFingerprintStillMatches() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val message = MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        val scope = MessageEditDraftScope(2L, "main", 9L)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { dao.getMainMessageById(2L, 9L) } returns message
        coEvery { store.load(scope) } returns MessageEditDraft(
            scope, MessageEditDraftStore.sourceFingerprint(scope, "原文", "{}"), "编辑后", "原文", 3L,
        )
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        assertEquals("编辑后", controller.state.value.content)
        assertTrue(controller.state.value.hasRecoverableDraft)
    }

    @Test
    fun changedSourceIsNeverSilentlyOverwrittenAndMissingSourceKeepsCopyableDraft() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val scope = MessageEditDraftScope(2L, "branch", 9L)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { dao.getVisibleMessageById(2L, "branch", 9L) } returns null
        coEvery { store.load(scope) } returns MessageEditDraft(scope, "old", "未保存内容", "旧原文", 4L)
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "branch", 9L)
        advanceUntilIdle()
        assertEquals("未保存内容", controller.state.value.content)
        assertTrue(controller.state.value.sourceMissing)
        assertTrue(controller.state.value.hasRecoverableDraft)
        coVerify(exactly = 0) { store.clear(any(), any()) }
    }

    @Test
    fun changedSourceRequiresExplicitRestoreBeforeWritingRecoveredText() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val scope = MessageEditDraftScope(2L, "branch", 9L)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val message = MessageEntity(id = 9L, sessionId = 2L, branchId = "branch", content = "新原文")
        coEvery { dao.getVisibleMessageById(2L, "branch", 9L) } returns message
        coEvery { store.load(scope) } returns MessageEditDraft(scope, "old", "旧草稿", "旧原文", 4L)
        coEvery { store.save(any()) } answers { firstArg() }
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "branch", 9L)
        advanceUntilIdle()
        assertEquals("新原文", controller.state.value.content)
        assertTrue(controller.state.value.recoverableContent == "旧草稿")

        controller.restoreRecoveredDraft()
        advanceUntilIdle()
        assertEquals("旧草稿", controller.state.value.content)
        coVerify { store.save(match { it.editedContent == "旧草稿" && it.revision == 5L }) }
    }

    @Test
    fun discardClearsPersistedDraftAfterDirtyEditing() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val scope = MessageEditDraftScope(2L, "main", 9L)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val message = MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { dao.getMainMessageById(2L, 9L) } returns message
        coEvery { store.load(scope) } returns null
        coEvery { store.save(any()) } answers { firstArg() }
        coEvery { store.clear(scope, 1L) } returns true
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("编辑后")
        advanceUntilIdle()
        assertTrue(controller.discard(1L))
        coVerify { store.clear(scope, 1L) }
    }

    @Test
    fun flushRetriesTheLatestFailedWrite() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val message = MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        val scope = MessageEditDraftScope(2L, "main", 9L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns message
        coEvery { store.load(scope) } returns null
        coEvery { store.save(any()) } throws IOException("disk") andThenAnswer { args[0] as MessageEditDraft }
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("编辑后")
        advanceUntilIdle()
        assertTrue(controller.flush())
        coVerify(exactly = 2) { store.save(any()) }
    }

    @Test
    fun cleanEditorCanDiscardRevisionZeroWithoutTouchingStore() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val scope = MessageEditDraftScope(2L, "main", 9L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns
            MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { store.load(scope) } returns null
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        assertTrue(controller.discard(0L))
        coVerify(exactly = 0) { store.clear(any(), any()) }
    }

    @Test
    fun twentyThousandCharacterSourceIsRebuiltOffTheUiStateOwner() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val body = "长".repeat(20_000)
        val scope = MessageEditDraftScope(2L, "main", 9L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns
            MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = body)
        coEvery { store.load(scope) } returns null
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        assertEquals(body, controller.state.value.content)
    }

    @Test
    fun committedMessageKeepsLockedStateWhenDraftClearFails() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val scope = MessageEditDraftScope(2L, "main", 9L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns
            MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { store.load(scope) } returns null
        coEvery { store.save(any()) } answers { firstArg() }
        coEvery { store.clear(scope, 1L) } throws IOException("clear failed")
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("编辑后")
        advanceUntilIdle()
        assertFalse(controller.markSaved(1L))
        assertTrue(controller.state.value.hasRecoverableDraft)
    }

    @Test
    fun markSavedCanRetryCleanupWithoutChangingCommittedRevision() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val scope = MessageEditDraftScope(2L, "main", 9L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns
            MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { store.load(scope) } returns null
        coEvery { store.save(any()) } answers { firstArg() }
        coEvery { store.clear(scope, 1L) } throws IOException("clear failed") andThen true
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("编辑后")
        advanceUntilIdle()
        assertFalse(controller.markSaved(1L))
        assertTrue(controller.markSaved(1L))
        assertEquals("原文", controller.state.value.content)
        assertEquals(0L, controller.state.value.revision)
        coVerify(exactly = 2) { store.clear(scope, 1L) }
    }

    @Test
    fun discardResetsMemoryAndPreventsAbandonedDraftFromBeingFlushedAgain() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val scope = MessageEditDraftScope(2L, "main", 9L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns
            MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { store.load(scope) } returns null
        coEvery { store.save(any()) } answers { firstArg() }
        coEvery { store.clear(scope, 1L) } returns true
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("放弃内容")
        advanceUntilIdle()
        assertTrue(controller.discard(1L))
        assertEquals("原文", controller.state.value.content)
        assertEquals(0L, controller.state.value.revision)
        assertEquals(0L, controller.state.value.persistedRevision)
        assertTrue(controller.flush())
        coVerify(exactly = 1) { store.save(any()) }
    }

    @Test
    fun reopeningSameScopeKeepsQueuedDraftAndEditorState() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val scope = MessageEditDraftScope(2L, "main", 9L)
        val message = MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { dao.getMainMessageById(2L, 9L) } returns message
        coEvery { store.load(scope) } returns null
        coEvery { store.save(any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("旧编辑")
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        assertEquals("旧编辑", controller.state.value.content)
        assertEquals(scope, controller.state.value.scope)
    }

    @Test
    fun switchingScopeAfterQueuedWriteFailureKeepsOldScopeForRetry() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val oldScope = MessageEditDraftScope(2L, "main", 9L)
        val newScope = MessageEditDraftScope(2L, "branch-2", 10L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns
            MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { dao.getVisibleMessageById(2L, "branch-2", 10L) } returns
            MessageEntity(id = 10L, sessionId = 2L, branchId = "branch-2", content = "新原文")
        coEvery { store.load(any()) } returns null
        coEvery { store.save(any()) } throws IOException("disk")
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("待保存")
        controller.open(2L, "branch-2", 10L)
        advanceUntilIdle()
        assertEquals(oldScope, controller.state.value.scope)
        assertEquals("待保存", controller.state.value.content)
        assertTrue(controller.state.value.error?.contains("暂存失败") == true)
    }

    @Test
    fun discardRejectsRevisionThatBecameStaleWhileWaiting() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = mockk<MessageEditDraftStore>(relaxed = true)
        val dao = mockk<MessageDao>()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val scope = MessageEditDraftScope(2L, "main", 9L)
        coEvery { dao.getMainMessageById(2L, 9L) } returns
            MessageEntity(id = 9L, sessionId = 2L, branchId = "main", content = "原文")
        coEvery { store.load(scope) } returns null
        coEvery { store.save(any()) } answers { firstArg() }
        val controller = MessageEditDraftController(dao, mockk<BranchVisibilityIndexManager>(relaxed = true), store, dispatcher)
        controller.open(2L, "main", 9L)
        advanceUntilIdle()
        controller.update("第一版")
        advanceUntilIdle()
        controller.update("第二版")
        advanceUntilIdle()
        assertTrue(controller.discard(1L).not())
        coVerify(exactly = 0) { store.clear(any(), any()) }
    }
}
