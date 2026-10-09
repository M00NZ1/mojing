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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
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
