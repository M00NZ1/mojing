package com.mojing.app.ui.generation

import androidx.lifecycle.SavedStateHandle
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.generation.GenerationResultApplicationPreview
import com.mojing.app.domain.generation.GenerationResultSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationTaskSnapshotApplicationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun stalePreviewKeepsSheetAndRefreshReReadsCurrentContent() = runTest {
        val processor = processor()
        val first = preview(taskId = 7L, current = "生成前")
        val refreshed = preview(taskId = 7L, current = "用户刚修改")
        coEvery { processor.previewResult(7L) } returnsMany listOf(first, refreshed)
        coEvery { processor.applyResult(7L, "生成前", null, null) } returns
            GenerationQueueProcessor.ResultApplyOutcome.StalePreview
        val vm = viewModel(processor)

        vm.previewSnapshot(7L)
        advanceUntilIdle()
        vm.applySnapshot()
        advanceUntilIdle()

        assertEquals("内容又有变化，请刷新后重新确认", vm.application.value.error)
        assertFalse(vm.application.value.applying)
        assertEquals(first, vm.application.value.preview)

        vm.previewSnapshot(7L)
        advanceUntilIdle()
        assertEquals(null, vm.application.value.error)
        assertEquals(refreshed, vm.application.value.preview)
        coVerify(exactly = 1) { processor.applyResult(7L, "生成前", null, null) }
    }

    @Test
    fun targetMissingKeepsGeneratedTextAvailableForCopy() = runTest {
        val processor = processor()
        val expected = preview(taskId = 8L)
        coEvery { processor.previewResult(8L) } returns expected
        coEvery { processor.applyResult(8L, "当前", null, null) } returns
            GenerationQueueProcessor.ResultApplyOutcome.TargetMissing
        val vm = viewModel(processor)

        vm.previewSnapshot(8L)
        advanceUntilIdle()
        assertEquals(expected, vm.application.value.preview)
        assertTrue(vm.application.value.preview!!.targetExists)
        vm.applySnapshot()
        advanceUntilIdle()

        assertEquals("目标已删除，结果仍可复制", vm.application.value.error)
        assertEquals("生成结果", (vm.application.value.preview!!.result as GenerationResultSnapshot.CharacterPersona).personaPrompt)
        coVerify(exactly = 1) { processor.applyResult(8L, "当前", null, null) }
    }

    @Test
    fun applyExceptionCanRecoverAfterRefreshingPreview() = runTest {
        val processor = processor()
        val expected = preview(taskId = 9L, current = "当前")
        coEvery { processor.previewResult(9L) } returns expected
        coEvery { processor.applyResult(9L, "当前", null, null) } throws IllegalStateException("disk") andThen
            GenerationQueueProcessor.ResultApplyOutcome.Applied
        val vm = viewModel(processor)

        vm.previewSnapshot(9L)
        advanceUntilIdle()
        vm.applySnapshot()
        advanceUntilIdle()
        assertEquals("应用未完成，原数据与结果仍保留；请刷新后重试", vm.application.value.error)
        assertFalse(vm.application.value.applying)

        vm.previewSnapshot(9L)
        advanceUntilIdle()
        vm.applySnapshot()
        advanceUntilIdle()
        assertEquals(SnapshotApplicationState(), vm.application.value)
        assertEquals("AI 结果已应用", vm.snackbar.value)
        coVerify(exactly = 2) { processor.applyResult(9L, "当前", null, null) }
    }

    @Test
    fun doubleApplyWhileRequestIsInFlightCallsProcessorOnce() = runTest {
        val processor = processor()
        val gate = CompletableDeferred<GenerationQueueProcessor.ResultApplyOutcome>()
        val expected = preview(taskId = 10L, current = "当前")
        coEvery { processor.previewResult(10L) } returns expected
        coEvery { processor.applyResult(10L, "当前", null, null) } coAnswers { gate.await() }
        val vm = viewModel(processor)

        vm.previewSnapshot(10L)
        advanceUntilIdle()
        vm.applySnapshot()
        vm.applySnapshot()
        runCurrent()
        assertTrue(vm.application.value.applying)
        coVerify(exactly = 1) { processor.applyResult(10L, "当前", null, null) }

        gate.complete(GenerationQueueProcessor.ResultApplyOutcome.Applied)
        advanceUntilIdle()
        assertEquals(SnapshotApplicationState(), vm.application.value)
    }

    @Test
    fun dismissWhileApplyingIsIgnoredUntilApplyFinishes() = runTest {
        val processor = processor()
        val gate = CompletableDeferred<GenerationQueueProcessor.ResultApplyOutcome>()
        coEvery { processor.previewResult(11L) } returns preview(taskId = 11L, current = "当前")
        coEvery { processor.applyResult(11L, "当前", null, null) } coAnswers { gate.await() }
        val vm = viewModel(processor)

        vm.previewSnapshot(11L)
        advanceUntilIdle()
        vm.applySnapshot()
        runCurrent()
        vm.dismissSnapshot()
        assertTrue(vm.application.value.applying)
        assertEquals(11L, vm.application.value.taskId)

        gate.complete(GenerationQueueProcessor.ResultApplyOutcome.Applied)
        advanceUntilIdle()
        assertEquals(SnapshotApplicationState(), vm.application.value)
    }

    private fun processor(): GenerationQueueProcessor = mockk<GenerationQueueProcessor>().also {
        every { it.pausedState } returns MutableStateFlow(false)
    }

    private fun viewModel(processor: GenerationQueueProcessor): GenerationTaskListViewModel {
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        return GenerationTaskListViewModel(dao, processor, mockk(), SavedStateHandle())
    }

    private fun preview(
        taskId: Long,
        current: String? = "当前",
        targetExists: Boolean = true,
    ) = GenerationResultApplicationPreview(
        taskId = taskId,
        result = GenerationResultSnapshot.CharacterPersona("生成结果"),
        currentPersona = current,
        targetExists = targetExists,
        alreadyApplied = false,
    )
}
