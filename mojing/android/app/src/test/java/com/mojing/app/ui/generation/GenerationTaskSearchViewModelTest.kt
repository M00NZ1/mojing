package com.mojing.app.ui.generation

import androidx.lifecycle.SavedStateHandle
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.domain.generation.GenerationQueueProcessor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class GenerationTaskSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun processor(): GenerationQueueProcessor = mockk {
        every { pausedState } returns kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    @Test
    fun rapidKeywordsAreMergedAndChangingTypeRequeriesTheSamePage() = runTest {
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        every { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "最终", null) } returns flowOf(emptyList())
        every { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "最终", "character_persona_ai") } returns flowOf(emptyList())
        val vm = GenerationTaskListViewModel(dao, processor(), mockk(relaxed = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()

        vm.updateSearchQuery("初")
        vm.updateSearchQuery("最终")
        runCurrent()
        advanceTimeBy(249)
        runCurrent()
        io.mockk.verify(exactly = 0) { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "最终", null) }
        advanceTimeBy(1)
        runCurrent()
        io.mockk.verify(exactly = 1) { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "最终", null) }

        vm.selectTaskKind("character_persona_ai")
        runCurrent()
        io.mockk.verify(exactly = 1) {
            dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "最终", "character_persona_ai")
        }
        assertEquals(listOf(Long.MAX_VALUE), vm.historyCursors.value)
    }

    @Test
    fun clearReturnsToRecentAndRestoredStateKeepsSearchAndType() = runTest {
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        every { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 1, "标题", "world_template_prompt_ai") } returns flowOf(emptyList())
        every { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 1, "标题", null) } returns flowOf(emptyList())
        val saved = SavedStateHandle(mapOf(
            "generation_browse" to longArrayOf(1, Long.MAX_VALUE),
            "generation_search" to "标题",
            "generation_task_kind" to "world_template_prompt_ai",
        ))
        val vm = GenerationTaskListViewModel(dao, processor(), mockk(relaxed = true), saved)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()
        assertEquals("标题", vm.searchQuery.value)
        assertEquals("world_template_prompt_ai", vm.selectedTaskKind.value)
        assertEquals(1, vm.selectedFilter.value)

        vm.updateSearchQuery("")
        vm.selectTaskKind(null)
        advanceTimeBy(250)
        runCurrent()
        assertTrue(vm.historyCursors.value.isEmpty())
        assertEquals("", vm.searchQuery.value)
        assertEquals(null, vm.selectedTaskKind.value)
    }

    @Test
    fun lateRowsFromPreviousQueryCannotReplaceCurrentQuery() = runTest {
        val dao = mockk<GenerationTaskDao>()
        val oldRows = MutableSharedFlow<List<com.mojing.app.data.local.entity.GenerationTaskEntity>>()
        val newRows = MutableSharedFlow<List<com.mojing.app.data.local.entity.GenerationTaskEntity>>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        every { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "旧", null) } returns oldRows
        every { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "新", null) } returns newRows
        val vm = GenerationTaskListViewModel(dao, processor(), mockk(relaxed = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()
        vm.updateSearchQuery("旧")
        advanceTimeBy(250)
        runCurrent()
        vm.updateSearchQuery("新")
        advanceTimeBy(250)
        runCurrent()
        val newTask = com.mojing.app.data.local.entity.GenerationTaskEntity(
            id = 2, taskKind = "character_persona_ai", title = "新", status = "COMPLETED", payloadJson = "{}",
        )
        newRows.emit(listOf(newTask))
        runCurrent()
        oldRows.emit(listOf(newTask.copy(id = 1, title = "旧")))
        runCurrent()
        assertEquals(listOf(newTask), vm.tasks.value)
    }

    @Test fun typingPreservesSpacesAndTypeChangeNeverQueriesPreviousKeyword() = runTest {
        val dao = mockk<GenerationTaskDao>()
        every { dao.observeQueueVisible() } returns flowOf(emptyList())
        every { dao.observeHistoryPageFiltered(any(), any(), any(), any()) } returns flowOf(emptyList())
        val vm = GenerationTaskListViewModel(dao, processor(), mockk(relaxed = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()
        vm.updateSearchQuery("旧词"); advanceTimeBy(250); runCurrent()
        vm.updateSearchQuery(" 新词 "); runCurrent()
        vm.selectTaskKind("character_persona_ai"); runCurrent()
        assertEquals(" 新词 ", vm.searchQuery.value)
        io.mockk.verify(exactly = 0) { dao.observeHistoryPageFiltered(any(), any(), "旧词", "character_persona_ai") }
        io.mockk.verify(exactly = 1) { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "新词", "character_persona_ai") }
    }

    @Test fun failedSearchRetainsRowsAndRetriesTheSameSelection() = runTest {
        val dao = mockk<GenerationTaskDao>()
        val task = com.mojing.app.data.local.entity.GenerationTaskEntity(
            id = 9, title = "记录", taskKind = "character_persona_ai", status = "COMPLETED", payloadJson = "{}")
        every { dao.observeQueueVisible() } returns flowOf(listOf(task))
        var attempts = 0
        every { dao.observeHistoryPageFiltered(Long.MAX_VALUE, 0, "记录", null) } returns kotlinx.coroutines.flow.flow {
            if (++attempts == 1) error("read failed")
            emit(listOf(task))
        }
        val vm = GenerationTaskListViewModel(dao, processor(), mockk(relaxed = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect {} }
        runCurrent()
        vm.updateSearchQuery("记录"); advanceTimeBy(250); runCurrent()
        org.junit.Assert.assertNotNull(vm.loadError.value)
        assertEquals(listOf(task), vm.tasks.value)
        vm.retryLoad(); runCurrent()
        assertEquals(2, attempts)
        org.junit.Assert.assertNull(vm.loadError.value)
        assertEquals("记录", vm.searchQuery.value)
        assertEquals(listOf(task), vm.tasks.value)
    }
}
