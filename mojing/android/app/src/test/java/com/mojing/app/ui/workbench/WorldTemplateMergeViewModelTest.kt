package com.mojing.app.ui.workbench

import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.LegacyWorldMappingDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.MergeWorldTemplateUseCase
import com.mojing.app.domain.usecase.PromoteWorldTemplateUseCase
import com.mojing.app.domain.usecase.WorldMergeField
import com.mojing.app.domain.usecase.WorldTemplateMergePreview
import com.mojing.app.domain.usecase.WorldTemplateMergeResult
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorldTemplateMergeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var encyclopediaDao: EncyclopediaDao
    private lateinit var mappingDao: LegacyWorldMappingDao
    private lateinit var merge: MergeWorldTemplateUseCase
    private lateinit var promote: PromoteWorldTemplateUseCase

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        encyclopediaDao = mockk(relaxed = true)
        mappingDao = mockk(relaxed = true)
        merge = mockk(relaxed = true)
        promote = mockk(relaxed = true)
        coEvery { encyclopediaDao.getCharacterFilterPage(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { mappingDao.getByTemplateId(any()) } returns null
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel() = WorldTemplateMergeViewModel(encyclopediaDao, mappingDao, merge, promote)

    private fun preview(targetId: Long) = WorldTemplateMergePreview(
        templateId = 7L,
        targetWorld = EncyclopediaEntity(id = targetId, name = "世界$targetId"),
        template = WorldTemplateEntity(id = 7L, label = "模板"),
        sourceToken = "source",
        targetToken = "target$targetId",
        loreTotal = 0,
        loreToAdd = 0,
        loreConflicts = 0,
    )

    @Test fun reopeningSameTemplateResetsPreviewAndFields() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { merge.preview(7L, 3L) } returns preview(3L)
        vm.start(7L)
        vm.selectWorld(3L)
        advanceUntilIdle()
        vm.toggleField(WorldMergeField.DESCRIPTION)
        vm.start(7L)
        advanceUntilIdle()
        assertNull(vm.state.value.preview)
        assertFalse(vm.state.value.selectedFields.contains(WorldMergeField.DESCRIPTION))
    }

    @Test fun mappingFailureIsVisibleAndRetryable() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { mappingDao.getByTemplateId(7L) } throws IllegalStateException("busy") andThen null
        vm.start(7L)
        advanceUntilIdle()
        assertEquals("busy", vm.state.value.mappingError)
        vm.retryMapping()
        advanceUntilIdle()
        assertNull(vm.state.value.mappingError)
    }

    @Test fun selectingAnotherTargetDropsLatePreview() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { merge.preview(7L, 1L) } coAnswers { kotlinx.coroutines.delay(100); preview(1L) }
        coEvery { merge.preview(7L, 2L) } returns preview(2L)
        vm.start(7L)
        vm.selectWorld(1L)
        vm.selectWorld(2L)
        advanceUntilIdle()
        assertEquals(2L, vm.state.value.preview?.targetWorld?.id)
    }

    @Test fun repeatedApplyCapturesFieldsAndRunsOnce() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { merge.preview(7L, 3L) } returns preview(3L)
        coEvery { merge.apply(any(), any()) } returns WorldTemplateMergeResult(EncyclopediaEntity(id = 3L, name = "世界3"), 0, 0)
        vm.start(7L)
        vm.selectWorld(3L)
        advanceUntilIdle()
        vm.toggleField(WorldMergeField.DESCRIPTION)
        vm.apply {}
        vm.apply {}
        advanceUntilIdle()
        coVerify(exactly = 1) { merge.apply(any(), setOf(WorldMergeField.DESCRIPTION)) }
    }

    @Test fun failedOlderPageRetriesItsCursorAndRetainsFieldChoices() = runTest(dispatcher) {
        val vm = viewModel()
        val first = (1L..25L).map { com.mojing.app.domain.usecase.WorldTemplateMergeLoreRow(it, "条目$it", "world", false) }
        val second = (25L..49L).map { com.mojing.app.domain.usecase.WorldTemplateMergeLoreRow(it, "条目$it", "world", false) }
        coEvery { merge.preview(7L, 3L) } returns preview(3L)
        coEvery { merge.lorePage(any(), 0L, 25) } returns first
        coEvery { merge.lorePage(any(), 24L, 25) } throws IllegalStateException("old page unavailable") andThen second
        vm.start(7L); vm.selectWorld(3L); advanceUntilIdle()
        vm.toggleField(WorldMergeField.DESCRIPTION)
        vm.nextLorePage(); advanceUntilIdle()
        assertEquals(24L, vm.state.value.lore.last().id)
        vm.retryLore(); advanceUntilIdle()
        assertEquals(25L, vm.state.value.lore.first().id)
        assertEquals(48L, vm.state.value.lore.last().id)
        assertEquals(setOf(WorldMergeField.DESCRIPTION), vm.state.value.selectedFields)
        coVerify(exactly = 2) { merge.lorePage(any(), 24L, 25) }
    }

    @Test fun previewCanTraverseBeyondThreePagesWithoutKeepingEarlierRows() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { merge.preview(7L, 3L) } returns preview(3L)
        coEvery { merge.lorePage(any(), any(), 25) } coAnswers {
            val cursor = arg<Long>(1)
            (cursor + 1..cursor + 25).map { com.mojing.app.domain.usecase.WorldTemplateMergeLoreRow(it, "条目$it", "world", false) }
        }
        vm.start(7L); vm.selectWorld(3L); advanceUntilIdle()
        repeat(5) { vm.nextLorePage(); advanceUntilIdle() }
        assertEquals(5, vm.state.value.lorePageIndex)
        assertEquals(24, vm.state.value.lore.size)
        assertEquals(121L, vm.state.value.lore.first().id)
    }

    @Test fun failedApplyCannotResubmitStalePreviewUntilReloaded() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { merge.preview(7L, 3L) } returns preview(3L)
        coEvery { merge.apply(any(), any()) } throws com.mojing.app.domain.usecase.WorldTemplateMergePreviewStaleException()
        vm.start(7L); vm.selectWorld(3L); advanceUntilIdle()
        vm.apply {}; advanceUntilIdle(); vm.apply {}
        coVerify(exactly = 1) { merge.apply(any(), any()) }
        assertFalse(vm.state.value.previewValid)
        vm.retryPreview(); advanceUntilIdle()
        org.junit.Assert.assertTrue(vm.state.value.previewValid)
    }

    @Test fun closeWaitsForCancelledNonCancellableWorkAndSuppressesLateSuccess() = runTest(dispatcher) {
        val vm = viewModel()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var navigated = false
        coEvery { merge.preview(7L, 3L) } returns preview(3L)
        coEvery { merge.apply(any(), any()) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                started.complete(Unit); release.await()
                WorldTemplateMergeResult(EncyclopediaEntity(id = 3L, name = "世界3"), 0, 0)
            }
        }
        vm.start(7L); vm.selectWorld(3L); advanceUntilIdle()
        vm.apply { navigated = true }; started.await()
        val closing = launch { vm.cancelAndJoin() }
        runCurrent()
        assertFalse(closing.isCompleted)
        release.complete(Unit); closing.join(); advanceUntilIdle()
        assertFalse(navigated)
        assertFalse(vm.state.value.applying)
    }
}
