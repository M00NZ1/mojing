package com.mojing.app.ui.workbench

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.WorldLoreEntryDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.remote.BackendAssetsApi
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.domain.generation.GenerationQueueProcessor
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TemplateEditViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        templateDao: WorldTemplateDao,
        activeTasks: Flow<List<GenerationTaskEntity>> = flowOf(emptyList()),
    ): TemplateEditViewModel {
        val queue = mockk<GenerationQueueProcessor>(relaxed = true)
        every { queue.observeActiveForTemplate(any()) } returns activeTasks
        return TemplateEditViewModel(
            worldMappingDao = io.mockk.mockk { io.mockk.coEvery { getByTemplateId(any()) } returns null },
            templateDao = templateDao,
            loreEntryDao = mockk<WorldLoreEntryDao>(relaxed = true),
            backendWorldsApi = mockk<Lazy<BackendWorldsApi>>(relaxed = true),
            backendAssetsApi = mockk<BackendAssetsApi>(relaxed = true),
            secureStorage = mockk<SecureStorage>(relaxed = true),
            generationQueueProcessor = queue,
        )
    }

    @Test
    fun existingTemplateTracksRealDraftChangesAndClearsAfterSave() = runTest(dispatcher) {
        val original = WorldTemplateEntity(
            id = 7L,
            templateId = "rain-city",
            label = "雨城",
            summary = "旧摘要",
        )
        val saved = original.copy(label = "雨夜城")
        val dao = mockk<WorldTemplateDao> {
            coEvery { getById(7L) } returnsMany listOf(original, saved)
            coEvery { upsert(any()) } returns 7L
        }
        val viewModel = createViewModel(dao)

        viewModel.load(7L)
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)

        viewModel.updateLabel("雨夜城")
        assertTrue(viewModel.state.value.isDirty)
        viewModel.updateLabel("雨城")
        assertFalse(viewModel.state.value.isDirty)

        viewModel.updateLabel("雨夜城")
        viewModel.save()
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun newTemplateOnlyWarnsAfterTheDraftActuallyChanges() = runTest(dispatcher) {
        val dao = mockk<WorldTemplateDao>(relaxed = true)
        val viewModel = createViewModel(dao)

        viewModel.load(0L)
        assertFalse(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)

        viewModel.updateSummary("世界摘要")
        assertTrue(viewModel.state.value.isDirty)
        viewModel.updateSummary("")
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun missingExistingTemplateShowsLoadErrorInsteadOfOpeningANewDraft() = runTest(dispatcher) {
        val dao = mockk<WorldTemplateDao> {
            coEvery { getById(9L) } returns null
        }
        val viewModel = createViewModel(dao)

        viewModel.load(9L)

        assertTrue(viewModel.state.value.isLoaded)
        assertFalse(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
        assertNotNull(viewModel.state.value.loadError)
    }

    @Test
    fun saveKeepsDraftChangesMadeWhilePersistenceIsInFlight() = runTest(dispatcher) {
        val original = WorldTemplateEntity(id = 7L, templateId = "rain-city", label = "雨城")
        val persisted = original.copy(label = "雨夜城")
        val saveRelease = CompletableDeferred<Unit>()
        val dao = mockk<WorldTemplateDao> {
            coEvery { getById(7L) } returnsMany listOf(original, persisted)
            coEvery { upsert(any()) } coAnswers {
                saveRelease.await()
                7L
            }
        }
        val viewModel = createViewModel(dao)

        viewModel.load(7L)
        viewModel.updateLabel("雨夜城")
        viewModel.save()
        assertTrue(viewModel.state.value.isSaving)

        viewModel.updateLabel("雨夜城·续")
        saveRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals("雨夜城·续", viewModel.state.value.label)
        assertTrue(viewModel.state.value.isPersisted)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun aiCompletionKeepsOtherDraftChangesDirty() = runTest(dispatcher) {
        val original = WorldTemplateEntity(
            id = 7L,
            templateId = "rain-city",
            label = "雨城",
            summary = "旧摘要",
            worldPrompt = "旧设定",
        )
        val completed = original.copy(summary = "补全摘要", worldPrompt = "补全设定")
        val tasks = MutableStateFlow<List<GenerationTaskEntity>>(emptyList())
        val dao = mockk<WorldTemplateDao> {
            coEvery { getById(7L) } returnsMany listOf(original, completed)
        }
        val viewModel = createViewModel(dao, tasks)

        viewModel.load(7L)
        tasks.value = listOf(
            GenerationTaskEntity(
                taskKind = "world_template_prompt_ai",
                title = "补全世界设定",
                status = "RUNNING",
                payloadJson = "{}",
                targetWorldTemplateId = 7L,
            ),
        )
        advanceUntilIdle()
        viewModel.updateLabel("雨夜城")
        tasks.value = emptyList()
        advanceUntilIdle()

        assertEquals("雨夜城", viewModel.state.value.label)
        assertEquals("补全摘要", viewModel.state.value.summary)
        assertEquals("补全设定", viewModel.state.value.worldPrompt)
        assertTrue(viewModel.state.value.isPersisted)
        assertTrue(viewModel.state.value.isDirty)
    }
    @Test
    fun aiCompletionPreservesEditedSummaryAndPromptFields() = runTest(dispatcher) {
        for (editPrompt in listOf(false, true)) {
            val original = WorldTemplateEntity(id = 7, templateId = "rain-city", label = "雨城", summary = "旧摘要", worldPrompt = "旧设定")
            val completed = original.copy(summary = "补全摘要", worldPrompt = "补全设定")
            val tasks = MutableStateFlow<List<GenerationTaskEntity>>(emptyList())
            val dao = mockk<WorldTemplateDao> { coEvery { getById(7) } returnsMany listOf(original, completed) }
            val vm = createViewModel(dao, tasks)
            vm.load(7)
            tasks.value = listOf(GenerationTaskEntity(taskKind = "world_template_prompt_ai", title = "补全", status = "RUNNING", payloadJson = "{}", targetWorldTemplateId = 7))
            advanceUntilIdle()
            vm.updateSummary("手动摘要")
            if (editPrompt) vm.updateWorldPrompt("手动正文")
            tasks.value = emptyList()
            advanceUntilIdle()
            assertEquals("手动摘要", vm.state.value.summary)
            assertEquals(if (editPrompt) "手动正文" else "补全设定", vm.state.value.worldPrompt)
            assertTrue(vm.state.value.isDirty)
            vm.updateSummary("补全摘要")
            if (editPrompt) vm.updateWorldPrompt("补全设定")
            assertFalse(vm.state.value.isDirty)
        }
    }

    @Test
    fun duplicateSaveIsRejectedBeforeDispatcherRunsAndFailureAllowsRetry() = runTest(dispatcher) {
        val paused = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(paused)
        val entity = WorldTemplateEntity(id = 7, templateId = "rain-city", label = "雨城")
        val dao = mockk<WorldTemplateDao> {
            coEvery { getById(7) } returns entity
            coEvery { upsert(any()) } throws IllegalStateException("save failed")
        }
        val vm = createViewModel(dao)
        vm.load(7)
        advanceUntilIdle()
        vm.updateSummary("保留草稿")
        vm.save()
        vm.save()
        assertTrue(vm.state.value.isSaving)
        advanceUntilIdle()
        io.mockk.coVerify(exactly = 1) { dao.upsert(any()) }
        assertFalse(vm.state.value.isSaving)
        assertEquals("保留草稿", vm.state.value.summary)
        coEvery { dao.upsert(any()) } returns 7
        coEvery { dao.getById(7) } returns entity.copy(summary = "保留草稿")
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun completionReadFailureKeepsDraftAndRetriesWithoutSavingOrRegenerating() = runTest(dispatcher) {
        val original = WorldTemplateEntity(id = 7, templateId = "rain-city", label = "雨城", worldPrompt = "旧设定")
        val task = GenerationTaskEntity(taskKind = "world_template_prompt_ai", title = "补全", status = "RUNNING", payloadJson = "{}", targetWorldTemplateId = 7)
        val tasks = MutableStateFlow(listOf(task))
        val dao = mockk<WorldTemplateDao> { coEvery { getById(7) } returns original }
        val vm = createViewModel(dao, tasks)
        vm.load(7)
        assertTrue(vm.state.value.isAiCompleting)
        vm.updateSummary("手动摘要")
        coEvery { dao.getById(7) } throws IllegalStateException("read failed")
        tasks.value = emptyList()
        advanceUntilIdle()
        assertFalse(vm.state.value.isAiCompleting)
        assertFalse(vm.state.value.isRefreshingCompletion)
        assertNotNull(vm.state.value.completionRefreshError)
        assertEquals("手动摘要", vm.state.value.summary)
        vm.save()
        io.mockk.coVerify(exactly = 0) { dao.upsert(any()) }
        vm.retryCompletionRefresh()
        assertNotNull(vm.state.value.completionRefreshError)
        val gate = CompletableDeferred<WorldTemplateEntity>()
        coEvery { dao.getById(7) } coAnswers { gate.await() }
        vm.retryCompletionRefresh()
        vm.retryCompletionRefresh()
        assertTrue(vm.state.value.isRefreshingCompletion)
        vm.updateSummary("读取期间修改")
        gate.complete(original.copy(summary = "生成摘要", worldPrompt = "生成正文"))
        advanceUntilIdle()
        assertEquals(null, vm.state.value.completionRefreshError)
        assertEquals("读取期间修改", vm.state.value.summary)
        assertEquals("生成正文", vm.state.value.worldPrompt)
        assertTrue(vm.state.value.isDirty)
        io.mockk.coVerify(exactly = 4) { dao.getById(7) }
        // A failed result read must not terminate observation of later tasks.
        tasks.value = listOf(task)
        advanceUntilIdle()
        assertTrue(vm.state.value.isAiCompleting)
        tasks.value = emptyList()
        advanceUntilIdle()
        assertFalse(vm.state.value.isAiCompleting)
    }

    @Test
    fun reenteringLoadedEditorKeepsActiveTaskTransition() = runTest(dispatcher) {
        val original = WorldTemplateEntity(id = 7, templateId = "rain-city", label = "雨城")
        val task = GenerationTaskEntity(taskKind = "world_template_prompt_ai", title = "补全", status = "RUNNING", payloadJson = "{}", targetWorldTemplateId = 7)
        val tasks = MutableStateFlow(listOf(task))
        val dao = mockk<WorldTemplateDao> { coEvery { getById(7) } returns original }
        val vm = createViewModel(dao, tasks)
        vm.load(7)
        vm.load(7)
        assertTrue(vm.state.value.isAiCompleting)
        coEvery { dao.getById(7) } returns original.copy(worldPrompt = "完成设定")
        tasks.value = emptyList()
        advanceUntilIdle()
        assertEquals("完成设定", vm.state.value.worldPrompt)
        assertFalse(vm.state.value.isAiCompleting)
    }

    @Test
    fun olderRetryCannotOverwriteANewerCompletion() = runTest(dispatcher) {
        val original = WorldTemplateEntity(id = 7, templateId = "rain-city", label = "雨城")
        val task = GenerationTaskEntity(taskKind = "world_template_prompt_ai", title = "补全", status = "RUNNING", payloadJson = "{}", targetWorldTemplateId = 7)
        val tasks = MutableStateFlow(listOf(task))
        val dao = mockk<WorldTemplateDao> { coEvery { getById(7) } returns original }
        val vm = createViewModel(dao, tasks)
        vm.load(7)
        coEvery { dao.getById(7) } throws IllegalStateException("read failed")
        tasks.value = emptyList()
        advanceUntilIdle()
        val oldRead = CompletableDeferred<WorldTemplateEntity>()
        coEvery { dao.getById(7) } coAnswers { oldRead.await() }
        vm.retryCompletionRefresh()
        tasks.value = listOf(task)
        advanceUntilIdle()
        coEvery { dao.getById(7) } returns original.copy(worldPrompt = "最新正文")
        tasks.value = emptyList()
        advanceUntilIdle()
        oldRead.complete(original.copy(worldPrompt = "过时正文"))
        advanceUntilIdle()
        assertEquals("最新正文", vm.state.value.worldPrompt)
        assertFalse(vm.state.value.isRefreshingCompletion)
        assertEquals(null, vm.state.value.completionRefreshError)
    }

}
