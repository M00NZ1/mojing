package com.mojing.app.ui.encyclopedia

import androidx.lifecycle.SavedStateHandle
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaLibraryItem
import com.mojing.app.data.prefs.UiPreferencesRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorldLibraryPagingTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = mockk<EncyclopediaDao>(relaxed = true)
    private fun row(id: Long) = EncyclopediaLibraryItem(id, "同名$id", "", 100L, 200L, "", "", 17, 2, 3, 1)
    private fun vm(handle: SavedStateHandle = SavedStateHandle()): EncyclopediaListViewModel {
        val preferences = mockk<UiPreferencesRepository> { every { encyclopediaListLayout } returns flowOf("list") }
        val tasks = mockk<com.mojing.app.data.local.dao.GenerationTaskDao> { every { observeActiveCount() } returns flowOf(0) }
        return EncyclopediaListViewModel(dao, mockk(relaxed=true), mockk(relaxed=true), mockk(relaxed=true),
            mockk(relaxed=true), mockk(relaxed=true), mockk(relaxed=true), mockk(relaxed=true), preferences, tasks,
            mockk(relaxed=true), handle)
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun criteriaResetPageAndQueryTheFullSet() = runTest(dispatcher) {
        coEvery { dao.getLibraryPage(any(),any(),any(),any(),any(),any(),any(),any(),any()) } returns List(25) { row(it+1L) }
        val v=vm();runCurrent();v.nextPage();runCurrent();assertEquals(1,v.library.value.pageIndex)
        v.setOnlyPinned(true);runCurrent();assertEquals(0,v.library.value.pageIndex)
        v.setSort(WorldLibrarySort.NAME);v.setSearchQuery(" 海港 ");runCurrent()
        coVerify { dao.getLibraryPage("海港",null,null,null,null,25,true,"name",null) }
        assertEquals(17,v.library.value.items.first().entryCount)
    }
    @Test fun failedNextPageRetriesTheSameCursorAndSort() = runTest(dispatcher) {
        coEvery { dao.getLibraryPage(any(),any(),any(),any(),any(),any(),any(),any(),any()) } returns List(25) { row(it+1L) }
        val v=vm();v.setSort(WorldLibrarySort.NAME);runCurrent()
        coEvery { dao.getLibraryPage("",0,0L,200L,24L,25,false,"name","同名24") } throws IllegalStateException()
        v.nextPage();runCurrent();assertNotNull(v.library.value.error);assertEquals(0,v.library.value.pageIndex)
        coEvery { dao.getLibraryPage("",0,0L,200L,24L,25,false,"name","同名24") } returns listOf(row(26))
        v.retryPage();runCurrent();assertEquals(1,v.library.value.pageIndex);assertEquals(26L,v.library.value.items.single().id)
    }
    @Test fun recreationRestoresCriteriaAndPairedPageIdentityWithoutRows() = runTest(dispatcher) {
        coEvery { dao.getLibraryPage(any(),any(),any(),any(),any(),any(),any(),any(),any()) } returns List(25) { row(it+1L) }
        val h=SavedStateHandle();val v=vm(h);v.setOnlyPinned(true);v.setSort(WorldLibrarySort.NAME);v.setSearchQuery("同名");runCurrent();v.nextPage();runCurrent()
        val copy=SavedStateHandle(h.keys().associateWith { h.get<Any>(it) })
        coEvery { dao.getLibraryPage("同名",0,0L,200L,24L,25,true,"name","同名24") } returns listOf(row(26))
        val restored=vm(copy);runCurrent()
        assertEquals(1,restored.library.value.pageIndex);assertTrue(restored.library.value.onlyPinned)
        assertEquals(WorldLibrarySort.NAME,restored.library.value.sort);assertEquals(26L,restored.library.value.items.single().id)
        assertFalse(copy.keys().any { it.contains("items") })
    }
    @Test fun refreshEmptyDeepPagesFallsBackUntilNonEmpty() = runTest(dispatcher) {
        coEvery { dao.getLibraryPage(any(),any(),any(),any(),any(),any(),any(),any(),any()) } returns List(25) { row(it+1L) }
        val v=vm();runCurrent();v.nextPage();runCurrent();v.nextPage();runCurrent()
        coEvery { dao.getLibraryPage(any(),any(),any(),any(),any(),any(),any(),any(),any()) } returnsMany listOf(emptyList(),emptyList(),listOf(row(90)))
        v.refreshCurrentPage();runCurrent();assertEquals(0,v.library.value.pageIndex);assertEquals(90L,v.library.value.items.single().id)
    }
    @Test fun cancelledOldReadCannotReplaceNewCriteria() = runTest(dispatcher) {
        val gate=CompletableDeferred<Unit>()
        coEvery { dao.getLibraryPage(any(),any(),any(),any(),any(),any(),any(),any(),any()) } coAnswers {
            if(!arg<Boolean>(6)) withContext(NonCancellable) { gate.await();listOf(row(1)) } else listOf(row(2))
        }
        val v=vm();runCurrent();v.setOnlyPinned(true);runCurrent();gate.complete(Unit);runCurrent()
        assertTrue(v.library.value.onlyPinned);assertEquals(2L,v.library.value.items.single().id)
    }
    @Test fun malformedSavedPairsStartFromFirstPage() = runTest(dispatcher) {
        val v=vm(SavedStateHandle(mapOf("library_cursors" to longArrayOf(1,2),"library_names" to arrayListOf("x"),"library_page" to 1)))
        runCurrent();assertEquals(0,v.library.value.pageIndex)
    }
}
