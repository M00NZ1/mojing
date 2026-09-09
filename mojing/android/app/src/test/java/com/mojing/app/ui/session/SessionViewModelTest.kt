package com.mojing.app.ui.session

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.domain.usecase.SessionCreationTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessionDao = mockk<SessionDao>(relaxed = true) {
        every { observeAllWithListMeta() } returns flowOf(emptyList())
    }
    private val generationTaskDao = mockk<GenerationTaskDao>(relaxed = true) {
        every { observeActiveCount() } returns flowOf(0)
    }
    private val preferences = mockk<UiPreferencesRepository>(relaxed = true) {
        every { quickStartGuideDismissed } returns flowOf(false)
    }
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val transaction = mockk<SessionCreationTransaction>(relaxed = true)
    private val worldTemplateDao = mockk<WorldTemplateDao>(relaxed = true)
    private val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val createSession = CreateSessionUseCase(
        transaction = transaction,
        characterDao = characterDao,
        worldTemplateDao = worldTemplateDao,
        secureStorage = secureStorage,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sessionLibraryStaysLoadingUntilTheFirstDatabaseResult() = runTest(dispatcher) {
        val delayedSessions = MutableSharedFlow<List<SessionWithListMeta>>()
        every { sessionDao.observeAllWithListMeta() } returns delayedSessions
        val viewModel = createViewModel()
        runCurrent()

        assertEquals(SessionLibraryUiState.Loading, viewModel.sessionLibraryState.value)

        delayedSessions.emit(emptyList())
        runCurrent()

        assertEquals(SessionLibraryUiState.Loaded(emptyList()), viewModel.sessionLibraryState.value)
    }

    @Test
    fun sessionLibraryFailureCanRetryWithoutPretendingTheLibraryIsEmpty() = runTest(dispatcher) {
        var attempts = 0
        every { sessionDao.observeAllWithListMeta() } answers {
            attempts += 1
            if (attempts == 1) {
                flow { throw IllegalStateException("database unavailable") }
            } else {
                flowOf(emptyList())
            }
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(SessionLibraryUiState.Failed, viewModel.sessionLibraryState.value)

        viewModel.retrySessionLibrary()
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(SessionLibraryUiState.Loaded(emptyList()), viewModel.sessionLibraryState.value)
    }

    @Test
    fun blankCreationFailureReturnsReadableFeedbackWithoutSuccessCallback() = runTest(dispatcher) {
        coEvery { transaction(any(), any(), any()) } throws
            IllegalStateException("disk full")
        val viewModel = createViewModel()
        var createdId: Long? = null
        var failure: String? = null

        viewModel.createNewSession(
            onCreated = { createdId = it },
            onFailed = { failure = it },
        )
        advanceUntilIdle()

        assertEquals(null, createdId)
        assertEquals("创建对话失败，请重试", failure)
        assertFalse(viewModel.isCreatingSession.value)
    }

    @Test
    fun dialogDefaultsCarrySavedWorldTemplateIdentity() {
        every { secureStorage.defaultWorldTemplateId } returns "star-sea"
        every { secureStorage.defaultNarratorEnabled } returns true
        every { secureStorage.defaultChoiceGenerationEnabled } returns false
        every { secureStorage.defaultAntiCheatEnabled } returns false

        val defaults = createViewModel().newSessionDialogDefaults()

        assertEquals("star-sea", defaults.defaultWorldTemplateId)
        assertEquals(true, defaults.narratorEnabled)
        assertEquals(false, defaults.choiceEnabled)
        assertEquals(false, defaults.antiCheatEnabled)
    }

    @Test
    fun dialogDataLoadsAllRequiredSources() = runTest(dispatcher) {
        val template = WorldTemplateEntity(id = 2L, templateId = "star-sea")
        val encyclopedia = EncyclopediaEntity(id = 3L, name = "星海百科")
        val character = CharacterEntity(id = 4L, boundEncyclopediaId = 3L)
        coEvery { worldTemplateDao.getAll() } returns listOf(template)
        coEvery { encyclopediaDao.getAll() } returns listOf(encyclopedia)
        coEvery { characterDao.getAll() } returns listOf(character)

        val data = createViewModel().loadNewSessionDialogData()

        assertEquals(listOf(template), data.templates)
        assertEquals(listOf(encyclopedia), data.encyclopedias)
        assertEquals(listOf(character), data.boundCharacters)
    }

    @Test
    fun dialogDataFailurePropagatesToUiOwner() = runTest(dispatcher) {
        coEvery { worldTemplateDao.getAll() } throws IllegalStateException("database unavailable")

        val result = runCatching { createViewModel().loadNewSessionDialogData() }

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { encyclopediaDao.getAll() }
        coVerify(exactly = 0) { characterDao.getAll() }
    }

    @Test
    fun configuredCreationFailureKeepsDialogOwnerOnReadableError() = runTest(dispatcher) {
        coEvery { characterDao.getById(1L) } returns CharacterEntity(
            id = 1L,
            boundEncyclopediaId = 3L,
        )
        coEvery { transaction(any(), any(), any()) } throws IllegalStateException("disk full")
        val viewModel = createViewModel()
        var createdId: Long? = null
        var failure: String? = null

        viewModel.createSessionWithOptions(
            title = "新故事",
            template = null,
            encyclopediaId = 3L,
            narratorEnabled = true,
            narratorName = "旁白",
            choiceEnabled = true,
            maxChoices = 3,
            antiCheatEnabled = true,
            participantCharacterIds = listOf(1L),
            onCreated = { createdId = it },
            onBlocked = { failure = it },
        )
        advanceUntilIdle()

        assertEquals(null, createdId)
        assertEquals("创建对话失败，请重试", failure)
        assertFalse(viewModel.isCreatingSession.value)
    }

    @Test
    fun allCreationEntrypointsShareOneInFlightOwner() = runTest(dispatcher) {
        val transactionStarted = CompletableDeferred<Unit>()
        val transactionResult = CompletableDeferred<Long>()
        coEvery { transaction(any(), any(), any()) } coAnswers {
            transactionStarted.complete(Unit)
            transactionResult.await()
        }
        val viewModel = createViewModel()
        var createdId: Long? = null
        var duplicateFeedback: String? = null

        viewModel.createNewSession(onCreated = { createdId = it })
        runCurrent()
        transactionStarted.await()
        assertTrue(viewModel.isCreatingSession.value)

        viewModel.createSessionWithOptions(
            title = "第二次点击",
            template = null,
            encyclopediaId = null,
            narratorEnabled = false,
            narratorName = "旁白",
            choiceEnabled = true,
            maxChoices = 3,
            antiCheatEnabled = true,
            participantCharacterIds = emptyList(),
            onBlocked = { duplicateFeedback = it },
        )

        assertEquals("正在创建对话，请稍候", duplicateFeedback)
        coVerify(exactly = 1) { transaction(any(), any(), any()) }

        transactionResult.complete(42L)
        advanceUntilIdle()

        assertEquals(42L, createdId)
        assertFalse(viewModel.isCreatingSession.value)
        coVerify(exactly = 1) { transaction(any(), any(), any()) }
    }

    @Test
    fun deletingSessionClearsItsRememberedChatBranch() = runTest(dispatcher) {
        val viewModel = createViewModel()

        viewModel.deleteSession(42L)
        advanceUntilIdle()

        coVerify(exactly = 1) { sessionDao.delete(42L) }
        coVerify(exactly = 1) { preferences.clearLastChatBranch(42L) }
    }

    private fun createViewModel(): SessionViewModel = SessionViewModel(
        sessionDao = sessionDao,
        worldTemplateDao = worldTemplateDao,
        encyclopediaDao = encyclopediaDao,
        characterDao = characterDao,
        createSessionUseCase = createSession,
        generationTaskDao = generationTaskDao,
        secureStorage = secureStorage,
        uiPreferencesRepository = preferences,
    )
}
