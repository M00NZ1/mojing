package com.mojing.app.ui.character

import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.domain.usecase.CreateSessionUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
class CharacterDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun relatedStoriesPageRetryPreservesRowsAndReturnsToPreviousPage() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        coEvery { characters.getById(1L) } returns character(1L)
        val rows = (12L downTo 1L).map { SessionEntity(id = it, updatedAt = 100L) }
        var failNext = false
        coEvery { sessions.getRecentForCharacter(1L, 6, any(), any()) } answers {
            val cursor = arg<Long?>(3)
            if (cursor != null && failNext) throw IllegalStateException("read failed")
            rows.filter { cursor == null || it.id < cursor }.take(6)
        }
        val vm = viewModel(characters, mockk(relaxed = true), sessions = sessions)
        vm.load(1L)
        advanceUntilIdle()
        assertEquals(listOf(12L, 11L, 10L, 9L, 8L), vm.state.value.recentStories.map { it.id })
        assertTrue(vm.state.value.storyHasNext)
        failNext = true
        vm.nextStoryPage()
        vm.nextStoryPage()
        advanceUntilIdle()
        assertEquals(0, vm.state.value.storyPageIndex)
        assertEquals(12L, vm.state.value.recentStories.first().id)
        assertEquals("相关故事读取失败，请重试", vm.state.value.storiesError)
        failNext = false
        vm.retryStoryPage()
        advanceUntilIdle()
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), vm.state.value.recentStories.map { it.id })
        assertEquals(1, vm.state.value.storyPageIndex)
        vm.nextStoryPage()
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), vm.state.value.recentStories.map { it.id })
        assertFalse(vm.state.value.storyHasNext)
        vm.previousStoryPage()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.storyPageIndex)
        assertEquals(7L, vm.state.value.recentStories.first().id)
    }

    @Test
    fun relatedStoriesIgnoreOldCharacterResultAndKeepNewCharacterEmpty() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        coEvery { characters.getById(any()) } answers { character(firstArg()) }
        val gate = CompletableDeferred<Unit>()
        coEvery { sessions.getRecentForCharacter(1L) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            listOf(SessionEntity(id = 99L))
        }
        coEvery { sessions.getRecentForCharacter(2L) } returns emptyList()
        val vm = viewModel(characters, mockk(relaxed = true), sessions = sessions)
        vm.load(1L)
        runCurrent()
        assertTrue(vm.state.value.storiesLoading)
        vm.load(2L, force = true)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2L, vm.state.value.character?.id)
        assertTrue(vm.state.value.recentStories.isEmpty())
        assertFalse(vm.state.value.storiesLoading)
    }

    @Test
    fun emptyLastStoryPageRefreshFallsBackWithoutReadingAllStories() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        coEvery { characters.getById(1L) } returns character(1L)
        var rows = (6L downTo 1L).map { SessionEntity(id = it, updatedAt = 100L) }
        coEvery { sessions.getRecentForCharacter(1L, 6, any(), any()) } answers {
            val cursor = arg<Long?>(3)
            rows.filter { cursor == null || it.id < cursor }.take(6)
        }
        val vm = viewModel(characters, mockk(relaxed = true), sessions = sessions)
        vm.load(1L)
        advanceUntilIdle()
        vm.nextStoryPage()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.storyPageIndex)
        rows = rows.filter { it.id > 1L }
        vm.load(1L, force = true)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.storyPageIndex)
        assertEquals(5, vm.state.value.recentStories.size)
        assertFalse(vm.state.value.storyHasNext)
    }

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun character(id: Long, favorite: Boolean = false) = CharacterEntity(
        id = id,
        name = "角色$id",
        favorite = favorite,
    )

    private fun viewModel(
        characters: CharacterDao,
        createSession: CreateSessionUseCase,
        encyclopedia: EncyclopediaDao = mockk(relaxed = true),
        sessions: SessionDao = mockk(relaxed = true),
    ) = CharacterDetailViewModel(characters, encyclopedia, sessions, createSession)

    @Test
    fun sameCharacterReloadKeepsChatBusyAndRejectsDuplicateStart() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        val createSession = mockk<CreateSessionUseCase>()
        val gate = CompletableDeferred<CreateSessionUseCase.Result>()
        coEvery { characters.getById(1L) } returns character(1L)
        coEvery { sessions.getRecentForCharacter(1L) } returns emptyList()
        coEvery { createSession.createForCharacter(1L) } coAnswers { gate.await() }
        val vm = viewModel(characters, createSession, sessions = sessions)
        vm.load(1L, force = true)
        advanceUntilIdle()

        var created = 0
        vm.startChat { created++ }
        runCurrent()
        assertTrue(vm.state.value.actionInProgress)
        vm.load(1L, force = true)
        runCurrent()
        assertTrue(vm.state.value.actionInProgress)
        vm.startChat { created++ }
        runCurrent()
        assertEquals(0, created)

        gate.complete(CreateSessionUseCase.Result.Created(7L))
        advanceUntilIdle()
        assertEquals(1, created)
        assertFalse(vm.state.value.actionInProgress)
    }

    @Test
    fun oldActionCannotDeliverAfterLoadingDifferentCharacter() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        val createSession = mockk<CreateSessionUseCase>()
        val gate = CompletableDeferred<CreateSessionUseCase.Result>()
        coEvery { characters.getById(1L) } returns character(1L)
        coEvery { characters.getById(2L) } returns character(2L)
        coEvery { sessions.getRecentForCharacter(any()) } returns emptyList()
        coEvery { createSession.createForCharacter(1L) } coAnswers { gate.await() }
        val vm = viewModel(characters, createSession, sessions = sessions)
        vm.load(1L, force = true)
        advanceUntilIdle()

        var created = 0
        vm.startChat { created++ }
        runCurrent()
        vm.load(2L, force = true)
        advanceUntilIdle()
        gate.complete(CreateSessionUseCase.Result.Created(8L))
        advanceUntilIdle()

        assertEquals(2L, vm.state.value.character?.id)
        assertEquals(0, created)
        assertFalse(vm.state.value.actionInProgress)
    }

    @Test
    fun favoriteFailureClearsBusyAndAllowsRetry() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        val createSession = mockk<CreateSessionUseCase>(relaxed = true)
        coEvery { characters.getById(1L) } returns character(1L)
        coEvery { sessions.getRecentForCharacter(1L) } returns emptyList()
        coEvery { characters.toggleFavorite(1L) } throws IllegalStateException("offline") andThen Unit
        val vm = viewModel(characters, createSession, sessions = sessions)
        vm.load(1L, force = true)
        advanceUntilIdle()

        vm.toggleFavorite()
        advanceUntilIdle()
        assertFalse(vm.state.value.actionInProgress)
        assertEquals("收藏操作失败，请重试", vm.state.value.actionError)

        vm.toggleFavorite()
        advanceUntilIdle()
        assertFalse(vm.state.value.actionInProgress)
        assertEquals(null, vm.state.value.actionError)
    }

    @Test
    fun sameCharacterReloadKeepsFavoriteBusyAndRejectsDuplicateToggle() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        val createSession = mockk<CreateSessionUseCase>(relaxed = true)
        val gate = CompletableDeferred<Unit>()
        coEvery { characters.getById(1L) } returns character(1L)
        coEvery { sessions.getRecentForCharacter(1L) } returns emptyList()
        coEvery { characters.toggleFavorite(1L) } coAnswers { gate.await() }
        val vm = viewModel(characters, createSession, sessions = sessions)
        vm.load(1L, force = true)
        advanceUntilIdle()

        vm.toggleFavorite()
        runCurrent()
        vm.load(1L, force = true)
        runCurrent()
        vm.toggleFavorite()
        runCurrent()
        io.mockk.coVerify(exactly = 1) { characters.toggleFavorite(1L) }
        assertTrue(vm.state.value.actionInProgress)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.actionInProgress)
    }

    @Test
    fun favoriteCompletionFromOldCharacterCannotOverwriteNewDetail() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        val createSession = mockk<CreateSessionUseCase>(relaxed = true)
        val gate = CompletableDeferred<Unit>()
        coEvery { characters.getById(1L) } returns character(1L)
        coEvery { characters.getById(2L) } returns character(2L)
        coEvery { sessions.getRecentForCharacter(any()) } returns emptyList()
        coEvery { characters.toggleFavorite(1L) } coAnswers { gate.await() }
        val vm = viewModel(characters, createSession, sessions = sessions)
        vm.load(1L, force = true)
        advanceUntilIdle()

        vm.toggleFavorite()
        runCurrent()
        vm.load(2L, force = true)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, vm.state.value.character?.id)
        assertFalse(vm.state.value.actionInProgress)
    }

    @Test
    fun favoriteMutationRevisionRejectsOlderLoadSnapshot() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val sessions = mockk<SessionDao>(relaxed = true)
        val createSession = mockk<CreateSessionUseCase>(relaxed = true)
        val toggleGate = CompletableDeferred<Unit>()
        val loadGate = CompletableDeferred<Unit>()
        var recentCalls = 0
        var favoriteWritten = false
        coEvery { characters.getById(1L) } answers { character(1L, favoriteWritten) }
        coEvery { sessions.getRecentForCharacter(1L) } coAnswers {
            recentCalls++
            if (recentCalls == 2) loadGate.await()
            emptyList()
        }
        coEvery { characters.toggleFavorite(1L) } coAnswers {
            toggleGate.await()
            favoriteWritten = true
        }
        val vm = viewModel(characters, createSession, sessions = sessions)
        vm.load(1L, force = true)
        advanceUntilIdle()

        vm.toggleFavorite()
        runCurrent()
        vm.load(1L, force = true)
        runCurrent()
        toggleGate.complete(Unit)
        runCurrent()
        loadGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value.character?.favorite == true)
    }
}
