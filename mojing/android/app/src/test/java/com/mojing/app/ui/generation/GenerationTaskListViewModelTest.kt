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
        val vm = GenerationTaskListViewModel(dao, processor)
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
        val vm = GenerationTaskListViewModel(dao, processor)
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

    @Test fun pauseFailureDoesNotPretendQueuePausedAndCanRetry() = runTest {
        val paused = MutableStateFlow(false)
        val processor = mockk<GenerationQueueProcessor>()
        every { processor.pausedState } returns paused
        coEvery { processor.pauseAll() } throws IllegalStateException("disk")
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        val vm = GenerationTaskListViewModel(dao, processor)
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
