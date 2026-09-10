package com.mojing.app.ui.generation

import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.domain.generation.GenerationQueueProcessor
import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenerationTaskListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun historySelectionRestoresFromSavedStateAndUpdatesAtomically() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeHistoryPage(51L, 2) } returns flowOf(emptyList())
        every { dao.observeHistoryPage(Long.MAX_VALUE, 1) } returns flowOf(emptyList())
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val saved = androidx.lifecycle.SavedStateHandle(mapOf("generation_browse" to longArrayOf(2, Long.MAX_VALUE, 51)))
        val vm = GenerationTaskListViewModel(dao, processor, mockk(), saved)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()
        assertEquals(2, vm.selectedFilter.value)
        assertEquals(listOf(Long.MAX_VALUE, 51L), vm.historyCursors.value)
        verify(exactly = 1) { dao.observeHistoryPage(51L, 2) }
        vm.selectHistoryFilter(1); runCurrent()
        assertArrayEquals(longArrayOf(1, Long.MAX_VALUE), saved.get<LongArray>("generation_browse"))
        verify(exactly = 0) { dao.observeHistoryPage(51L, 1) }
        vm.showRecent(); runCurrent()
        assertEquals(0, vm.selectedFilter.value)
        assertTrue(vm.historyCursors.value.isEmpty())
        assertArrayEquals(longArrayOf(0), saved.get<LongArray>("generation_browse"))
    }

    @Test fun historyPagingIsBoundedAndCanLeaveFailedPage() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        val dao = mockk<GenerationTaskDao>()
        val rows = (200L downTo 150L).map { id -> com.mojing.app.data.local.entity.GenerationTaskEntity(
            id = id, taskKind = "world_template_prompt_ai", title = "$id", status = "COMPLETED", payloadJson = "{}") }
        every { dao.observeQueueVisible() } returns flowOf(rows.take(1))
        every { dao.observeHistoryPage(Long.MAX_VALUE, 0) } returns flowOf(rows)
        every { dao.observeHistoryPage(151L, 0) } returns kotlinx.coroutines.flow.flow { throw IllegalStateException("read failed") }
        every { dao.observeHistoryPage(Long.MAX_VALUE, 2) } returns flowOf(rows.takeLast(1))
        val vm = GenerationTaskListViewModel(dao, processor, mockk())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()
        vm.showHistory(); runCurrent()
        assertEquals(50, vm.tasks.value.size)
        assertTrue(vm.hasOlder.value)
        vm.olderPage(); vm.olderPage(); runCurrent()
        assertEquals(2, vm.historyCursors.value.size)
        assertNotNull(vm.loadError.value)
        vm.selectHistoryFilter(2); runCurrent()
        assertEquals(listOf(Long.MAX_VALUE), vm.historyCursors.value)
        assertNull(vm.loadError.value)
        assertEquals(listOf(150L), vm.tasks.value.map { it.id })
        assertFalse(vm.hasOlder.value)
        vm.showRecent(); runCurrent()
        assertEquals(listOf(200L), vm.tasks.value.map { it.id })
        assertTrue(vm.historyCursors.value.isEmpty())
    }

    @Test fun resultLookupBlocksDuplicateNavigationAndKeepsMissingRecord() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val resolver = mockk<com.mojing.app.domain.generation.GenerationResultResolver>()
        val gate = kotlinx.coroutines.CompletableDeferred<com.mojing.app.domain.generation.GenerationResultTarget?>()
        coEvery { resolver.resolve(any()) } coAnswers { gate.await() }
        val vm = GenerationTaskListViewModel(dao, processor, resolver)
        val task = com.mojing.app.data.local.entity.GenerationTaskEntity(id = 9L, title = "角色生成",
            taskKind = "character_persona_ai", payloadJson = "{}", status = "COMPLETED")
        val opened = mutableListOf<com.mojing.app.domain.generation.GenerationResultTarget>()
        vm.openResult(task, opened::add); vm.openResult(task, opened::add)
        runCurrent()
        assertEquals(9L, vm.openingResultId.value)
        coVerify(exactly = 1) { resolver.resolve(task) }
        gate.complete(null)
        advanceUntilIdle()
        assertTrue(opened.isEmpty())
        assertNull(vm.openingResultId.value)
        assertEquals("生成内容已不存在或未关联，记录仍保留", vm.snackbar.value)
        coEvery { resolver.resolve(task) } returns com.mojing.app.domain.generation.GenerationResultTarget.Character(7L)
        vm.openResult(task, opened::add)
        advanceUntilIdle()
        assertEquals(listOf(com.mojing.app.domain.generation.GenerationResultTarget.Character(7L)), opened)
    }

    @Test fun closedResultLookupCannotNavigateOrClearNextRequest() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val resolver = mockk<com.mojing.app.domain.generation.GenerationResultResolver>()
        val oldRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        val nextRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        val target = com.mojing.app.domain.generation.GenerationResultTarget.Character(7L)
        var reads = 0
        coEvery { resolver.resolve(any()) } coAnswers {
            if (++reads == 1) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldRead.await() }
            else nextRead.await()
            target
        }
        val vm = GenerationTaskListViewModel(dao, processor, resolver)
        val task = com.mojing.app.data.local.entity.GenerationTaskEntity(id = 9L,
            title = "角色生成", taskKind = "character_persona_ai", payloadJson = "{}", status = "COMPLETED")
        val opened = mutableListOf<com.mojing.app.domain.generation.GenerationResultTarget>()
        vm.openResult(task, opened::add)
        runCurrent()
        vm.cancelResultLookup()
        assertNull(vm.openingResultId.value)
        vm.openResult(task, opened::add)
        runCurrent()
        oldRead.complete(Unit)
        runCurrent()
        assertTrue(opened.isEmpty())
        assertEquals(9L, vm.openingResultId.value)
        nextRead.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(target), opened)
        assertNull(vm.openingResultId.value)
        assertNull(vm.snackbar.value)
    }

    @Test fun resultErrorsStayWithDetailAndAllowRetry() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val resolver = mockk<com.mojing.app.domain.generation.GenerationResultResolver>()
        coEvery { resolver.resolve(any()) } throws IllegalStateException("read failed")
        val vm = GenerationTaskListViewModel(dao, processor, resolver)
        val task = com.mojing.app.data.local.entity.GenerationTaskEntity(id = 9L,
            title = "角色生成", taskKind = "character_persona_ai", payloadJson = "{}", status = "COMPLETED")
        val errors = mutableListOf<String>()
        var opened = 0
        vm.openResult(task, { opened++ }, errors::add)
        advanceUntilIdle()
        assertEquals(listOf("生成内容暂时无法打开，请重试"), errors)
        assertNull(vm.snackbar.value)
        assertNull(vm.openingResultId.value)
        coEvery { resolver.resolve(any()) } returns null
        vm.openResult(task, { opened++ }, errors::add)
        advanceUntilIdle()
        assertEquals("生成内容已不存在或未关联，记录仍保留", errors.last())
        coEvery { resolver.resolve(any()) } returns com.mojing.app.domain.generation.GenerationResultTarget.Character(7L)
        vm.openResult(task, { opened++ }, errors::add)
        advanceUntilIdle()
        assertEquals(1, opened)
    }

    @Test fun readFailureRetainsRowsAndRetryRestartsOnlyTheSubscription() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        val dao = mockk<GenerationTaskDao>()
        val task = com.mojing.app.data.local.entity.GenerationTaskEntity(id = 9L,
            title = "原有记录", taskKind = "encyclopedia_entries", payloadJson = "{}",
            status = com.mojing.app.data.local.entity.GenerationTaskStatus.COMPLETED)
        var reads = 0
        every { dao.observeQueueVisible() } returns kotlinx.coroutines.flow.flow {
            reads++
            if (reads == 1) {
                emit(listOf(task))
                throw IllegalStateException("read unavailable")
            }
            emit(emptyList())
        }
        val vm = GenerationTaskListViewModel(dao, processor, mockk(relaxed = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()
        assertEquals(listOf(task), vm.tasks.value)
        assertFalse(vm.loading.value)
        assertEquals("生成记录读取失败，请重试", vm.loadError.value)
        assertEquals(1, reads)
        vm.retryLoad(); vm.retryLoad()
        runCurrent()
        assertEquals(2, reads)
        assertTrue(vm.tasks.value.isEmpty())
        assertFalse(vm.loading.value)
        assertNull(vm.loadError.value)
        coVerify(exactly = 0) { processor.resumeAll() }
        coVerify(exactly = 0) { processor.requeueFailedTask(any()) }
    }

    @Test fun retryTracksOnlyItsTaskAndReleasesOnFailure() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        every { processor.resolveRetryTotalForUi(any()) } returns 5
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { processor.requeueFailedTask(any()) } coAnswers { gate.await() }
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val vm = GenerationTaskListViewModel(dao, processor, mockk(relaxed = true))
        val task = com.mojing.app.data.local.entity.GenerationTaskEntity(id = 7L,
            taskKind = "encyclopedia_entries", title = "测试", payloadJson = "{}",
            status = com.mojing.app.data.local.entity.GenerationTaskStatus.FAILED)
        vm.retryFailedTask(task)
        assertEquals(setOf(7L), vm.retryingTaskIds.value)
        val oldMessage = vm.snackbar.value!!
        vm.retryFailedTask(task)
        vm.consumeSnackbar(oldMessage)
        assertEquals("正在重新排队…", vm.snackbar.value)
        runCurrent()
        coVerify(exactly = 1) { processor.requeueFailedTask(any()) }
        gate.completeExceptionally(IllegalStateException("disk"))
        advanceUntilIdle()
        assertEquals(emptySet<Long>(), vm.retryingTaskIds.value)
        assertEquals("继续失败，请重试", vm.snackbar.value)
        vm.consumeSnackbar("继续失败，请重试")
        assertNull(vm.snackbar.value)
        coEvery { processor.requeueFailedTask(any()) } returns true
        vm.retryFailedTask(task)
        advanceUntilIdle()
        assertTrue(vm.snackbar.value!!.startsWith("已重新排队"))
        assertTrue(vm.retryingTaskIds.value.isEmpty())
    }

    @Test fun cancellationReportsOutcomeAfterCompletionAndCanRetry() = runTest {
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns MutableStateFlow(false)
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { processor.cancelTask(7L) } coAnswers { gate.await() }
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val vm = GenerationTaskListViewModel(dao, processor, mockk(relaxed = true))
        val outcomes = mutableListOf<Boolean>()
        val result: (Boolean) -> Unit = { assertFalse(vm.busy.value); outcomes.add(it) }
        vm.cancelTask(7L, result)
        vm.cancelTask(7L, result)
        runCurrent()
        assertTrue(vm.busy.value)
        assertTrue(outcomes.isEmpty())
        coVerify(exactly = 1) { processor.cancelTask(7L) }
        gate.completeExceptionally(IllegalStateException("write failed"))
        advanceUntilIdle()
        assertEquals(listOf(false), outcomes)
        coEvery { processor.cancelTask(7L) } returns false
        vm.cancelTask(7L, result)
        advanceUntilIdle()
        assertEquals(listOf(false, false), outcomes)
        coEvery { processor.cancelTask(7L) } returns true
        vm.cancelTask(7L, result)
        advanceUntilIdle()
        assertEquals(listOf(false, false, true), outcomes)
        assertEquals("任务已取消，已保存内容保留", vm.snackbar.value)
    }

    @Test fun pauseFailureDoesNotPretendQueuePausedAndCanRetry() = runTest {
        val paused = MutableStateFlow(false)
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns paused
        coEvery { processor.pauseAll() } throws IllegalStateException("disk")
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val vm = GenerationTaskListViewModel(dao, processor, mockk(relaxed = true))
        vm.pauseQueue(); vm.pauseQueue()
        advanceUntilIdle()
        coVerify(exactly = 1) { processor.pauseAll() }
        assertFalse(vm.queuePaused.value)
        assertFalse(vm.busy.value)
        assertEquals("操作未完成，请重试", vm.snackbar.value)
        coEvery { processor.pauseAll() } answers { paused.value = true }
        vm.pauseQueue(); advanceUntilIdle()
        assertTrue(vm.queuePaused.value)
        paused.value = false
        assertFalse(vm.queuePaused.value)
    }
}
