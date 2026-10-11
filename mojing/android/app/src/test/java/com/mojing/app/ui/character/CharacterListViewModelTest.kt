package com.mojing.app.ui.character

import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.CharacterListItem
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.DeleteCharacterUseCase
import com.mojing.app.domain.usecase.ImportCharacterUseCase
import com.mojing.app.domain.usecase.SmartImportUseCase
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun createNewDefaultsToUnboundDraft() {
        assertEquals(0L, newCharacterDraft().boundEncyclopediaId)
        assertEquals(7L, newCharacterDraft(7L).boundEncyclopediaId)
    }

    @Test
    fun deletingLastCharacterOnSecondPageFallsBackToPreviousPage() = runTest(dispatcher) {
        val firstPage = List(41) { characterItem(it.toLong() + 1) }
        val secondPage = listOf(characterItem(41L))
        val previousPage = List(40) { characterItem(it.toLong() + 1) }
        val dao = mockk<CharacterDao>(relaxed = true)
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(firstPage, secondPage, emptyList(), previousPage)
        val delete = mockk<DeleteCharacterUseCase>()
        coEvery { delete(any()) } returns Unit
        val vm = createViewModel(dao, delete)
        vm.refreshList(keepVisible = false)
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        vm.delete(41L)
        advanceUntilIdle()
        assertEquals(0, vm.page.value.pageIndex)
        assertEquals(40, vm.page.value.items.size)
    }

    @Test
    fun deletingCharacterWithRemainingSecondPageRowsKeepsPage() = runTest(dispatcher) {
        val firstPage = List(41) { characterItem(it.toLong() + 1) }
        val secondPage = listOf(characterItem(41L), characterItem(42L))
        val refreshedSecondPage = listOf(characterItem(42L))
        val dao = mockk<CharacterDao>(relaxed = true)
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(firstPage, secondPage, refreshedSecondPage)
        val delete = mockk<DeleteCharacterUseCase>()
        coEvery { delete(any()) } returns Unit
        val vm = createViewModel(dao, delete)
        vm.refreshList(keepVisible = false)
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        vm.delete(41L)
        advanceUntilIdle()
        assertEquals(1, vm.page.value.pageIndex)
        assertEquals(42L, vm.page.value.items.single().id)
    }

    @Test
    fun oldDeleteCannotReloadPageWhileRefreshHasResetItsCursors() = runTest(dispatcher) {
        val deleteRelease = CompletableDeferred<Unit>()
        val refreshRelease = CompletableDeferred<Unit>()
        val dao = mockk<CharacterDao>(relaxed = true)
        var reads = 0
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when (++reads) {
                1 -> List(41) { characterItem(it.toLong() + 1) }
                2 -> listOf(characterItem(41L))
                else -> { refreshRelease.await(); listOf(characterItem(42L)) }
            }
        }
        val delete = mockk<DeleteCharacterUseCase>()
        coEvery { delete(41L) } coAnswers { deleteRelease.await() }
        val vm = createViewModel(dao, delete)
        vm.refreshList()
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        vm.delete(41L)
        vm.refreshList(keepVisible = true)
        assertEquals(1, vm.page.value.pageIndex)
        deleteRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, reads)
        refreshRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, vm.page.value.pageIndex)
        assertEquals(42L, vm.page.value.items.single().id)
    }

    @Test
    fun deletionRetainsSearchWorldFilterAndNameSort() = runTest(dispatcher) {
        val dao = mockk<CharacterDao>(relaxed = true)
        val delete = mockk<DeleteCharacterUseCase>()
        coEvery { delete(41L) } returns Unit
        val vm = createViewModel(dao, delete)
        vm.setEncyclopediaFilter(7L, "海港")
        vm.updateSearch("守灯人")
        coEvery { dao.getLibraryNamePage(7L, any(), any(), any(), 41, "守灯人") } returnsMany listOf(
            List(41) { characterItem(it.toLong() + 1) },
            listOf(characterItem(41L), characterItem(42L)),
            listOf(characterItem(42L)),
        )
        vm.setSortOrder(CharacterLibrarySort.NAME)
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        vm.delete(41L)
        advanceUntilIdle()
        assertEquals(1, vm.page.value.pageIndex)
        assertEquals("守灯人", vm.searchQuery.value)
        assertEquals(7L, vm.filterEncyclopediaId.value)
        assertEquals("海港", vm.selectedFilterName.value)
        coVerify(exactly = 2) { dao.getLibraryNamePage(7L, 0, "角色40", 40L, 41, "守灯人") }
    }

    @Test
    fun firstScreenVisibleRefreshesFirstPageOnce() = runTest(dispatcher) {
        val dao = mockk<CharacterDao>(relaxed = true)
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            listOf(characterItem(1L))
        val vm = createViewModel(dao, mockk(relaxed = true))
        vm.onScreenVisible(CharacterLibrarySort.RECOMMENDED)
        advanceUntilIdle()
        assertEquals(0, vm.page.value.pageIndex)
        assertEquals(1L, vm.page.value.items.single().id)
        coVerify(exactly = 1) { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun screenReturnKeepsSecondPageVisibleWhileRefreshingItsCurrentCursor() = runTest(dispatcher) {
        val dao = mockk<CharacterDao>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when (++reads) {
                1 -> List(41) { characterItem(it.toLong() + 1L) }
                2 -> listOf(characterItem(41L), characterItem(42L))
                else -> {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    listOf(characterItem(42L), characterItem(142L))
                }
            }
        }
        val vm = createViewModel(dao, mockk(relaxed = true))
        vm.refreshList(keepVisible = false)
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        try {
            vm.onScreenVisible(CharacterLibrarySort.RECOMMENDED)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(vm.page.value.loading)
            assertEquals(1, vm.page.value.pageIndex)
            assertEquals(41L, vm.page.value.items.first().id)
            assertEquals(42L, vm.page.value.items.last().id)
            coVerify(exactly = 2) {
                dao.getLibraryRecommendedPage(null, 0, 0L, false, 40L, 40L, 41, "")
            }
        } finally {
            release.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(1, vm.page.value.pageIndex)
        assertEquals(42L, vm.page.value.items.first().id)
        assertEquals(142L, vm.page.value.items.last().id)
        assertEquals(3, reads)
    }

    @Test
    fun screenReturnSortChangeResetsToFirstPageWithOneRequest() = runTest(dispatcher) {
        val dao = mockk<CharacterDao>(relaxed = true)
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            List(41) { characterItem(it.toLong() + 1) }
        coEvery { dao.getLibraryRecentPage(any(), any(), any(), any(), any(), any()) } returns
            List(41) { characterItem(it.toLong() + 101) }
        val vm = createViewModel(dao, mockk(relaxed = true))
        vm.refreshList(keepVisible = false)
        advanceUntilIdle()
        vm.onScreenVisible(CharacterLibrarySort.RECENT)
        advanceUntilIdle()
        assertEquals(0, vm.page.value.pageIndex)
        assertEquals(101L, vm.page.value.items.first().id)
        coVerify(exactly = 1) { dao.getLibraryRecentPage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun screenReturnEmptyDeepPageFallsBackToPreviousPage() = runTest(dispatcher) {
        val dao = mockk<CharacterDao>(relaxed = true)
        var reads = 0
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when (++reads) {
                1 -> List(41) { characterItem(it.toLong() + 1) }
                2 -> listOf(characterItem(41L))
                3 -> emptyList()
                else -> List(40) { characterItem(it.toLong() + 1) }
            }
        }
        val vm = createViewModel(dao, mockk(relaxed = true))
        vm.refreshList(keepVisible = false)
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        vm.onScreenVisible(CharacterLibrarySort.RECOMMENDED)
        advanceUntilIdle()
        assertEquals(0, vm.page.value.pageIndex)
        assertFalse(vm.page.value.loading)
        assertEquals(40, vm.page.value.items.size)
    }

    @Test
    fun screenReturnErrorKeepsOldRowsAndRetryLoadsCurrentPage() = runTest(dispatcher) {
        val dao = mockk<CharacterDao>(relaxed = true)
        var reads = 0
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when (++reads) {
                1 -> List(40) { characterItem(it.toLong() + 1) }
                2 -> error("candidate failure")
                else -> listOf(characterItem(999L))
            }
        }
        val vm = createViewModel(dao, mockk(relaxed = true))
        vm.refreshList(keepVisible = false)
        advanceUntilIdle()
        vm.onScreenVisible(CharacterLibrarySort.RECOMMENDED)
        advanceUntilIdle()
        assertEquals(40, vm.page.value.items.size)
        assertEquals("角色加载失败，请重试", vm.page.value.error)
        vm.retryPage()
        advanceUntilIdle()
        assertEquals(999L, vm.page.value.items.single().id)
        assertFalse(vm.page.value.loading)
    }

    @Test
    fun lateOlderRefreshCannotOverwriteNewerRevision() = runTest(dispatcher) {
        val oldRelease = CompletableDeferred<Unit>()
        val dao = mockk<CharacterDao>(relaxed = true)
        var reads = 0
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when (++reads) {
                1 -> listOf(characterItem(1L))
                2 -> withContext(NonCancellable) { oldRelease.await(); listOf(characterItem(2L)) }
                else -> listOf(characterItem(3L))
            }
        }
        val vm = createViewModel(dao, mockk(relaxed = true))
        try {
            vm.refreshList(keepVisible = false)
            advanceUntilIdle()
            vm.onScreenVisible(CharacterLibrarySort.RECOMMENDED)
            vm.onScreenVisible(CharacterLibrarySort.RECOMMENDED)
            advanceUntilIdle()
            assertEquals(3L, vm.page.value.items.single().id)
            oldRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(3L, vm.page.value.items.single().id)
            assertTrue(reads >= 3)
        } finally {
            oldRelease.complete(Unit)
        }
    }

    @Test
    fun resetWhileSecondPageRefreshIsSuspendedFallsBackToFirstPageAndIgnoresLateOldQuery() = runTest(dispatcher) {
        val oldRelease = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val dao = mockk<CharacterDao>(relaxed = true)
        var reads = 0
        coEvery { dao.getLibraryRecommendedPage(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when (++reads) {
                1 -> List(41) { characterItem(it.toLong() + 1) }
                2 -> listOf(characterItem(41L), characterItem(42L))
                3 -> {
                    entered.complete(Unit)
                    withContext(NonCancellable) { oldRelease.await() }
                    listOf(characterItem(900L))
                }
                else -> List(41) { characterItem(it.toLong() + 100L) }
            }
        }
        val vm = createViewModel(dao, mockk(relaxed = true))
        try {
            vm.refreshList(keepVisible = false)
            advanceUntilIdle()
            vm.nextPage()
            advanceUntilIdle()
            vm.refreshList(keepVisible = true)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(vm.page.value.loading)
            assertEquals(1, vm.page.value.pageIndex)
            assertEquals(41L, vm.page.value.items.first().id)
            assertEquals(42L, vm.page.value.items.last().id)
            vm.onScreenVisible(CharacterLibrarySort.RECOMMENDED)
            advanceUntilIdle()
            assertEquals(0, vm.page.value.pageIndex)
            assertEquals(100L, vm.page.value.items.first().id)
            coVerify(exactly = 3) {
                dao.getLibraryRecommendedPage(null, null, null, null, null, null, 41, "")
            }
            coVerify(exactly = 1) {
                dao.getLibraryRecommendedPage(null, 0, 0L, false, 40L, 40L, 41, "")
            }
            oldRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, vm.page.value.pageIndex)
            assertEquals(100L, vm.page.value.items.first().id)
        } finally {
            oldRelease.complete(Unit)
        }
    }

    private fun createViewModel(dao: CharacterDao, delete: DeleteCharacterUseCase) = CharacterListViewModel(
        characterDao = dao, encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true),
        entryDao = mockk<EncyclopediaEntryDao>(relaxed = true), deleteCharacter = delete,
        saveCharacterBinding = mockk<SaveCharacterBindingUseCase>(relaxed = true),
        importCharacter = mockk<ImportCharacterUseCase>(relaxed = true),
        smartImportUseCase = mockk<SmartImportUseCase>(relaxed = true),
        createSessionUseCase = mockk<CreateSessionUseCase>(relaxed = true),
        uiPreferencesRepository = mockk<UiPreferencesRepository> { every { characterListLayout } returns flowOf("list") },
    )

    private fun characterItem(id: Long) = CharacterListItem(
        id = id, name = "角色$id", personaPreview = "", avatarColor = "#000000",
        avatarImagePath = "", cardImagePath = "", boundEncyclopediaId = 0L,
        encyclopediaName = null, pinnedAt = 0L, favorite = false, createdAt = id,
    )
}
