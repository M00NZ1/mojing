package com.mojing.app.ui.session

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.dao.LegacyWorldMappingDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.data.local.dao.NewSessionWorldOption
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.StoryBranchPreviewSource
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.branch.BranchVisibilityIndexManager
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.LegacyWorldMappingEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import com.mojing.app.data.local.entity.SessionBranchEntity
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
        every { observeListPageWithMeta(any(), any(), any(), any(), any()) } returns flowOf(emptyList())
    }
    private val sessionBranchDao = mockk<SessionBranchDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val branchVisibilityIndexManager = mockk<BranchVisibilityIndexManager>(relaxed = true)
    private val generationTaskDao = mockk<GenerationTaskDao>(relaxed = true) {
        every { observeActiveCount() } returns flowOf(0)
    }
    private val preferences = mockk<UiPreferencesRepository>(relaxed = true) {
        every { quickStartGuideDismissed } returns flowOf(false)
        every { lastChatBranches } returns flowOf(emptyMap())
    }
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val transaction = mockk<SessionCreationTransaction>(relaxed = true)
    private val worldTemplateDao = mockk<WorldTemplateDao>(relaxed = true)
    private val legacyWorldMappingDao = mockk<LegacyWorldMappingDao>(relaxed = true)
    private val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val createSession = CreateSessionUseCase(
        worldMappingDao = io.mockk.mockk { coEvery { getByTemplateId(any()) } returns null },
        encyclopediaDao = io.mockk.mockk { coEvery { getById(any()) } returns null },
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
        every { sessionDao.observeListPageWithMeta(any(), any(), any(), any(), any()) } returns delayedSessions
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
        every { sessionDao.observeListPageWithMeta(any(), any(), any(), any(), any()) } answers {
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
    fun failedLibraryRefreshKeepsReadStoriesAvailableUntilRetrySucceeds() = runTest(dispatcher) {
        val previous = SessionWithListMeta(
            session = SessionEntity(id = 7L, title = "雨夜故事"),
            lastMessagePreview = "上一幕", lastMessageSpeakerType = "narrator",
            messageCount = 3, participantCount = 1,
        )
        val recovered = previous.copy(lastMessagePreview = "新一幕", messageCount = 4)
        val nextRead = CompletableDeferred<List<SessionWithListMeta>>()
        var attempts = 0
        every { sessionDao.observeListPageWithMeta(any(), any(), any(), any(), any()) } answers {
            attempts += 1
            if (attempts == 1) flow {
                emit(listOf(previous))
                throw IllegalStateException("temporary read failure")
            } else flow { emit(nextRead.await()) }
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(
            SessionLibraryUiState.Loaded(listOf(previous), refreshError = true),
            viewModel.sessionLibraryState.value,
        )

        viewModel.retrySessionLibrary()
        runCurrent()
        assertEquals(
            SessionLibraryUiState.Loaded(listOf(previous), refreshing = true),
            viewModel.sessionLibraryState.value,
        )

        nextRead.complete(listOf(recovered))
        advanceUntilIdle()
        assertEquals(SessionLibraryUiState.Loaded(listOf(recovered)), viewModel.sessionLibraryState.value)
        assertEquals(2, attempts)
    }

    @Test
    fun storyLibraryKeepsCurrentPageWhenNextPageFailsAndRetriesItsCursor() = runTest(dispatcher) {
        val firstPage = (80L downTo 40L).map { id ->
            SessionWithListMeta(SessionEntity(id = id, title = "故事$id", updatedAt = 100L), null, null, 0, 0)
        }
        val secondPage = (39L downTo 1L).map { id ->
            SessionWithListMeta(SessionEntity(id = id, title = "故事$id", updatedAt = 100L), null, null, 0, 0)
        }
        var secondAttempts = 0
        every { sessionDao.observeListPageWithMeta(any(), any(), any(), any(), any()) } answers {
            if (arg<Long?>(3) == null) flowOf(firstPage)
            else if (++secondAttempts == 1) flow { throw IllegalStateException("page unavailable") }
            else flowOf(secondPage)
        }
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(40, (viewModel.sessionLibraryState.value as SessionLibraryUiState.Loaded).sessions.size)

        viewModel.nextSessionLibraryPage()
        advanceUntilIdle()
        val failed = viewModel.sessionLibraryState.value as SessionLibraryUiState.Loaded
        assertEquals(0, failed.pageIndex)
        assertEquals(firstPage.take(40), failed.sessions)
        assertTrue(failed.refreshError)

        viewModel.retrySessionLibrary()
        advanceUntilIdle()
        val recovered = viewModel.sessionLibraryState.value as SessionLibraryUiState.Loaded
        assertEquals(1, recovered.pageIndex)
        assertEquals(secondPage, recovered.sessions)
        assertEquals(2, secondAttempts)
    }

    @Test
    fun storyTitleSearchQueriesDatabaseFromTheFirstPage() = runTest(dispatcher) {
        val requested = mutableListOf<String>()
        every { sessionDao.observeListPageWithMeta(any(), any(), any(), any(), any()) } answers {
            requested += arg<String>(0)
            flowOf(emptyList())
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSearch("灯塔")
        advanceUntilIdle()

        assertEquals(listOf("", "灯塔"), requested)
        assertEquals("灯塔", (viewModel.sessionLibraryState.value as SessionLibraryUiState.Loaded).query)
    }

    @Test
    fun branchCardPreviewReadsRememberedBranchWithoutLoadingFullMessages() = runTest(dispatcher) {
        coEvery { sessionBranchDao.getByBranch(7L, "branch-a") } returns
            SessionBranchEntity(sessionId = 7L, branchId = "branch-a", label = "雨后", sourceMessageId = 3L)
        coEvery { messageDao.getVisibleStoryCardPreview(7L, "branch-a") } returns
            StoryBranchPreviewSource("新一幕", "narrator")
        val viewModel = createViewModel()

        viewModel.loadBranchCardPreview(7L, "branch-a", 10L)
        advanceUntilIdle()

        assertEquals(
            BranchCardPreviewState("branch-a", 10L, 1L, label = "雨后", contentPrefix = "新一幕",
                speakerType = "narrator", loading = false),
            viewModel.branchCardPreviews.value[7L],
        )
        coVerify(exactly = 1) { branchVisibilityIndexManager.ensureReady() }
        coVerify(exactly = 1) { messageDao.getVisibleStoryCardPreview(7L, "branch-a") }
    }

    @Test
    fun staleBranchCardPreviewCannotReplaceNewlySelectedBranch() = runTest(dispatcher) {
        val oldRead = CompletableDeferred<StoryBranchPreviewSource?>()
        coEvery { sessionBranchDao.getByBranch(7L, "branch-a") } returns
            SessionBranchEntity(sessionId = 7L, branchId = "branch-a", sourceMessageId = 3L)
        coEvery { sessionBranchDao.getByBranch(7L, "branch-b") } returns
            SessionBranchEntity(sessionId = 7L, branchId = "branch-b", sourceMessageId = 4L)
        coEvery { messageDao.getVisibleStoryCardPreview(7L, "branch-a") } coAnswers { oldRead.await() }
        coEvery { messageDao.getVisibleStoryCardPreview(7L, "branch-b") } returns
            StoryBranchPreviewSource("另一幕", "character")
        val viewModel = createViewModel()

        viewModel.loadBranchCardPreview(7L, "branch-a", 10L)
        runCurrent()
        viewModel.loadBranchCardPreview(7L, "branch-b", 10L)
        advanceUntilIdle()
        oldRead.complete(StoryBranchPreviewSource("过期内容", "narrator"))
        advanceUntilIdle()

        assertEquals("branch-b", viewModel.branchCardPreviews.value[7L]?.branchId)
        assertEquals("另一幕", viewModel.branchCardPreviews.value[7L]?.contentPrefix)
    }

    @Test
    fun failedBranchCardPreviewCanRetryWithoutChangingRememberedBranch() = runTest(dispatcher) {
        coEvery { sessionBranchDao.getByBranch(7L, "branch-a") } returns
            SessionBranchEntity(sessionId = 7L, branchId = "branch-a", sourceMessageId = 3L)
        var reads = 0
        coEvery { messageDao.getVisibleStoryCardPreview(7L, "branch-a") } coAnswers {
            if (++reads == 1) throw IllegalStateException("temporary read failure")
            StoryBranchPreviewSource("恢复的预览", "character")
        }
        val viewModel = createViewModel()

        viewModel.loadBranchCardPreview(7L, "branch-a", 10L)
        advanceUntilIdle()
        assertTrue(viewModel.branchCardPreviews.value[7L]?.error == true)
        viewModel.loadBranchCardPreview(7L, "branch-a", 10L, retry = true)
        advanceUntilIdle()

        assertEquals("恢复的预览", viewModel.branchCardPreviews.value[7L]?.contentPrefix)
        assertFalse(viewModel.branchCardPreviews.value[7L]?.error == true)
        assertEquals(2, reads)
    }

    @Test
    fun returningToLibraryDropsInFlightBranchPreview() = runTest(dispatcher) {
        val pending = CompletableDeferred<StoryBranchPreviewSource?>()
        coEvery { sessionBranchDao.getByBranch(7L, "branch-a") } returns
            SessionBranchEntity(sessionId = 7L, branchId = "branch-a", sourceMessageId = 3L)
        coEvery { messageDao.getVisibleStoryCardPreview(7L, "branch-a") } coAnswers { pending.await() }
        val viewModel = createViewModel()

        viewModel.loadBranchCardPreview(7L, "branch-a", 10L)
        runCurrent()
        viewModel.refreshBranchCardPreviews()
        pending.complete(StoryBranchPreviewSource("旧预览", "narrator"))
        advanceUntilIdle()

        assertEquals(1L, viewModel.branchPreviewEpoch.value)
        assertTrue(viewModel.branchCardPreviews.value.isEmpty())
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
    fun dialogDataResolvesMappedDefaultWithoutLoadingTheWorldCatalog() = runTest(dispatcher) {
        val template = WorldTemplateEntity(id = 2L, templateId = "star-sea")
        val encyclopedia = EncyclopediaEntity(id = 3L, name = "星海百科")
        coEvery { worldTemplateDao.getByTemplateId("star-sea") } returns template
        coEvery { legacyWorldMappingDao.getByTemplateId(2L) } returns LegacyWorldMappingEntity(2L, 3L, "test")
        coEvery { encyclopediaDao.getById(3L) } returns encyclopedia

        val data = createViewModel().loadNewSessionDialogData(
            initializeWorld = true, selectedTemplateId = null, selectedEncyclopediaId = null,
            requestedTemplateId = null, defaultTemplateId = "star-sea",
        )

        assertEquals(null, data.template)
        assertEquals(encyclopedia, data.encyclopedia)
        assertEquals(3L, data.encyclopediaId)
        assertTrue(data.initialTemplateFound)
        coVerify(exactly = 0) { worldTemplateDao.getAll() }
        coVerify(exactly = 0) { encyclopediaDao.getAll() }
        coVerify(exactly = 0) { characterDao.getAll() }
    }

    @Test
    fun dialogDataFailurePropagatesToUiOwner() = runTest(dispatcher) {
        coEvery { worldTemplateDao.getById(2L) } throws IllegalStateException("database unavailable")

        val result = runCatching { createViewModel().loadNewSessionDialogData(
            initializeWorld = true, selectedTemplateId = null, selectedEncyclopediaId = null,
            requestedTemplateId = 2L, defaultTemplateId = "",
        ) }

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { encyclopediaDao.getById(any()) }
        coVerify(exactly = 0) { characterDao.getAll() }
    }

    @Test
    fun worldPickerLoadsOnlyOnePageAndResolvesTheSelectedRow() = runTest(dispatcher) {
        val options = (41L downTo 1L).map { id -> NewSessionWorldOption(0, id, "世界$id", 1, 0L, 100L) }
        coEvery { worldTemplateDao.getNewSessionWorldPage("世界", null, null, null, null, 41) } returns options
        val selected = EncyclopediaEntity(id = 41L, name = "世界41")
        coEvery { encyclopediaDao.getById(41L) } returns selected

        val viewModel = createViewModel()
        val page = viewModel.loadNewSessionWorldPage(" 世界 ", null)
        val selection = viewModel.loadNewSessionWorldSelection(page.rows.first())

        assertEquals(40, page.rows.size)
        assertTrue(page.hasMore)
        assertEquals(selected, selection.encyclopedia)
        coVerify(exactly = 0) { worldTemplateDao.getAll() }
    }

    @Test
    fun newSessionCharacterSummaryKeepsOnlyCompatibleSelectionsAndFindsOneNewCharacter() = runTest(dispatcher) {
        coEvery { characterDao.countForNewSession(3L) } returns 3
        coEvery { characterDao.firstIdsForNewSession(3L) } returns listOf(9L, 8L)
        coEvery { characterDao.existingIdsForNewSession(listOf(8L, 7L), 3L) } returns listOf(8L)
        coEvery { characterDao.newIdsForNewSession(7L, 3L) } returns listOf(9L)
        coEvery { characterDao.maxId() } returns 9L

        val summary = createViewModel().loadNewSessionCharacterSummary(3L, setOf(8L, 7L), 7L)

        assertEquals(3, summary.count)
        assertEquals(null, summary.onlyId)
        assertEquals(setOf(8L), summary.existingSelectedIds)
        assertEquals(listOf(9L), summary.newlyAvailableIds)
        assertEquals(9L, summary.maxId)
        coVerify(exactly = 0) { characterDao.getAll() }
    }

    @Test
    fun newSessionCharacterPickerLimitsRowsWithoutLoadingPrompts() = runTest(dispatcher) {
        val rows = (41L downTo 1L).map { id ->
            NewSessionCharacterOption(id, "角色$id", 0L, false, 100L)
        }
        coEvery { characterDao.getNewSessionPickerPage(3L, "角色", null, null, null, null, 41) } returns rows

        val page = createViewModel().loadNewSessionCharacterPage(3L, " 角色 ", null)

        assertEquals(40, page.rows.size)
        assertTrue(page.hasMore)
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
    fun blankCreationIsNotReportedAsFailedWhenOpeningCallbackThrows() = runTest(dispatcher) {
        coEvery { transaction(any(), any(), any()) } returns 42L
        val viewModel = createViewModel()
        var failure: String? = null
        var createdButNotOpened: Long? = null

        viewModel.createNewSession(
            onCreated = { throw IllegalStateException("navigation unavailable") },
            onFailed = { failure = it },
            onCreatedButNotOpened = {
                assertTrue(viewModel.isCreatingSession.value)
                createdButNotOpened = it
            },
        )
        advanceUntilIdle()

        assertEquals(null, failure)
        assertEquals(42L, createdButNotOpened)
        assertFalse(viewModel.isCreatingSession.value)
        coVerify(exactly = 1) { transaction(any(), any(), any()) }
    }

    @Test
    fun configuredCreationIsNotReportedAsFailedWhenOpeningCallbackThrows() = runTest(dispatcher) {
        coEvery { characterDao.getById(1L) } returns CharacterEntity(id = 1L)
        coEvery { transaction(any(), any(), any()) } returns 43L
        val viewModel = createViewModel()
        var blocked: String? = null
        var createdButNotOpened: Long? = null

        viewModel.createSessionWithOptions(
            title = "新故事",
            template = null,
            encyclopediaId = null,
            narratorEnabled = true,
            narratorName = "旁白",
            choiceEnabled = true,
            maxChoices = 3,
            antiCheatEnabled = true,
            participantCharacterIds = listOf(1L),
            onCreated = { throw IllegalStateException("navigation unavailable") },
            onBlocked = { blocked = it },
            onCreatedButNotOpened = {
                assertTrue(viewModel.isCreatingSession.value)
                createdButNotOpened = it
            },
        )
        advanceUntilIdle()

        assertEquals(null, blocked)
        assertEquals(43L, createdButNotOpened)
        assertFalse(viewModel.isCreatingSession.value)
        coVerify(exactly = 1) { transaction(any(), any(), any()) }
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

    @Test
    fun deletionWaitsForDatabaseAndRejectsDuplicateSubmission() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { sessionDao.delete(42L) } coAnswers { gate.await() }
        val model = createViewModel()
        model.deleteSession(42L)
        model.deleteSession(42L)
        runCurrent()
        assertTrue(model.deletionState.value.running)
        assertFalse(model.deletionState.value.completed)
        model.clearDeletionResult()
        assertTrue(model.deletionState.value.running)
        coVerify(exactly = 1) { sessionDao.delete(42L) }
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(model.deletionState.value.completed)
        assertFalse(model.deletionState.value.running)
    }

    @Test
    fun failedDeletionRetainsRetryStateWithoutAnnouncingSuccess() = runTest(dispatcher) {
        coEvery { sessionDao.delete(42L) } throws IllegalStateException("private database detail")
        val model = createViewModel()
        model.deleteSession(42L); advanceUntilIdle()
        assertEquals("删除未完成，请重试", model.deletionState.value.error)
        assertFalse(model.deletionState.value.completed)
        coVerify(exactly = 0) { preferences.clearLastChatBranch(any()) }
        coEvery { sessionDao.delete(42L) } returns Unit
        model.deleteSession(42L); advanceUntilIdle()
        assertTrue(model.deletionState.value.completed)
        assertEquals(null, model.deletionState.value.error)
    }

    @Test
    fun generatingSessionCannotBeDeleted() = runTest(dispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val job = kotlinx.coroutines.Job()
        registry.retainJob(42L, job)
        try {
            val model = createViewModel()
            model.deleteSession(42L); runCurrent()
            assertEquals("请先停止此对话的后台任务，再删除", model.deletionState.value.error)
            assertFalse(model.deletionState.value.running)
            coVerify(exactly = 0) { sessionDao.delete(any()) }
        } finally { job.complete(); runCurrent() }
    }

    private fun createViewModel(): SessionViewModel = SessionViewModel(
        sessionDao = sessionDao,
        sessionBranchDao = sessionBranchDao,
        messageDao = messageDao,
        branchVisibilityIndexManager = branchVisibilityIndexManager,
        worldTemplateDao = worldTemplateDao,
        legacyWorldMappingDao = legacyWorldMappingDao,
        encyclopediaDao = encyclopediaDao,
        characterDao = characterDao,
        createSessionUseCase = createSession,
        generationTaskDao = generationTaskDao,
        secureStorage = secureStorage,
        uiPreferencesRepository = preferences,
    )
}
