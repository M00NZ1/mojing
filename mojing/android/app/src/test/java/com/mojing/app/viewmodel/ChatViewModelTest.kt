package com.mojing.app.viewmodel

import kotlinx.coroutines.flow.first

import androidx.lifecycle.SavedStateHandle
import android.content.Context
import com.mojing.app.data.ChatDraftSnapshot
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.AttachmentDao
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionEventNodeDao
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.dao.MessageRecallResult
import com.mojing.app.data.local.dao.ParticipantDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.dao.SessionWorldDao
import com.mojing.app.data.local.dao.SessionMemoryCorrectionDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.BranchSwipeSelectionEntity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldCredentialDraft
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.engine.ChatEngine
import com.mojing.app.domain.engine.NarratorEngine
import com.mojing.app.domain.engine.StreamState
import com.mojing.app.domain.usecase.MessageSubmissionTransaction
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.ui.chat.ChatViewModel
import com.mojing.app.ui.util.UserFacingStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun existingSessionDao(sessionId: Long): SessionDao = mockk<SessionDao>(relaxed = true).also {
        coEvery { it.getById(sessionId) } returns SessionEntity(id = sessionId)
    }

    private fun emptyDraftStore(): ChatDraftStore = mockk<ChatDraftStore>(relaxed = true).also {
        every { it.load(any()) } returns ChatDraftSnapshot()
        every { it.saveBeforeSubmission(any(), any()) } returns true
    }

    private fun submissionTransaction(
        messageDao: MessageDao,
        attachmentDao: AttachmentDao,
    ): MessageSubmissionTransaction = mockk<MessageSubmissionTransaction>().also { transaction ->
        coEvery { transaction(any(), any(), any()) } coAnswers {
            val message = args[0] as MessageEntity
            @Suppress("UNCHECKED_CAST")
            val attachments = args[1] as List<MessageAttachmentEntity>
            @Suppress("UNCHECKED_CAST")
            val onMessageIdAssigned = args[2] as (Long) -> Unit
            val messageId = messageDao.insert(message)
            onMessageIdAssigned(messageId)
            attachments.forEach { attachment ->
                attachmentDao.insert(attachment.copy(messageId = messageId))
            }
            messageId
        }
    }

    private fun uiPreferences(lastBranchId: String = "main"): UiPreferencesRepository =
        mockk<UiPreferencesRepository>(relaxed = true) {
            every { chatDensity } returns flowOf("comfortable")
            coEvery { getLastChatBranch(any()) } returns lastBranchId
        }

    private fun createViewModel(
        sessionId: Long = 42L,
        messageDao: MessageDao = mockk(relaxed = true),
        sessionBranchDao: SessionBranchDao = mockk(relaxed = true),
        sessionDao: SessionDao = existingSessionDao(sessionId),
        characterDao: CharacterDao = mockk(relaxed = true),
        sessionWorldDao: SessionWorldDao = mockk(relaxed = true),
        attachmentDao: AttachmentDao = mockk(relaxed = true),
        messageSubmissionTransaction: MessageSubmissionTransaction? = null,
        memoryCorrectionDao: SessionMemoryCorrectionDao = mockk(relaxed = true),
        eventNodeDao: SessionEventNodeDao = mockk(relaxed = true),
        memorySegmentDao: com.mojing.app.data.local.dao.SessionMemorySegmentDao = mockk(relaxed = true),
        contextMemory: com.mojing.app.domain.engine.UniversalContextMemoryManager = mockk(relaxed = true),
        participantDao: ParticipantDao = mockk(relaxed = true),
        chatDraftStore: ChatDraftStore = emptyDraftStore(),
        secureStorage: SecureStorage = mockk(relaxed = true) { every { sessionModelSelection(any()) } returns null },
        llmApiService: LlmApiService = mockk(relaxed = true),
        appContext: Context = mockk(relaxed = true),
        uiPreferencesRepository: UiPreferencesRepository = uiPreferences(),
        chatEngine: ChatEngine = mockk(relaxed = true),
    ) = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId)),
        messageDao = messageDao,
        sessionDao = sessionDao,
        characterDao = characterDao,
        participantDao = participantDao,
        sessionWorldDao = sessionWorldDao,
        sessionBranchDao = sessionBranchDao,
        memorySegmentDao = memorySegmentDao,
        memoryCorrectionDao = memoryCorrectionDao,
        eventNodeDao = eventNodeDao,
        costRecorder = mockk(relaxed = true),
        chatEngine = chatEngine,
        secureStorage = secureStorage,
        promptBuilder = mockk(relaxed = true),
        memoryCompactor = mockk(relaxed = true),
        contextBuilder = mockk(relaxed = true),
        tokenBudgetManager = mockk(relaxed = true),
        slidingWindowBuilder = mockk(relaxed = true),
        snapshotExtractor = mockk(relaxed = true),
        memoryV2Manager = mockk(relaxed = true),
        universalContextMemoryManager = contextMemory,
        sedimentEngine = mockk(relaxed = true),
        characterStateDao = mockk(relaxed = true),
        attachmentDao = attachmentDao,
        messageSubmissionTransaction = messageSubmissionTransaction
            ?: submissionTransaction(messageDao, attachmentDao),
        bookmarkDao = mockk(relaxed = true),
        imageRepository = mockk(relaxed = true),
        llmApiService = llmApiService,
        imageApiService = mockk(relaxed = true),
        uiPreferencesRepository = uiPreferencesRepository,
        narratorEngine = NarratorEngine(),
        chatDraftStore = chatDraftStore,
        appContext = appContext,
    )

    private fun validSecureStorage(
        apiKey: String = "sk-test",
        baseUrl: String = "https://api.test.com/v1",
        model: String = "test-model",
    ): SecureStorage = mockk(relaxed = true) {
        every { sessionModelSelection(any()) } returns null
        every { publicApiKey } returns apiKey
        every { publicBaseUrl } returns baseUrl
        every { publicModel } returns model
    }

    private fun validLlmApiService(): LlmApiService = LlmApiService()

    @Test
    fun clearingContextMemoryUpdatesDisplayedMemoryAndKeepsStateOnFailure() = runTest(testDispatcher) {
        val memory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
        coEvery { memory.getFormattedMemory(42L, "main") } returns "码头约定"
        val vm = createViewModel(contextMemory = memory)
        advanceUntilIdle()
        assertEquals("码头约定", vm.state.value.contextMemoryText)
        coEvery { memory.clear(42L, "main") } throws IllegalStateException("busy")
        vm.clearCurrentContextMemory()
        advanceUntilIdle()
        assertEquals("码头约定", vm.state.value.contextMemoryText)
        assertFalse(vm.state.value.memoryOperationRunning)
        coEvery { memory.clear(42L, "main") } returns Unit
        vm.clearCurrentContextMemory()
        advanceUntilIdle()
        assertEquals("", vm.state.value.contextMemoryText)
        assertFalse(vm.state.value.memoryOperationRunning)
    }

    @Test
    fun exposesSessionIdFromSavedState() = runTest(testDispatcher) {
        val vm = createViewModel()
        assertNotNull(vm)
        assertEquals(42L, vm.state.value.sessionId)
        advanceUntilIdle()
    }

    @Test
    fun initializationLoadsVisibleMemoryCorrectionsForMainBranch() = runTest(testDispatcher) {
        val correction = SessionMemoryCorrectionEntity(sessionId = 42L, content = "主线纠正")
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        coEvery { dao.getVisible(42L, "main") } returns listOf(correction)

        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()

        assertEquals(listOf(correction), vm.state.value.memoryCorrections)
    }

    @Test
    fun switchingBranchRefreshesCorrectionsWithoutTouchingAutomaticMemory() = runTest(testDispatcher) {
        val correction = SessionMemoryCorrectionEntity(sessionId = 42L, branchId = "branch-1", content = "分支纠正")
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        coEvery { dao.getVisible(42L, "main") } returns emptyList()
        coEvery { dao.getVisible(42L, "branch-1") } returns listOf(correction)
        val branches = listOf(SessionBranchEntity(sessionId = 42L, branchId = "branch-1", sourceMessageId = 1L))
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branchDao.getBySession(42L) } returns branches
        val preferences = uiPreferences()

        val vm = createViewModel(
            sessionBranchDao = branchDao,
            memoryCorrectionDao = dao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()
        vm.switchBranch("branch-1")
        advanceUntilIdle()

        assertEquals("branch-1", vm.state.value.currentBranchId)
        assertEquals(listOf(correction), vm.state.value.memoryCorrections)
        coVerify(exactly = 1) { preferences.setLastChatBranch(42L, "branch-1") }
    }

    @Test
    fun switchingBranchRejectsAStaleIdWithoutChangingOrPersisting() = runTest(testDispatcher) {
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branchDao.getBySession(42L) } returns emptyList()
        val preferences = uiPreferences()
        val vm = createViewModel(
            sessionBranchDao = branchDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        vm.switchBranch("missing-branch")
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("故事线已不存在，请刷新后重试", vm.state.value.error)
        coVerify(exactly = 0) { preferences.setLastChatBranch(any(), any()) }
    }

    @Test
    fun switchingBranchReadFailureKeepsTheCurrentStoryline() = runTest(testDispatcher) {
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        var failReads = false
        coEvery { branchDao.getBySession(42L) } answers {
            if (failReads) throw IllegalStateException("database unavailable")
            emptyList()
        }
        val preferences = uiPreferences()
        val vm = createViewModel(
            sessionBranchDao = branchDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        failReads = true
        vm.switchBranch("branch-1")
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("故事线切换失败，请重试", vm.state.value.error)
        coVerify(exactly = 0) { preferences.setLastChatBranch(any(), any()) }
    }

    @Test
    fun switchingBranchLoadFailureKeepsTheCurrentStoryline() = runTest(testDispatcher) {
        val branch = SessionBranchEntity(sessionId = 42L, branchId = "branch-1", sourceMessageId = 1L)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { branchDao.getBySession(42L) } returns listOf(branch)
        coEvery { messageDao.getVisibleMessagesTail(42L, "branch-1", any()) } throws
            IllegalStateException("database unavailable")
        val preferences = uiPreferences()
        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        vm.switchBranch("branch-1")
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("故事线切换失败，请重试", vm.state.value.error)
        coVerify(exactly = 0) { preferences.setLastChatBranch(any(), any()) }
    }

    @Test
    fun initializationRestoresValidLastBranchForAllBranchScopedState() = runTest(testDispatcher) {
        val branch = SessionBranchEntity(
            sessionId = 42L,
            branchId = "branch-1",
            sourceMessageId = 1L,
        )
        val branchMessage = MessageEntity(
            id = 2L,
            sessionId = 42L,
            branchId = "branch-1",
            speakerType = "character",
            content = "分支中的回复",
        )
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val corrections = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val correction = SessionMemoryCorrectionEntity(
            sessionId = 42L,
            branchId = "branch-1",
            content = "分支纠正",
        )
        coEvery { branchDao.getBySession(42L) } returns listOf(branch)
        coEvery { messageDao.getVisibleMessagesTail(42L, "branch-1", any()) } returns listOf(branchMessage)
        coEvery { corrections.getVisible(42L, "branch-1") } returns listOf(correction)

        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            memoryCorrectionDao = corrections,
            uiPreferencesRepository = uiPreferences("branch-1"),
        )
        advanceUntilIdle()

        assertEquals("branch-1", vm.state.value.currentBranchId)
        assertEquals(listOf("分支中的回复"), vm.state.value.messages.map { it.content })
        assertEquals(listOf(correction), vm.state.value.memoryCorrections)
        coVerify(exactly = 0) { messageDao.getMainMessagesTail(42L, any()) }
    }

    @Test
    fun initializationClearsMissingLastBranchAndReturnsToMain() = runTest(testDispatcher) {
        val mainMessage = MessageEntity(
            id = 3L,
            sessionId = 42L,
            speakerType = "user",
            content = "主线继续",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val preferences = uiPreferences("deleted-branch")
        coEvery { branchDao.getBySession(42L) } returns emptyList()
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(mainMessage)

        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(listOf("主线继续"), vm.state.value.messages.map { it.content })
        assertEquals("上次打开的故事线已不存在，已返回主线", vm.state.value.error)
        coVerify(exactly = 1) { preferences.clearLastChatBranch(42L) }
        coVerify(exactly = 0) { messageDao.getVisibleMessagesTail(any(), any(), any()) }
    }

    @Test
    fun correctionFailureKeepsExistingVisibleCorrections() = runTest(testDispatcher) {
        val existing = SessionMemoryCorrectionEntity(sessionId = 42L, content = "已有纠正")
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        coEvery { dao.getVisible(42L, "main") } returns listOf(existing)
        coEvery { dao.insert(any()) } throws IllegalStateException("write failed")

        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.saveMemoryCorrection(null, "新纠正", "main", null)
        advanceUntilIdle()

        assertEquals(listOf(existing), vm.state.value.memoryCorrections)
        assertTrue(vm.state.value.error.orEmpty().contains("保存失败"))
    }

    @Test
    fun correctionRejectsNewMissingSourceButKeepsExistingDeletedSourceEditable() = runTest(testDispatcher) {
        val existing = SessionMemoryCorrectionEntity(
            id = 7L,
            sessionId = 42L,
            content = "已有纠正",
            sourceMessageId = 999L,
        )
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getVisible(42L, "main") } returns listOf(existing)
        coEvery { dao.getById(42L, 7L) } returns existing
        coEvery { messageDao.getByIdInSession(999L, 42L) } returns null

        val vm = createViewModel(messageDao = messageDao, memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.saveMemoryCorrection(null, "新纠正", "main", 999L)
        advanceUntilIdle()
        coVerify(exactly = 0) { dao.insert(any()) }

        vm.saveMemoryCorrection(7L, "仍可编辑", null, 999L)
        advanceUntilIdle()
        coVerify(exactly = 1) { dao.update(match { it.content == "仍可编辑" }) }
    }

    @Test
    fun missingSessionStopsInitializationBeforeCreatingWorldRows() = runTest(testDispatcher) {
        val sessionDao = mockk<SessionDao>(relaxed = true)
        val sessionWorldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { sessionDao.getById(42L) } returns null

        val vm = createViewModel(sessionDao = sessionDao, sessionWorldDao = sessionWorldDao)
        advanceUntilIdle()

        assertTrue(vm.state.value.sessionNotFound)
        assertEquals("对话不存在或已删除", vm.state.value.error)
        coVerify(exactly = 0) { sessionWorldDao.upsert(any<SessionWorldEntity>()) }
    }

    @Test
    fun initializationFailureCanRetryWithoutDuplicatingWorldCreation() = runTest(testDispatcher) {
        val sessionWorldDao = mockk<SessionWorldDao>(relaxed = true)
        var worldRead = 0
        coEvery { sessionWorldDao.getBySession(42L) } answers {
            worldRead++
            when (worldRead) {
                1 -> null
                2 -> throw IllegalStateException("database unavailable")
                else -> SessionWorldEntity(sessionId = 42L)
            }
        }

        val vm = createViewModel(sessionWorldDao = sessionWorldDao)
        advanceUntilIdle()

        assertFalse(vm.state.value.isReady)
        assertEquals("对话加载失败，请重试", vm.state.value.initialLoadError)
        assertFalse(vm.state.value.initialLoadError.orEmpty().contains("database"))
        coVerify(exactly = 1) { sessionWorldDao.upsert(any<SessionWorldEntity>()) }

        vm.retryInitialization()
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        assertEquals(null, vm.state.value.initialLoadError)
        coVerify(exactly = 1) { sessionWorldDao.upsert(any<SessionWorldEntity>()) }
    }

    @Test
    fun sessionWorldCredentialSaveConfirmsOnlyAfterPersistence() = runTest(testDispatcher) {
        val original = SessionWorldEntity(sessionId = 42L, sessionLlmApiKey = "old-key")
        val sessionWorldDao = mockk<SessionWorldDao>(relaxed = true)
        val persistenceFinished = CompletableDeferred<Unit>()
        coEvery { sessionWorldDao.getBySession(42L) } returns original
        coEvery { sessionWorldDao.upsert(any()) } coAnswers {
            persistenceFinished.await()
            1L
        }
        val vm = createViewModel(sessionWorldDao = sessionWorldDao)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()

        vm.saveSessionWorldCredentials(
            SessionWorldCredentialDraft(
                sessionLlmApiKey = "  new-key  ",
                sessionLlmBaseUrl = "  https://example.test/v1  ",
            ),
        ) { results += it }
        runCurrent()

        assertTrue(results.isEmpty())
        assertEquals("old-key", vm.state.value.world?.sessionLlmApiKey)

        persistenceFinished.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true), results)
        assertEquals("new-key", vm.state.value.world?.sessionLlmApiKey)
        assertEquals("https://example.test/v1", vm.state.value.world?.sessionLlmBaseUrl)
        coVerify(exactly = 1) {
            sessionWorldDao.upsert(match {
                it.sessionLlmApiKey == "new-key" &&
                    it.sessionLlmBaseUrl == "https://example.test/v1"
            })
        }
    }

    @Test
    fun sessionWorldCredentialSaveFailureKeepsPreviousStateAndReportsFailure() = runTest(testDispatcher) {
        val original = SessionWorldEntity(sessionId = 42L, sessionLlmApiKey = "old-key")
        val sessionWorldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { sessionWorldDao.getBySession(42L) } returns original
        coEvery { sessionWorldDao.upsert(any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(sessionWorldDao = sessionWorldDao)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()

        vm.saveSessionWorldCredentials(
            SessionWorldCredentialDraft(sessionLlmApiKey = "new-key"),
        ) { results += it }
        advanceUntilIdle()

        assertEquals(listOf(false), results)
        assertEquals("old-key", vm.state.value.world?.sessionLlmApiKey)
        assertEquals("本场线路保存失败，请重试", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test
    fun participantAdditionConfirmsOnlyAfterPersistence() = runTest(testDispatcher) {
        val character = CharacterEntity(id = 7L, name = "青鸾", boundEncyclopediaId = 3L)
        val characterDao = mockk<CharacterDao>(relaxed = true)
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        val persistenceFinished = CompletableDeferred<Unit>()
        var committed = false
        coEvery { characterDao.getById(7L) } returns character
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            encyclopediaId = 3L,
        )
        coEvery { participantDao.getBySession(42L) } answers {
            if (committed) {
                listOf(SessionParticipantEntity(id = 9L, sessionId = 42L, characterId = 7L))
            } else {
                emptyList()
            }
        }
        coEvery { participantDao.upsert(any()) } coAnswers {
            persistenceFinished.await()
            committed = true
            9L
        }
        val vm = createViewModel(
            characterDao = characterDao,
            participantDao = participantDao,
            sessionWorldDao = worldDao,
        )
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()

        vm.addParticipant(7L) { results += it }
        runCurrent()

        assertTrue(results.isEmpty())
        assertTrue(vm.state.value.participants.isEmpty())

        persistenceFinished.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true), results)
        assertEquals(listOf(7L), vm.state.value.participants.map { it.characterId })
        assertEquals("青鸾", vm.state.value.characterNames[7L])
    }

    @Test
    fun participantAdditionFailureReturnsRetryableError() = runTest(testDispatcher) {
        val characterDao = mockk<CharacterDao>(relaxed = true)
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { characterDao.getById(7L) } returns CharacterEntity(
            id = 7L,
            name = "青鸾",
            boundEncyclopediaId = 3L,
        )
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            encyclopediaId = 3L,
        )
        coEvery { participantDao.getBySession(42L) } returns emptyList()
        coEvery { participantDao.upsert(any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(
            characterDao = characterDao,
            participantDao = participantDao,
            sessionWorldDao = worldDao,
        )
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()

        vm.addParticipant(7L) { results += it }
        advanceUntilIdle()

        assertEquals(listOf(false), results)
        assertTrue(vm.state.value.participants.isEmpty())
        assertEquals("添加角色失败，请重试", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test
    fun talkativenessSaveConfirmsOnlyAfterFinalValuePersists() = runTest(testDispatcher) {
        val original = SessionParticipantEntity(
            id = 9L,
            sessionId = 42L,
            characterId = 7L,
            talkativeness = 0.7f,
        )
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        val persistenceFinished = CompletableDeferred<Unit>()
        var persisted = original
        coEvery { participantDao.getBySession(42L) } answers { listOf(persisted) }
        coEvery { participantDao.getById(9L) } returns original
        coEvery { participantDao.upsert(any()) } coAnswers {
            persistenceFinished.await()
            persisted = firstArg()
            9L
        }
        val vm = createViewModel(participantDao = participantDao)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()

        vm.updateParticipantTalkativeness(9L, 0.25f) { results += it }
        runCurrent()

        assertTrue(results.isEmpty())
        assertEquals(0.7f, vm.state.value.participants.single().talkativeness)

        persistenceFinished.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true), results)
        assertEquals(0.25f, vm.state.value.participants.single().talkativeness)
        coVerify(exactly = 1) {
            participantDao.upsert(match { it.id == 9L && it.talkativeness == 0.25f })
        }
    }

    @Test
    fun talkativenessSaveFailureKeepsPersistedValueAndReportsRetry() = runTest(testDispatcher) {
        val original = SessionParticipantEntity(
            id = 9L,
            sessionId = 42L,
            characterId = 7L,
            talkativeness = 0.7f,
        )
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        coEvery { participantDao.getBySession(42L) } returns listOf(original)
        coEvery { participantDao.getById(9L) } returns original
        coEvery { participantDao.upsert(any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(participantDao = participantDao)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()

        vm.updateParticipantTalkativeness(9L, 0.25f) { results += it }
        advanceUntilIdle()

        assertEquals(listOf(false), results)
        assertEquals(0.7f, vm.state.value.participants.single().talkativeness)
        assertEquals("发言率保存失败，请重试", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test
    fun activeGenerationRejectsRoundConfigurationMutations() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        val sessionDao = existingSessionDao(42L)
        val participant = SessionParticipantEntity(
            id = 9L,
            sessionId = 42L,
            characterId = 7L,
        )
        coEvery { participantDao.getBySession(42L) } returns listOf(participant)
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L)
        coEvery { messageDao.insert(any()) } coAnswers { awaitCancellation() }
        val vm = createViewModel(
            messageDao = messageDao,
            participantDao = participantDao,
            sessionWorldDao = worldDao,
            sessionDao = sessionDao,
        )
        advanceUntilIdle()
        vm.updateInput("继续剧情")
        vm.sendMessage()
        runCurrent()
        assertTrue(vm.state.value.isGenerating)
        val results = mutableListOf<Boolean>()

        vm.addParticipant(8L) { results += it }
        vm.toggleMute(9L)
        vm.updateParticipantTalkativeness(9L, 0.2f) { results += it }
        vm.removeParticipant(9L)
        vm.updateWorldSetting("narratorEnabled", true)
        vm.saveSessionWorldCredentials(SessionWorldCredentialDraft(sessionLlmApiKey = "new-key")) {
            results += it
        }
        assertFalse(vm.updateSpeakerTurnMode("manual"))
        vm.setSessionThinkMax(true) {}
        runCurrent()

        assertEquals(listOf(false, false, false), results)
        assertEquals(
            "回复生成期间暂不能调整对话设置，请先停止或等待完成",
            vm.state.value.error,
        )
        coVerify(exactly = 0) { participantDao.getById(any()) }
        coVerify(exactly = 0) { participantDao.upsert(any()) }
        coVerify(exactly = 0) { participantDao.delete(any()) }
        coVerify(exactly = 0) { worldDao.upsert(any()) }
        coVerify(exactly = 0) { sessionDao.updateThinkMax(any(), any()) }

        vm.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun removingParticipantClearsItsManualReplySelection() = runTest(testDispatcher) {
        val participant = SessionParticipantEntity(
            id = 9L,
            sessionId = 42L,
            characterId = 7L,
        )
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        var removed = false
        coEvery { participantDao.getBySession(42L) } answers {
            if (removed) emptyList() else listOf(participant)
        }
        coEvery { participantDao.getById(9L) } returns participant
        coEvery { participantDao.delete(9L) } answers { removed = true }
        val vm = createViewModel(participantDao = participantDao)
        advanceUntilIdle()
        vm.setManualReplyCharacterId(7L)

        vm.removeParticipant(9L)
        advanceUntilIdle()

        assertTrue(vm.state.value.participants.isEmpty())
        assertEquals(null, vm.state.value.manualReplyCharacterId)
        coVerify(exactly = 1) { participantDao.delete(9L) }
    }

    @Test
    fun participantRemovalFailureKeepsParticipantAndReportsRetry() = runTest(testDispatcher) {
        val participant = SessionParticipantEntity(id = 9L, sessionId = 42L, characterId = 7L)
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        coEvery { participantDao.getBySession(42L) } returns listOf(participant)
        coEvery { participantDao.getById(9L) } returns participant
        coEvery { participantDao.delete(9L) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(participantDao = participantDao)
        advanceUntilIdle()

        vm.removeParticipant(9L)
        advanceUntilIdle()

        assertEquals(listOf(9L), vm.state.value.participants.map { it.id })
        assertEquals("移除角色失败，请重试", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test
    fun participantMuteFailureKeepsPersistedStateAndReportsRetry() = runTest(testDispatcher) {
        val participant = SessionParticipantEntity(
            id = 9L,
            sessionId = 42L,
            characterId = 7L,
            muted = false,
        )
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        coEvery { participantDao.getBySession(42L) } returns listOf(participant)
        coEvery { participantDao.getById(9L) } returns participant
        coEvery { participantDao.upsert(any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(participantDao = participantDao)
        advanceUntilIdle()

        vm.toggleMute(9L)
        advanceUntilIdle()

        assertFalse(vm.state.value.participants.single().muted)
        assertEquals("角色静音状态保存失败，请重试", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test
    fun worldSettingFailureKeepsPersistedStateAndReportsRetry() = runTest(testDispatcher) {
        val world = SessionWorldEntity(sessionId = 42L, narratorEnabled = false)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { worldDao.getBySession(42L) } returns world
        coEvery { worldDao.upsert(any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(sessionWorldDao = worldDao)
        advanceUntilIdle()

        vm.updateWorldSetting("narratorEnabled", true)
        advanceUntilIdle()

        assertFalse(vm.state.value.world?.narratorEnabled == true)
        assertEquals("本场玩法保存失败，请重试", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test
    fun sendIsRejectedWhileInitializationFailed() = runTest(testDispatcher) {
        val sessionDao = mockk<SessionDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { sessionDao.getById(42L) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(sessionDao = sessionDao, messageDao = messageDao)
        advanceUntilIdle()

        vm.updateInput("不应写入")
        vm.sendMessage()
        advanceUntilIdle()

        assertFalse(vm.state.value.isReady)
        assertEquals("对话加载失败，请重试", vm.state.value.initialLoadError)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun modelPickerChangesEngineRouteForCharactersAndNarrator() = runTest(testDispatcher) {
        for (narrator in listOf(false, true)) {
            val a = com.mojing.app.data.ModelPlatform("a", "A", "https://a.test/v1", "fake-a", listOf("a-one", "a-two"))
            val b = com.mojing.app.data.ModelPlatform("b", "B", "https://b.test/v1", "fake-b", listOf("b-one"))
            var selection = "a" to "a-one"
            val storage = validSecureStorage()
            every { storage.modelPlatforms() } returns listOf(a, b)
            every { storage.sessionModelSelection(42L) } answers { selection }
            every { storage.selectSessionModel(42L, any(), any()) } answers {
                selection = secondArg<String>() to thirdArg<String>()
            }
            every { storage.speakerTurnMode } returns "manual"
            val routes = mutableListOf<List<String>>()
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                routes.add(listOf(args[3] as String, args[4] as String, args[5] as String))
                flowOf(StreamState.Done("旁白测试回复"))
            }
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                routes.add(listOf(args[6] as String, args[7] as String, args[8] as String))
                flowOf(StreamState.Done("角色测试回复"))
            }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L)
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "测试角色", modelName = "old-character-model")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val vm = createViewModel(secureStorage = storage, sessionWorldDao = world,
                characterDao = characters, participantDao = participants, chatEngine = engine,
                llmApiService = validLlmApiService())
            advanceUntilIdle()
            vm.setManualReplyCharacterId(3L)
            for ((platform, model) in listOf("a" to "a-one", "a" to "a-two", "b" to "b-one")) {
                val saved = CompletableDeferred<Unit>()
                vm.selectChatModel(platform, model) { saved.complete(Unit) }
                saved.await()
                if (narrator) assertTrue(vm.requestNarrator()) else {
                    vm.setManualReplyCharacterId(3L)
                    vm.updateInput("测试 $model")
                    vm.sendMessage()
                }
                advanceUntilIdle()
            }
            assertEquals("narrator=$narrator error=${vm.state.value.error}", listOf(
                listOf("fake-a", "https://a.test/v1", "a-one"),
                listOf("fake-a", "https://a.test/v1", "a-two"),
                listOf("fake-b", "https://b.test/v1", "b-one"),
            ), routes)
        }
    }

    @Test
    fun selectedPlatformIsFrozenForCurrentRoundAndChangesOnNextSend() = runTest(testDispatcher) {
        val a = com.mojing.app.data.ModelPlatform("a", "A", "https://a.test/v1", "test-a", listOf("a-model"))
        val b = com.mojing.app.data.ModelPlatform("b", "B", "https://b.test/v1", "test-b", listOf("b-model"))
        var selection = "a" to "a-model"
        val storage = validSecureStorage()
        every { storage.modelPlatforms() } returns listOf(a, b)
        every { storage.sessionModelSelection(42L) } answers { selection }
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insert(any()) } coAnswers { awaitCancellation() }
        val vm = createViewModel(messageDao = messageDao, secureStorage = storage)
        advanceUntilIdle()
        val route = ChatViewModel::class.java.getDeclaredMethod("requestPlatform").apply { isAccessible = true }
        vm.updateInput("第一条")
        vm.sendMessage()
        assertTrue(vm.state.value.isGenerating)
        assertEquals(a, route.invoke(vm))
        selection = "b" to "b-model"
        assertEquals(a, route.invoke(vm))
        vm.stopGeneration()
        advanceUntilIdle()
        vm.updateInput("下一条")
        vm.sendMessage()
        assertEquals(b, route.invoke(vm))
        vm.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun doubleSendStartsOnlyOneGeneration() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insert(any()) } coAnswers { awaitCancellation() }
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.updateInput("第一条")
        vm.queueLocalImageAttachment("F:/tmp/pending.png")
        vm.sendMessage()
        assertTrue("生成状态应在排队前立即可见", vm.state.value.isGenerating)
        vm.sendMessage()
        runCurrent()

        coVerify(exactly = 1) {
            messageDao.insert(match { it.speakerType == "user" && it.content == "第一条" })
        }
        assertTrue(vm.state.value.isGenerating)

        vm.stopGeneration()
        assertEquals("第一条", vm.state.value.inputText)
        assertEquals(listOf("F:/tmp/pending.png"), vm.state.value.pendingLocalImagePaths)
        advanceUntilIdle()
    }

    @Test
    fun sendingFirstMessageUsesPlaceholderAwareSessionTouch() = runTest(testDispatcher) {
        val storedMessages = mutableListOf<MessageEntity>()
        val messageDao = mockk<MessageDao>(relaxed = true)
        val sessionDao = existingSessionDao(42L)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } answers { storedMessages.toList() }
        coEvery { messageDao.insert(any()) } answers {
            val inserted = (args.first() as MessageEntity).copy(id = 8L)
            storedMessages.add(0, inserted)
            inserted.id
        }
        val vm = createViewModel(messageDao = messageDao, sessionDao = sessionDao)
        advanceUntilIdle()

        vm.updateInput("第一幕：雨夜")
        vm.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) { sessionDao.touchWithGeneratedTitle(42L, "第一幕：雨夜", any()) }
        coVerify(exactly = 0) { sessionDao.updateTitle(any(), any(), any()) }
    }

    @Test
    fun selectingLatestReplyChoiceSubmitsThatChoiceThroughTheSendOwner() = runTest(testDispatcher) {
        val reply = MessageEntity(
            id = 8L,
            sessionId = 42L,
            speakerType = "character",
            content = """
                <SPEECH>你准备好了吗？</SPEECH>
                ### 可选行动（任选其一）：
                > 1. 推门进入
                > （二）先观察四周

                请选择你接下来要做的事。
            """.trimIndent(),
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(reply)
        coEvery { messageDao.insert(any()) } returns 9L
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            choiceGenerationEnabled = true,
        )
        val vm = createViewModel(messageDao = messageDao, sessionWorldDao = worldDao)
        advanceUntilIdle()

        assertEquals(listOf("推门进入", "先观察四周"), vm.state.value.roundChoiceOptions)
        assertEquals(8L, vm.state.value.roundChoiceMessageId)
        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.SelectChoice("推门进入", 8L))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            messageDao.insert(match { it.speakerType == "user" && it.content == "推门进入" })
        }
    }

    @Test
    fun trailingAutoMediaKeepsLatestReplyChoicesInteractive() = runTest(testDispatcher) {
        val reply = MessageEntity(
            id = 8L,
            sessionId = 42L,
            speakerType = "character",
            content = "<SPEECH>选一条路。</SPEECH><CHOICES><OPTION>穿过森林</OPTION><OPTION>沿河前进</OPTION></CHOICES>",
            createdAt = 100L,
        )
        val generatedImage = MessageEntity(
            id = 9L,
            sessionId = 42L,
            speakerType = "character",
            content = "",
            includeInContext = false,
            createdAt = 200L,
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(generatedImage, reply)
        coEvery { messageDao.insert(any()) } returns 10L
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            choiceGenerationEnabled = true,
        )
        val vm = createViewModel(messageDao = messageDao, sessionWorldDao = worldDao)
        advanceUntilIdle()

        assertEquals(8L, vm.state.value.roundChoiceMessageId)
        assertEquals(listOf("穿过森林", "沿河前进"), vm.state.value.roundChoiceOptions)
        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.SelectChoice("穿过森林", 8L))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            messageDao.insert(match { it.speakerType == "user" && it.content == "穿过森林" })
        }
    }

    @Test
    fun selectingChoiceFromOlderReplyDoesNotCreateAnotherUserTurn() = runTest(testDispatcher) {
        val oldReply = MessageEntity(
            id = 8L,
            sessionId = 42L,
            speakerType = "character",
            content = "<SPEECH>请选择。</SPEECH>\n<CHOICES><OPTION>旧选项</OPTION><OPTION>另一旧选项</OPTION></CHOICES>",
        )
        val unansweredUser = MessageEntity(
            id = 9L,
            sessionId = 42L,
            speakerType = "user",
            content = "我已经采取了别的行动",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(unansweredUser, oldReply)
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            choiceGenerationEnabled = true,
        )
        val vm = createViewModel(messageDao = messageDao, sessionWorldDao = worldDao)
        advanceUntilIdle()

        assertTrue(vm.state.value.roundChoiceOptions.isEmpty())
        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.SelectChoice("旧选项", 8L))
        advanceUntilIdle()

        assertEquals("该选项已不属于当前回合，请选择最新回复中的选项", vm.state.value.error)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun sameTextChoiceFromOlderReplyCannotSelectCurrentRound() = runTest(testDispatcher) {
        val oldReply = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "character",
            content = "<SPEECH>上一轮。</SPEECH><CHOICES><OPTION>继续</OPTION></CHOICES>",
        )
        val currentReply = MessageEntity(
            id = 8L,
            sessionId = 42L,
            speakerType = "character",
            content = "<SPEECH>这一轮。</SPEECH><CHOICES><OPTION>继续</OPTION></CHOICES>",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(currentReply, oldReply)
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            choiceGenerationEnabled = true,
        )
        val vm = createViewModel(messageDao = messageDao, sessionWorldDao = worldDao)
        advanceUntilIdle()

        assertEquals(8L, vm.state.value.roundChoiceMessageId)
        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.SelectChoice("继续", 7L))
        advanceUntilIdle()

        assertEquals("该选项已不属于当前回合，请选择最新回复中的选项", vm.state.value.error)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun choicesFollowTheActiveSwipeVersionInsteadOfTheNewestStoredVariant() = runTest(testDispatcher) {
        val activeReply = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "character",
            swipeGroupId = "reply-1",
            content = "<SPEECH>当前采用版本。</SPEECH><CHOICES><OPTION>走左边</OPTION></CHOICES>",
            includeInContext = true,
            createdAt = 100L,
        )
        val inactiveNewerReply = MessageEntity(
            id = 8L,
            sessionId = 42L,
            speakerType = "character",
            swipeGroupId = "reply-1",
            content = "<SPEECH>未采用版本。</SPEECH><CHOICES><OPTION>走右边</OPTION></CHOICES>",
            includeInContext = false,
            createdAt = 200L,
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns
            listOf(inactiveNewerReply, activeReply)
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            choiceGenerationEnabled = true,
        )
        val vm = createViewModel(messageDao = messageDao, sessionWorldDao = worldDao)
        advanceUntilIdle()

        assertEquals(7L, vm.state.value.roundChoiceMessageId)
        assertEquals(listOf("走左边"), vm.state.value.roundChoiceOptions)
        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.SelectChoice("走右边", 8L))
        advanceUntilIdle()

        assertEquals("该选项已不属于当前回合，请选择最新回复中的选项", vm.state.value.error)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun continueReplyUsesLatestUserTailWithoutInsertingAnotherUserMessage() = runTest(testDispatcher) {
        val latest = MessageEntity(id = 8L, sessionId = 42L, speakerType = "user", content = "继续")
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(latest)
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.ContinueReply(latest))
        advanceUntilIdle()

        assertEquals(UserFacingStrings.chatNoParticipant(), vm.state.value.error)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun continueReplyRejectsActionWhenBranchTailIsNotTheSelectedUserMessage() = runTest(testDispatcher) {
        val actionMessage = MessageEntity(id = 8L, sessionId = 42L, speakerType = "user", content = "旧用户")
        val laterMessage = MessageEntity(id = 9L, sessionId = 42L, speakerType = "character", content = "已有回复")
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(laterMessage)
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.ContinueReply(actionMessage))
        advanceUntilIdle()

        assertEquals("只能继续生成当前故事线最后一条用户消息的回复", vm.state.value.error)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun novelContinueReplyValidatesTailAndUsesNarratorPreflightWithoutUserInsert() = runTest(testDispatcher) {
        val latest = MessageEntity(id = 8L, sessionId = 42L, speakerType = "user", content = "续写")
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            gameplayMode = "小说创作",
        )
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(latest)
        val vm = createViewModel(
            messageDao = messageDao,
            sessionWorldDao = worldDao,
            secureStorage = validSecureStorage(apiKey = ""),
        )
        advanceUntilIdle()

        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.ContinueReply(latest))
        advanceUntilIdle()

        assertEquals("请先在「设置」填写 API Key，或在角色中填写 API Key", vm.state.value.error)
        coVerify(atLeast = 1) { messageDao.getMainMessagesTail(42L, 1) }
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun localMessageInsertFailureIsNotReportedAsNetworkOrPermissionError() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insert(any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.updateInput("不会写入的消息")
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals("消息未能保存到本机，请重试。", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("database"))
        assertFalse(vm.state.value.error.orEmpty().contains("网络"))
        assertFalse(vm.state.value.error.orEmpty().contains("权限"))
        assertEquals("不会写入的消息", vm.state.value.inputText)
    }

    @Test
    fun restoresSessionDraftAndDropsInvalidOrAlreadySentAttachments() = runTest(testDispatcher) {
        val root = createTempDirectory("mojing-draft-test").toFile()
        try {
            val sessionDir = File(root, "attachments/42").apply { mkdirs() }
            val valid = File(sessionDir, "valid.png").apply { writeText("image") }
            val alreadySent = File(sessionDir, "already-sent.png").apply { writeText("sent image") }
            val missing = File(sessionDir, "missing.png")
            val foreign = File(root, "outside.png").apply { writeText("foreign") }
            val context = mockk<Context>(relaxed = true)
            every { context.filesDir } returns root
            val draftStore = emptyDraftStore()
            every { draftStore.load(42L) } returns ChatDraftSnapshot(
                inputText = "恢复后的草稿",
                pendingAttachmentPaths = listOf(
                    valid.absolutePath,
                    alreadySent.absolutePath,
                    missing.absolutePath,
                    foreign.absolutePath,
                ),
            )
            val attachmentDao = mockk<AttachmentDao>(relaxed = true)
            coEvery { attachmentDao.getReferencedStoragePaths(any()) } returns listOf(alreadySent.absolutePath)

            val vm = createViewModel(
                attachmentDao = attachmentDao,
                chatDraftStore = draftStore,
                appContext = context,
            )
            advanceUntilIdle()

            assertEquals("恢复后的草稿", vm.state.value.inputText)
            assertEquals(listOf(valid.canonicalPath), vm.state.value.pendingLocalImagePaths.map { File(it).canonicalPath })
            assertEquals(
                "已移除 2 个无法读取的待发送图片；已从待发送区移除 1 个已经发送的图片",
                vm.state.value.error,
            )
            assertTrue(alreadySent.exists())
            verify(exactly = 1) {
                draftStore.save(
                    42L,
                    match { it.inputText == "恢复后的草稿" && it.pendingAttachmentPaths == listOf(valid.absolutePath) },
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun committedDraftSubmissionIsClearedByMarkerWhileUncommittedTextIsRestored() = runTest(testDispatcher) {
        val committedStore = emptyDraftStore()
        every { committedStore.load(42L) } returns ChatDraftSnapshot(
            inputText = "已经发送的同一句",
            pendingSubmissionId = "submission-committed",
        )
        val committedMessageDao = mockk<MessageDao>(relaxed = true)
        coEvery {
            committedMessageDao.countDraftSubmission(
                42L,
                "\"draftSubmissionId\":\"submission-committed\"",
            )
        } returns 1

        val committedVm = createViewModel(
            messageDao = committedMessageDao,
            chatDraftStore = committedStore,
        )
        advanceUntilIdle()

        assertEquals("", committedVm.state.value.inputText)
        assertEquals("已从草稿中移除已经发送的文字", committedVm.state.value.error)
        verify {
            committedStore.save(
                42L,
                match { it.inputText.isEmpty() && it.pendingSubmissionId == null },
            )
        }

        val uncommittedStore = emptyDraftStore()
        every { uncommittedStore.load(42L) } returns ChatDraftSnapshot(
            inputText = "还没有提交，必须保留",
            pendingSubmissionId = "submission-not-committed",
        )
        val uncommittedMessageDao = mockk<MessageDao>(relaxed = true)
        coEvery { uncommittedMessageDao.countDraftSubmission(any(), any()) } returns 0

        val uncommittedVm = createViewModel(
            messageDao = uncommittedMessageDao,
            chatDraftStore = uncommittedStore,
        )
        advanceUntilIdle()

        assertEquals("还没有提交，必须保留", uncommittedVm.state.value.inputText)
        verify {
            uncommittedStore.save(
                42L,
                match {
                    it.inputText == "还没有提交，必须保留" && it.pendingSubmissionId == null
                },
            )
        }
    }

    @Test
    fun editingNextDraftAfterSendPreparationRemovesOnlyTheSubmissionMarker() = runTest(testDispatcher) {
        val releaseTransaction = CompletableDeferred<Unit>()
        val transaction = mockk<MessageSubmissionTransaction>()
        coEvery { transaction(any(), any(), any()) } coAnswers {
            val message = args[0] as MessageEntity
            assertTrue(message.structuredContentJson.contains("\"draftSubmissionId\":"))
            releaseTransaction.await()
            @Suppress("UNCHECKED_CAST")
            val onMessageIdAssigned = args[2] as (Long) -> Unit
            onMessageIdAssigned(18L)
            18L
        }
        val draftStore = emptyDraftStore()
        val vm = createViewModel(
            messageSubmissionTransaction = transaction,
            chatDraftStore = draftStore,
        )
        advanceUntilIdle()

        vm.updateInput("这一句正在发送")
        vm.sendMessage()
        runCurrent()
        verify {
            draftStore.saveBeforeSubmission(
                42L,
                match {
                    it.inputText == "这一句正在发送" && !it.pendingSubmissionId.isNullOrBlank()
                },
            )
        }

        vm.updateInput("临时改写")
        vm.updateInput("这一句正在发送")
        verify {
            draftStore.save(
                42L,
                match { it.inputText == "这一句正在发送" && it.pendingSubmissionId == null },
            )
        }

        releaseTransaction.complete(Unit)
        advanceUntilIdle()

        assertEquals("这一句正在发送", vm.state.value.inputText)
    }

    @Test
    fun committedMessageClearsPersistedDraftButLocalFailureKeepsIt() = runTest(testDispatcher) {
        val successStore = emptyDraftStore()
        val successMessageDao = mockk<MessageDao>(relaxed = true)
        coEvery { successMessageDao.insert(any()) } returns 8L
        val successVm = createViewModel(messageDao = successMessageDao, chatDraftStore = successStore)
        advanceUntilIdle()
        successVm.updateInput("已提交")
        successVm.queueLocalImageAttachment("F:/pending/success.png")
        successVm.sendMessage()
        advanceUntilIdle()

        assertEquals("", successVm.state.value.inputText)
        assertTrue(successVm.state.value.pendingLocalImagePaths.isEmpty())
        verify { successStore.save(42L, ChatDraftSnapshot()) }

        val failureStore = emptyDraftStore()
        val failureMessageDao = mockk<MessageDao>(relaxed = true)
        coEvery { failureMessageDao.insert(any()) } throws IllegalStateException("database unavailable")
        val failureVm = createViewModel(messageDao = failureMessageDao, chatDraftStore = failureStore)
        advanceUntilIdle()
        failureVm.updateInput("仍需保留")
        failureVm.sendMessage()
        advanceUntilIdle()

        assertEquals("仍需保留", failureVm.state.value.inputText)
        verify(exactly = 0) { failureStore.save(42L, ChatDraftSnapshot()) }
    }

    @Test
    fun attachmentTransactionFailureKeepsWholeDraftWithoutDirectDaoWrites() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val attachmentDao = mockk<AttachmentDao>(relaxed = true)
        val transaction = mockk<MessageSubmissionTransaction>()
        coEvery { transaction(any(), any(), any()) } throws IllegalStateException("forced failure")
        val draftStore = emptyDraftStore()
        val vm = createViewModel(
            messageDao = messageDao,
            attachmentDao = attachmentDao,
            messageSubmissionTransaction = transaction,
            chatDraftStore = draftStore,
        )
        advanceUntilIdle()
        vm.updateInput("附件不能丢")
        vm.queueLocalImageAttachment("F:/pending/first.png")
        vm.queueLocalImageAttachment("F:/pending/second.png")

        vm.sendMessage()
        advanceUntilIdle()

        assertEquals("附件不能丢", vm.state.value.inputText)
        assertEquals(
            listOf("F:/pending/first.png", "F:/pending/second.png"),
            vm.state.value.pendingLocalImagePaths,
        )
        assertEquals(UserFacingStrings.localSaveFailed("消息"), vm.state.value.error)
        coVerify(exactly = 1) {
            transaction(
                match { it.speakerType == "user" && it.content == "附件不能丢" },
                match { attachments ->
                    attachments.size == 2 && attachments.all { it.messageId == 0L }
                },
                any(),
            )
        }
        coVerify(exactly = 0) { messageDao.insert(any()) }
        coVerify(exactly = 0) { attachmentDao.insert(any()) }
        verify(exactly = 0) { draftStore.save(42L, ChatDraftSnapshot()) }
    }

    @Test
    fun novelGuidanceFailureKeepsDraftUntilUserMessageCommits() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            gameplayMode = "小说创作",
        )
        coEvery { messageDao.insert(any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(
            messageDao = messageDao,
            sessionWorldDao = worldDao,
            chatDraftStore = draftStore,
            secureStorage = validSecureStorage(),
            llmApiService = validLlmApiService(),
        )
        advanceUntilIdle()

        vm.setQuotingMessage(MessageEntity(id = 7L, sessionId = 42L, speakerType = "narrator", content = "码头见"))
        vm.updateInput("不要丢失的下一段走向")
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals("不要丢失的下一段走向", vm.state.value.inputText)
        assertEquals("剧情走向保存失败，请重试", vm.state.value.error)
        assertEquals(7L, vm.state.value.quotingMessage?.id)
        coVerify { messageDao.insert(match { it.content.startsWith("> 旁白：码头见\n\n") }) }
        verify(exactly = 0) { draftStore.save(42L, ChatDraftSnapshot()) }
    }

    @Test
    fun committedNovelGuidanceReportsRefreshFailureWithoutInvitingDuplicateSave() =
        runTest(testDispatcher) {
            val draftStore = emptyDraftStore()
            val messageDao = mockk<MessageDao>(relaxed = true)
            val worldDao = mockk<SessionWorldDao>(relaxed = true)
            var guidanceCommitted = false
            coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
                sessionId = 42L,
                gameplayMode = "小说创作",
            )
            coEvery { messageDao.insert(match { it.speakerType == "user" }) } answers {
                guidanceCommitted = true
                7L
            }
            coEvery { messageDao.getMainMessagesTail(42L, 81) } answers {
                if (guidanceCommitted) throw IllegalStateException("database unavailable")
                emptyList()
            }
            val chatEngine = mockk<ChatEngine>(relaxed = true)
            val vm = createViewModel(
                messageDao = messageDao,
                sessionWorldDao = worldDao,
                chatDraftStore = draftStore,
                secureStorage = validSecureStorage(),
                llmApiService = validLlmApiService(),
                chatEngine = chatEngine,
            )
            advanceUntilIdle()

            vm.updateInput("已经保存的剧情走向")
            vm.sendMessage()
            advanceUntilIdle()

            assertEquals("", vm.state.value.inputText)
            assertEquals("剧情走向已保存，但对话刷新失败，请重新进入对话", vm.state.value.error)
            assertFalse(vm.state.value.isGenerating)
            verify(exactly = 0) { chatEngine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { messageDao.insert(match { it.speakerType == "user" }) }
        }

    @Test
    fun mainExportUsesEffectiveSwipeSelectionWithoutRewritingStoredMessages() = runTest(testDispatcher) {
        val original = MessageEntity(
            id = 1L,
            sessionId = 42L,
            speakerType = "character",
            branchId = "main",
            swipeGroupId = "reply-export",
            includeInContext = true,
            content = "原版本",
        )
        val alternative = original.copy(
            id = 2L,
            includeInContext = false,
            content = "采用版本",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getMainBranchMaxMessageId(42L) } returns alternative.id
        coEvery { messageDao.getMainMessagesForExport(42L, 0L, alternative.id, 256) } returns
            listOf(original, alternative)
        coEvery {
            messageDao.getBranchSwipeSelectionsForGroups(42L, "main", listOf("reply-export"))
        } returns listOf(
            BranchSwipeSelectionEntity(42L, "main", "reply-export", alternative.id),
        )
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()
        val output = ByteArrayOutputStream()

        val count = vm.exportMainBranchJson(output)
        val messages = com.google.gson.JsonParser.parseString(output.toString(Charsets.UTF_8.name()))
            .asJsonObject["messages"].asJsonArray

        assertEquals(2L, count)
        assertFalse(messages[0].asJsonObject["includeInContext"].asBoolean)
        assertTrue(messages[1].asJsonObject["includeInContext"].asBoolean)
        assertTrue(original.includeInContext)
        assertFalse(alternative.includeInContext)
    }

    @Test
    fun committedNarratorReplyReportsRefreshFailureInsteadOfGenerationFailure() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        val chatEngine = mockk<ChatEngine>(relaxed = true)
        var narratorCommitted = false
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            narratorName = "旁白",
        )
        every {
            chatEngine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamState.Done("雨声停在窗外。"))
        coEvery { messageDao.insert(match { it.speakerType == "narrator" }) } answers {
            narratorCommitted = true
            8L
        }
        coEvery { messageDao.getMainMessagesTail(42L, 81) } answers {
            if (narratorCommitted) throw IllegalStateException("database unavailable")
            emptyList()
        }
        val vm = createViewModel(
            messageDao = messageDao,
            sessionWorldDao = worldDao,
            secureStorage = validSecureStorage(),
            llmApiService = validLlmApiService(),
            chatEngine = chatEngine,
        )
        advanceUntilIdle()

        assertTrue(vm.requestNarrator())
        advanceUntilIdle()

        assertEquals("旁白回复已保存，但对话刷新失败，请重新进入对话", vm.state.value.error)
        assertFalse(vm.state.value.isGenerating)
        coVerify(exactly = 1) { messageDao.insert(match { it.speakerType == "narrator" }) }
    }

    @Test
    fun novelGuidancePreflightRejectsMissingKeyModelAndBaseWithoutWritingOrClearingDraft() = runTest(testDispatcher) {
        data class Case(val storage: SecureStorage, val error: String)
        val cases = listOf(
            Case(validSecureStorage(apiKey = ""), "请先在「设置」填写 API Key，或在角色中填写 API Key"),
            Case(validSecureStorage(model = ""), "未填写主对话模型 id：请在角色「模型名称」或「设置」的公共模型中填写后再发送"),
            Case(validSecureStorage(baseUrl = ""), "对话服务根地址无效"),
        )

        cases.forEach { case ->
            val draftStore = emptyDraftStore()
            val messageDao = mockk<MessageDao>(relaxed = true)
            val worldDao = mockk<SessionWorldDao>(relaxed = true)
            coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
                sessionId = 42L,
                gameplayMode = "小说创作",
            )
            val vm = createViewModel(
                messageDao = messageDao,
                sessionWorldDao = worldDao,
                chatDraftStore = draftStore,
                secureStorage = case.storage,
                llmApiService = validLlmApiService(),
            )
            advanceUntilIdle()

            vm.updateInput("预检失败仍需保留")
            vm.requestNarrator("预检失败的剧情走向")
            advanceUntilIdle()

            assertEquals(case.error, vm.state.value.error)
            assertEquals("预检失败仍需保留", vm.state.value.inputText)
            coVerify(exactly = 0) { messageDao.insert(any()) }
            verify(exactly = 0) { draftStore.save(42L, ChatDraftSnapshot()) }
        }
    }

    @Test
    fun clearingPendingAttachmentsDeletesOnlyUnreferencedOwnedFiles() = runTest(testDispatcher) {
        val root = createTempDirectory("mojing-pending-test").toFile()
        try {
            val sessionDir = File(root, "attachments/42").apply { mkdirs() }
            val disposable = File(sessionDir, "disposable.png").apply { writeText("image") }
            val referenced = File(sessionDir, "referenced.png").apply { writeText("image") }
            val context = mockk<Context>(relaxed = true)
            every { context.filesDir } returns root
            val attachmentDao = mockk<AttachmentDao>(relaxed = true)
            coEvery { attachmentDao.countByStoragePath(disposable.absolutePath) } returns 0
            coEvery { attachmentDao.countByStoragePath(referenced.absolutePath) } returns 1
            val draftStore = emptyDraftStore()
            val vm = createViewModel(
                attachmentDao = attachmentDao,
                chatDraftStore = draftStore,
                appContext = context,
            )
            advanceUntilIdle()
            vm.queueLocalImageAttachment(disposable.absolutePath)
            vm.queueLocalImageAttachment(referenced.absolutePath)

            val cleared = CompletableDeferred<Unit>()
            vm.clearPendingAttachments { cleared.complete(Unit) }
            cleared.await()

            assertTrue(vm.state.value.pendingLocalImagePaths.isEmpty())
            assertFalse(disposable.exists())
            assertTrue(referenced.exists())
            verify { draftStore.save(42L, ChatDraftSnapshot()) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recallingMessageUsesTransactionalOwnerAndCleansOnlyUnreferencedPrivateMedia() = runTest(testDispatcher) {
        val root = createTempDirectory("mojing-recall-test").toFile()
        try {
            val sessionDir = File(root, "attachments/42").apply { mkdirs() }
            val disposable = File(sessionDir, "disposable.png").apply { writeText("image") }
            val stillReferenced = File(sessionDir, "referenced.png").apply { writeText("image") }
            val context = mockk<Context>(relaxed = true)
            every { context.filesDir } returns root
            val messageDao = mockk<MessageDao>(relaxed = true)
            val attachmentDao = mockk<AttachmentDao>(relaxed = true)
            coEvery { messageDao.recallInSession(42L, 8L) } returns MessageRecallResult(
                deleted = true,
                deletedMessageIds = listOf(9L, 8L),
                attachmentStoragePaths = listOf(disposable.absolutePath, stillReferenced.absolutePath),
                fallbackSwipeMessageId = 7L,
            )
            coEvery { attachmentDao.countByStoragePath(disposable.absolutePath) } returns 0
            coEvery { attachmentDao.countByStoragePath(stillReferenced.absolutePath) } returns 1
            val vm = createViewModel(
                messageDao = messageDao,
                attachmentDao = attachmentDao,
                appContext = context,
            )
            advanceUntilIdle()

            val recalled = CompletableDeferred<Boolean>()
            vm.deleteMessage(8L) { recalled.complete(it) }
            assertTrue(recalled.await())

            coVerify(exactly = 1) { messageDao.recallInSession(42L, 8L) }
            coVerify(exactly = 0) { messageDao.delete(8L) }
            assertFalse(disposable.exists())
            assertTrue(stillReferenced.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recallProtectionFailureKeepsMessagesAndDoesNotCleanFiles() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val attachmentDao = mockk<AttachmentDao>(relaxed = true)
        val target = MessageEntity(id = 8, sessionId = 42, content = "分叉来源")
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(target)
        coEvery { messageDao.recallInSession(42L, 8L) } throws com.mojing.app.data.local.dao.MessageRecallBlockedException("需要保留分叉来源")
        val vm = createViewModel(messageDao = messageDao, attachmentDao = attachmentDao)
        advanceUntilIdle()
        val result = CompletableDeferred<Boolean>()
        vm.deleteMessage(8L) { result.complete(it) }
        assertFalse(result.await())
        assertEquals("需要保留分叉来源", vm.state.value.error)
        assertEquals(listOf(8L), vm.state.value.messages.map { it.id })
        coVerify(exactly = 0) { attachmentDao.countByStoragePath(any()) }
    }

    @Test fun eventStatusUsesCurrentStorylineAndKeepsSavedStateIfReadbackFails() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, title = "当前线事件")
        coEvery { events.getForBranch(42, "main") } returns listOf(source)
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        coEvery { events.getForBranch(42, "main") } throws IllegalStateException("read failed")
        vm.toggleEventNodeResolved(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.eventNodes.single().resolved)
        assertTrue(vm.state.value.error.orEmpty().contains("修改已保存"))
        coVerify { events.setResolved(1, true) }
        coVerify(exactly = 0) { events.getBySession(any()) }
        coVerify(exactly = 0) { events.toggleResolved(any()) }
    }

    @Test fun lateEventRefreshCannotReplaceAnotherStoryline() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, title = "主线事件")
        val other = source.copy(id = 2, branchId = "B", title = "分支事件")
        coEvery { events.getForBranch(42, "main") } returns listOf(source)
        coEvery { events.getForBranch(42, "B") } returns listOf(other)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "B", sourceMessageId = 8))
        val vm = createViewModel(eventNodeDao = events, sessionBranchDao = branches)
        advanceUntilIdle()
        val reply = CompletableDeferred<List<SessionEventNodeEntity>>()
        coEvery { events.getForBranch(42, "main") } coAnswers { reply.await() }
        vm.toggleEventNodeResolved(1)
        runCurrent()
        vm.switchBranch("B")
        advanceUntilIdle()
        assertEquals("B", vm.state.value.currentBranchId)
        reply.complete(listOf(source.copy(resolved = true)))
        advanceUntilIdle()
        assertEquals(listOf(other), vm.state.value.eventNodes)
        coVerify(exactly = 0) { events.getBySession(any()) }
    }

    @Test fun deletingAnEventDoesNotLoadOtherStorylines() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, title = "当前线事件")
        coEvery { events.getForBranch(42, "main") } returns listOf(source)
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        coEvery { events.getForBranch(42, "main") } returns emptyList()
        vm.deleteEventNode(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.eventNodes.isEmpty())
        coVerify { events.deleteById(1) }
        coVerify(exactly = 0) { events.getBySession(any()) }
    }

    @Test fun lateEventRefreshCannotUndoANewerStatusChange() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, title = "事件")
        coEvery { events.getForBranch(42, "main") } returns listOf(source)
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        val older = CompletableDeferred<List<SessionEventNodeEntity>>()
        var reads = 0
        coEvery { events.getForBranch(42, "main") } coAnswers { if (++reads == 1) older.await() else listOf(source) }
        vm.toggleEventNodeResolved(1)
        runCurrent()
        assertTrue(vm.state.value.eventNodes.single().resolved)
        vm.toggleEventNodeResolved(1)
        advanceUntilIdle()
        assertFalse(vm.state.value.eventNodes.single().resolved)
        older.complete(listOf(source.copy(resolved = true)))
        advanceUntilIdle()
        assertFalse(vm.state.value.eventNodes.single().resolved)
    }

    @Test
    fun recallKeepsTheWindowNearAnOldMessage() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val segmentDao = mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed = true)
        val earlier = com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id = 1, sessionId = 42, endMessageId = 100, summary = "保留")
        val affected = earlier.copy(id = 2, endMessageId = 600, summary = "等待重新整理")
        var deleted = false
        coEvery { segmentDao.getRecentForBranch(42, "main", any()) } answers { if (deleted) listOf(earlier) else listOf(affected, earlier) }
        val target = MessageEntity(id = 500, sessionId = 42, content = "旧消息")
        val neighbor = target.copy(id = 499)
        coEvery { messageDao.getMainMessageById(42L, 500L) } returns target
        coEvery { messageDao.getMainMessagesBefore(42L, 500L, 41) } returns listOf(neighbor)
        coEvery { messageDao.getMainMessageById(42L, 499L) } returns neighbor
        coEvery { messageDao.getMainMessagesBefore(42L, 499L, 41) } returns (498L downTo 457L).map { target.copy(id = it) }
        coEvery { messageDao.getMainMessagesAfter(42L, 499L, 41) } returns (501L..542L).map { target.copy(id = it) }
        coEvery { messageDao.recallInSession(42L, 500L) } answers { deleted = true; MessageRecallResult(true, deletedMessageIds = listOf(500L)) }
        val vm = createViewModel(messageDao = messageDao, memorySegmentDao = segmentDao)
        advanceUntilIdle()
        assertEquals(listOf(affected, earlier), vm.state.value.memorySegments)
        vm.openMessageInHistory(500L)
        advanceUntilIdle()
        val result = CompletableDeferred<Boolean>()
        vm.deleteMessage(500L) { result.complete(it) }
        assertTrue(result.await())
        assertEquals(499L, vm.state.value.focusedMessageId)
        assertTrue(vm.state.value.hasOlderMessages)
        assertTrue(vm.state.value.hasNewerMessages)
        assertEquals(81, vm.state.value.messages.size)
        assertFalse(vm.state.value.messages.any { it.id == 500L })
        assertEquals(listOf(earlier), vm.state.value.memorySegments)
    }

    @Test
    fun recallIsRejectedWhileGenerationOwnsTheSession() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.generateAndAttachUserMessage("雨夜街景")
        assertTrue(vm.state.value.isGenerating)
        val recalled = CompletableDeferred<Boolean>()
        vm.deleteMessage(8L) { recalled.complete(it) }

        assertFalse(recalled.await())
        assertEquals("当前正在生成，请先停止或等待完成后再撤回消息", vm.state.value.error)
        coVerify(exactly = 0) { messageDao.recallInSession(any(), any()) }

        vm.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun branchNavigationIsRejectedWhileGenerationOwnsTheSession() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        coEvery { messageDao.insert(any()) } coAnswers { awaitCancellation() }
        val vm = createViewModel(messageDao = messageDao, sessionBranchDao = branchDao)
        advanceUntilIdle()

        vm.updateInput("正在生成")
        vm.sendMessage()
        vm.switchBranch("other")
        vm.createBranch(1L)
        runCurrent()

        assertEquals("main", vm.state.value.currentBranchId)
        coVerify(exactly = 0) { branchDao.insert(any()) }

        vm.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun creatingBranchPersistsItAsTheSessionReentryTarget() = runTest(testDispatcher) {
        val anchor = MessageEntity(id = 7L, sessionId = 42L, speakerType = "user", content = "从这里分叉")
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val preferences = uiPreferences()
        val branches = mutableListOf<SessionBranchEntity>()
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(anchor)
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns anchor
        coEvery { messageDao.getVisibleMessagesTail(42L, match { it.startsWith("branch_") }, any()) } returns listOf(anchor)
        coEvery { branchDao.getBySession(42L) } answers { branches.toList() }
        coEvery { branchDao.insert(any()) } answers {
            branches += args.first() as SessionBranchEntity
            1L
        }
        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        vm.createBranch(7L)
        advanceUntilIdle()

        assertTrue(vm.state.value.currentBranchId.startsWith("branch_"))
        coVerify(exactly = 1) {
            preferences.setLastChatBranch(42L, match { it.startsWith("branch_") })
        }
    }

    @Test
    fun creatingBranchRejectsAStaleMessageOutsideTheCurrentStoryline() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns null
        val vm = createViewModel(messageDao = messageDao, sessionBranchDao = branchDao)
        advanceUntilIdle()

        vm.createBranch(7L)
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("源消息已不存在或不属于当前故事线", vm.state.value.error)
        coVerify(exactly = 0) { branchDao.insert(any()) }
        coVerify(exactly = 0) { messageDao.getById(7L) }
    }

    @Test
    fun creatingBranchReportsAnchorReadFailureWithoutWriting() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        coEvery { messageDao.getMainMessageById(42L, 7L) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(messageDao = messageDao, sessionBranchDao = branchDao)
        advanceUntilIdle()

        vm.createBranch(7L)
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("故事线创建失败，请重试", vm.state.value.error)
        coVerify(exactly = 0) { branchDao.insert(any()) }
    }

    @Test
    fun creatingBranchReportsCommittedRefreshFailureWithoutInvitingARetry() = runTest(testDispatcher) {
        val anchor = MessageEntity(id = 7L, sessionId = 42L, speakerType = "user", content = "从这里分叉")
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        var committed = false
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns anchor
        coEvery { branchDao.insert(any()) } answers {
            committed = true
            1L
        }
        coEvery { branchDao.getBySession(42L) } answers {
            if (committed) throw IllegalStateException("database unavailable")
            emptyList()
        }
        val vm = createViewModel(messageDao = messageDao, sessionBranchDao = branchDao)
        advanceUntilIdle()

        vm.createBranch(7L)
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("故事线已创建，但列表刷新失败，请重新进入对话", vm.state.value.error)
        coVerify(exactly = 1) { branchDao.insert(any()) }
    }

    @Test
    fun selectingSwipeUsesValidatedAtomicDaoOperation() = runTest(testDispatcher) {
        val target = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "character",
            swipeGroupId = "group",
            content = "目标版本",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns target
        coEvery { messageDao.selectSwipeVariantForBranch(42L, "main", "group", 7L) } returns 0
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.selectSwipeVariant("group", 7L)
        advanceUntilIdle()

        coVerify(exactly = 1) { messageDao.selectSwipeVariantForBranch(42L, "main", "group", 7L) }
        assertEquals("该回复版本已不存在", vm.state.value.error)
    }

    @Test
    fun swipeSelectionUsesCurrentStorylineWithoutChangingFrozenDefault() = runTest(testDispatcher) {
        val branch = SessionBranchEntity(
            sessionId = 42L,
            branchId = "branch-a",
            sourceMessageId = 7L,
        )
        val target = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "character",
            branchId = "main",
            swipeGroupId = "group",
            includeInContext = false,
            content = "分支采用版本",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val segmentDao = mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed = true)
        val earlier = com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id = 1, sessionId = 42, endMessageId = 3, summary = "早期剧情")
        val obsolete = earlier.copy(id = 2, endMessageId = 10, summary = "旧回复摘要")
        var committed = false
        coEvery { segmentDao.getRecentForBranch(42, branch.branchId, any()) } answers {
            if (committed) listOf(earlier) else listOf(obsolete, earlier)
        }
        coEvery { branchDao.getBySession(42L) } returns listOf(branch)
        coEvery { messageDao.getVisibleMessagesTail(42L, branch.branchId, any()) } returns listOf(target)
        coEvery { messageDao.getVisibleMessageById(42L, branch.branchId, target.id) } returns target
        coEvery {
            messageDao.selectSwipeVariantForBranch(42L, branch.branchId, "group", target.id)
        } answers { committed = true; 1 }
        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            memorySegmentDao = segmentDao,
            uiPreferencesRepository = uiPreferences(branch.branchId),
        )
        advanceUntilIdle()

        assertEquals(listOf(obsolete, earlier), vm.state.value.memorySegments)
        val selected = CompletableDeferred<Boolean>()
        vm.selectSwipeVariant("group", target.id) { selected.complete(it) }
        advanceUntilIdle()

        assertTrue(selected.await())
        assertEquals(listOf(earlier), vm.state.value.memorySegments)
        assertEquals(branch.branchId, vm.state.value.currentBranchId)
        coVerify(exactly = 1) {
            messageDao.selectSwipeVariantForBranch(42L, branch.branchId, "group", target.id)
        }
    }

    @Test
    fun committedSwipeSelectionReportsRefreshFailureWithoutInvitingAnotherWrite() = runTest(testDispatcher) {
        val target = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "character",
            swipeGroupId = "group",
            content = "已采用版本",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        var committed = false
        coEvery { messageDao.getMainMessageById(42L, target.id) } returns target
        coEvery { messageDao.selectSwipeVariantForBranch(42L, "main", "group", target.id) } answers {
            committed = true
            1
        }
        coEvery { messageDao.getMainMessagesTail(42L, any()) } answers {
            if (committed) throw IllegalStateException("database unavailable")
            listOf(target)
        }
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        val selected = CompletableDeferred<Boolean>()
        vm.selectSwipeVariant("group", target.id) { selected.complete(it) }
        advanceUntilIdle()

        assertFalse(selected.await())
        assertEquals("回复版本已切换，但对话刷新失败，请重新进入对话", vm.state.value.error)
        coVerify(exactly = 1) {
            messageDao.selectSwipeVariantForBranch(42L, "main", "group", target.id)
        }
    }

    @Test
    fun swipeSelectionIsRejectedWhileGenerationOwnsTheSession() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.generateAndAttachUserMessage("雨夜街景")
        assertTrue(vm.state.value.isGenerating)
        val selected = CompletableDeferred<Boolean>()
        vm.selectSwipeVariant("group", 7L) { selected.complete(it) }

        assertFalse(selected.await())
        assertEquals("当前正在生成，请先停止或等待完成后再切换回复版本", vm.state.value.error)
        coVerify(exactly = 0) { messageDao.selectSwipeVariantForBranch(any(), any(), any(), any()) }

        vm.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun swipeSelectionRejectsAStaleMessageOutsideTheCurrentStoryline() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns null
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        val selected = CompletableDeferred<Boolean>()
        vm.selectSwipeVariant("group", 7L) { selected.complete(it) }

        assertFalse(selected.await())
        assertEquals("该回复版本已不存在或不属于当前故事线", vm.state.value.error)
        coVerify(exactly = 0) { messageDao.selectSwipeVariantForBranch(any(), any(), any(), any()) }
    }

    @Test
    fun editingSwipeReplyKeepsGroupButDoesNotPersistProjectedSelectionFlag() = runTest(testDispatcher) {
        val original = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "character",
            swipeGroupId = "reply-group",
            includeInContext = true,
            content = "原回复",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        var committed = false
        var capturedReplacement: MessageEntity? = null
        coEvery { messageDao.getMainMessageById(42L, original.id) } returns original
        coEvery { branchDao.getBySession(42L) } answers {
            if (committed) throw IllegalStateException("stop after commit")
            emptyList()
        }
        coEvery { branchDao.insertEditedBranch(any(), any(), any()) } answers {
            capturedReplacement = args[1] as MessageEntity
            committed = true
            8L
        }
        val vm = createViewModel(messageDao = messageDao, sessionBranchDao = branchDao)
        advanceUntilIdle()

        vm.editMessage(original.id, "编辑后的回复")
        advanceUntilIdle()

        val replacement = requireNotNull(capturedReplacement)
        assertEquals(original.swipeGroupId, replacement.swipeGroupId)
        assertFalse(replacement.includeInContext)
        assertEquals(original.id, replacement.regeneratedFromMessageId)
        assertEquals("消息已编辑，但后续状态更新失败，请重新进入对话", vm.state.value.error)
    }

    @Test
    fun editingUserMessageCreatesReplacementBranchKeepsOriginalAndStartsRound() = runTest(testDispatcher) {
        val original = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "user",
            content = "原回复",
        )
        val copiedAttachment = MessageAttachmentEntity(
            id = 11L,
            messageId = original.id,
            storagePath = "F:/images/scene.png",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val attachmentDao = mockk<AttachmentDao>(relaxed = true)
        val branches = mutableListOf<SessionBranchEntity>()
        var replacement: MessageEntity? = null
        coEvery { messageDao.getMainMessagesTail(42L, 81) } answers {
            listOfNotNull(replacement ?: original)
        }
        coEvery { messageDao.getVisibleMessagesTail(42L, match { it.startsWith("edit_7_") }, 81) } answers {
            listOfNotNull(replacement)
        }
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns original
        coEvery { attachmentDao.getByMessage(7L) } returns listOf(copiedAttachment)
        coEvery { branchDao.getBySession(42L) } answers { branches.toList() }
        coEvery { branchDao.insertEditedBranch(any(), any(), any()) } answers {
            val branch = args[0] as SessionBranchEntity
            replacement = (args[1] as MessageEntity).copy(id = 99L)
            branches += branch
            99L
        }
        var successCalled = false
        val preferences = uiPreferences()
        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            attachmentDao = attachmentDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        vm.editMessage(7L, "改写后的回复") { successCalled = true }
        advanceUntilIdle()

        assertTrue(successCalled)
        assertTrue(vm.state.value.currentBranchId.startsWith("edit_7_"))
        assertEquals(listOf("改写后的回复"), vm.state.value.messages.map { it.content })
        assertEquals(7L, replacement?.regeneratedFromMessageId)
        assertEquals(null, replacement?.swipeGroupId)
        assertEquals(UserFacingStrings.chatNoParticipant(), vm.state.value.error)
        coVerify(exactly = 1) {
            branchDao.insertEditedBranch(
                match { it.parentBranchId == "main" && it.sourceMessageId == 7L },
                match { it.content == "改写后的回复" && it.branchId.startsWith("edit_7_") },
                match { it.single().id == 11L },
            )
        }
        coVerify(exactly = 1) {
            preferences.setLastChatBranch(42L, match { it.startsWith("edit_7_") })
        }
    }

    @Test
    fun editingUserMessageDoesNotContinueOnTheOldStorylineAfterCommittedRefreshFailure() =
        runTest(testDispatcher) {
            val original = MessageEntity(
                id = 7L,
                sessionId = 42L,
                speakerType = "user",
                content = "原回复",
            )
            val messageDao = mockk<MessageDao>(relaxed = true)
            val branchDao = mockk<SessionBranchDao>(relaxed = true)
            val branches = mutableListOf<SessionBranchEntity>()
            coEvery { messageDao.getMainMessageById(42L, 7L) } returns original
            coEvery { branchDao.getBySession(42L) } answers { branches.toList() }
            coEvery { branchDao.insertEditedBranch(any(), any(), any()) } answers {
                branches += args[0] as SessionBranchEntity
                99L
            }
            coEvery {
                messageDao.getVisibleMessagesTail(42L, match { it.startsWith("edit_7_") }, 81)
            } throws IllegalStateException("database unavailable")
            var successCalled = false
            val preferences = uiPreferences()
            val vm = createViewModel(
                messageDao = messageDao,
                sessionBranchDao = branchDao,
                uiPreferencesRepository = preferences,
            )
            advanceUntilIdle()

            vm.editMessage(7L, "改写后的回复") { successCalled = true }
            advanceUntilIdle()

            assertFalse(successCalled)
            assertFalse(vm.state.value.isGenerating)
            assertEquals("main", vm.state.value.currentBranchId)
            assertEquals("消息已编辑，但后续状态更新失败，请重新进入对话", vm.state.value.error)
            coVerify(exactly = 1) { branchDao.insertEditedBranch(any(), any(), any()) }
            coVerify(exactly = 0) { preferences.setLastChatBranch(any(), any()) }
        }

    @Test
    fun editingUserMessageReportsPreCommitFailureWithoutStartingARound() = runTest(testDispatcher) {
        val original = MessageEntity(
            id = 7L,
            sessionId = 42L,
            speakerType = "user",
            content = "原回复",
        )
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns original
        coEvery { branchDao.insertEditedBranch(any(), any(), any()) } throws
            IllegalStateException("database unavailable")
        var successCalled = false
        val vm = createViewModel(messageDao = messageDao, sessionBranchDao = branchDao)
        advanceUntilIdle()

        vm.editMessage(7L, "改写后的回复") { successCalled = true }
        advanceUntilIdle()

        assertFalse(successCalled)
        assertFalse(vm.state.value.isGenerating)
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("消息编辑失败，请重试", vm.state.value.error)
    }

    @Test
    fun initialAndOlderHistoryUseBoundedKeysetWindows() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val initialRows = (200L downTo 120L).map { id ->
            MessageEntity(id = id, sessionId = 42L, content = "消息$id")
        }
        val olderRows = (119L downTo 79L).map { id ->
            MessageEntity(id = id, sessionId = 42L, content = "消息$id")
        }
        coEvery { messageDao.getMainMessagesTail(42L, 81) } returns initialRows
        coEvery { messageDao.getMainMessagesBefore(42L, 121L, 41) } returns olderRows

        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        assertEquals(80, vm.state.value.messages.size)
        assertEquals(121L, vm.state.value.messages.first().id)
        assertEquals(200L, vm.state.value.messages.last().id)
        assertTrue(vm.state.value.hasOlderMessages)
        coVerify(exactly = 0) { messageDao.getMainBranchMessages(any()) }

        vm.loadOlderMessages()
        advanceUntilIdle()

        assertEquals(120, vm.state.value.messages.size)
        assertEquals(80L, vm.state.value.messages.first().id)
        assertEquals(200L, vm.state.value.messages.last().id)
        assertTrue(vm.state.value.hasOlderMessages)
        assertFalse(vm.state.value.hasNewerMessages)
        coVerify(exactly = 1) {
            messageDao.getMainMessagesBefore(42L, 121L, 41)
        }
    }

    @Test
    fun searchIsBoundedAndOpensUnloadedResultWindow() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val target = MessageEntity(id = 500L, sessionId = 42L, content = "远页钟声")
        val before = (499L downTo 458L).map { id ->
            MessageEntity(id = id, sessionId = 42L, content = "旧消息$id")
        }
        val after = (501L..542L).map { id ->
            MessageEntity(id = id, sessionId = 42L, content = "新消息$id")
        }
        coEvery {
            messageDao.searchMainMessages(42L, "钟声", 0, 101, Long.MAX_VALUE)
        } returns listOf(target)
        coEvery { messageDao.getMainMessageById(42L, 500L) } returns target
        coEvery { messageDao.getMainMessagesBefore(42L, 500L, 41) } returns before
        coEvery { messageDao.getMainMessagesAfter(42L, 500L, 41) } returns after

        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.searchSession("钟声")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5000) { vm.state.first { it.completedSearchQuery == "钟声" } }
        }
        advanceUntilIdle()
        assertEquals(listOf(500L), vm.state.value.searchResults.map { it.id })
        assertFalse(vm.state.value.isSearchingMessages)

        vm.openMessageInHistory(500L)
        advanceUntilIdle()

        assertEquals(81, vm.state.value.messages.size)
        assertEquals(460L, vm.state.value.messages.first().id)
        assertEquals(540L, vm.state.value.messages.last().id)
        assertEquals(500L, vm.state.value.focusedMessageId)
        assertTrue(vm.state.value.hasOlderMessages)
        assertTrue(vm.state.value.hasNewerMessages)
    }

    @Test
    fun clearedOrSupersededSearchCannotPublishLateResults() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { dao.searchMainMessages(42L, "旧", 0, 101, Long.MAX_VALUE) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            listOf(MessageEntity(id = 1, sessionId = 42, content = "旧"))
        }
        coEvery { dao.searchMainMessages(42L, "新", 0, 101, Long.MAX_VALUE) } returns
            listOf(MessageEntity(id = 2, sessionId = 42, content = "新"))
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()
        vm.searchSession("旧")
        runCurrent()
        vm.clearSearch()
        assertEquals("", vm.state.value.completedSearchQuery)
        vm.searchSession("新")
        runCurrent()
        release.complete(Unit)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5000) { vm.state.first { it.completedSearchQuery == "新" } }
        }
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.state.value.searchResults.map { it.id })
        assertEquals("新", vm.state.value.completedSearchQuery)
        assertFalse(vm.state.value.isSearchingMessages)
        vm.clearSearch()
        assertTrue(vm.state.value.searchResults.isEmpty())
    }

    @Test
    fun searchPagesUseLastVisibleIdAndReplaceTheWindow() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val rows = (250L downTo 1L).map { MessageEntity(id = it, sessionId = 42, content = "线索 $it") }
        var failOlder = false
        coEvery { dao.searchMainMessages(42L, "线索", 0, 101, any()) } coAnswers {
            if (failOlder) error("read failed")
            rows.filter { it.id < arg<Long>(4) }.take(101)
        }
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()
        suspend fun awaitPage(firstId: Long) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5000) { vm.state.first { it.searchResults.firstOrNull()?.id == firstId && !it.isSearchingMessages } }
            }
        }
        vm.searchSession("线索")
        awaitPage(250)
        assertEquals(100, vm.state.value.searchResults.size)
        failOlder = true
        vm.olderSearchResults()
        advanceUntilIdle()
        assertEquals(250L, vm.state.value.searchResults.first().id)
        assertTrue(vm.state.value.searchHasOlder)
        assertTrue(vm.state.value.searchError != null)
        failOlder = false
        vm.olderSearchResults()
        awaitPage(150)
        assertEquals(null, vm.state.value.searchError)
        assertEquals(100, vm.state.value.searchResults.size)
        assertEquals(151L, vm.state.value.searchBeforeId)
        vm.olderSearchResults()
        awaitPage(50)
        assertEquals(50, vm.state.value.searchResults.size)
        assertFalse(vm.state.value.searchHasOlder)
        vm.searchSession("线索")
        awaitPage(250)
        assertEquals(Long.MAX_VALUE, vm.state.value.searchBeforeId)
    }

    @Test
    fun tavernImportUsesOneStableAtomicBatchAndSkipsDuplicateRetry() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        coEvery { participantDao.getBySession(42L) } returns listOf(
            SessionParticipantEntity(sessionId = 42L, characterId = 3L),
        )
        coEvery {
            messageDao.insertImportBatchIfAbsent(42L, "main", any(), any())
        } returnsMany listOf(2, 0)
        val vm = createViewModel(messageDao = messageDao, participantDao = participantDao)
        advanceUntilIdle()
        val json = """
            {"messages":[
              {"speakerType":"user","content":"第一句"},
              {"speakerType":"character","content":"第二句"}
            ]}
        """.trimIndent()

        val first = vm.importTavernChatText(json)
        val retry = vm.importTavernChatText(json)

        assertEquals(2, first.importedCount)
        assertFalse(first.duplicate)
        assertEquals(0, retry.importedCount)
        assertTrue(retry.duplicate)
        coVerify(exactly = 2) {
            messageDao.insertImportBatchIfAbsent(
                42L,
                "main",
                match { it.startsWith("\"st_import_batch\":\"") },
                match { rows ->
                    rows.size == 2 &&
                        rows[0].speakerType == "user" && rows[0].characterId == null &&
                        rows[1].speakerType == "character" && rows[1].characterId == 3L &&
                        rows.all { it.structuredContentJson.contains("st_import_batch") }
                },
            )
        }
    }

    @Test
    fun cancelledGenerationCannotOverwriteImmediateRetryState() = runTest(testDispatcher) {
        val oldCleanupRelease = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insert(any()) } coAnswers {
            when ((args.first() as MessageEntity).content) {
                "第一条" -> try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { oldCleanupRelease.await() }
                }
                "第二条" -> {
                    secondStarted.complete(Unit)
                    awaitCancellation()
                }
                else -> 0L
            }
        }
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        vm.updateInput("第一条")
        vm.sendMessage()
        runCurrent()
        assertTrue(vm.state.value.isGenerating)

        vm.stopGeneration()
        runCurrent()
        vm.updateInput("第二条")
        vm.sendMessage()
        runCurrent()
        assertTrue(secondStarted.isCompleted)
        assertTrue(vm.state.value.isGenerating)

        oldCleanupRelease.complete(Unit)
        runCurrent()
        assertTrue("旧任务清理不能结束新的生成", vm.state.value.isGenerating)

        vm.stopGeneration()
        advanceUntilIdle()
    }
}
