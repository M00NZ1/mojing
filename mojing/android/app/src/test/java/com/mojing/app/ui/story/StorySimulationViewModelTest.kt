package com.mojing.app.ui.story

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.StoryOpeningDraftStore
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.StoryOpeningInputDraft
import com.mojing.app.data.StoryOpeningGenerationState
import com.mojing.app.data.UnreadableStoryInputDraft
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.dao.NewSessionWorldOption
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.story.StoryChapter
import com.mojing.app.domain.story.StoryWritingResult
import com.mojing.app.domain.story.StoryWritingProgress
import com.mojing.app.domain.story.StoryWritingUseCase
import com.mojing.app.domain.story.StoryOpeningDraft
import com.mojing.app.domain.story.StoryOpeningRecord
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.ui.session.NewSessionWorldSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorySimulationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pickerFailureDoesNotLoseDraftAndRetryLoadsPage() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
        val characterDao = mockk<CharacterDao>()
        val option = NewSessionWorldOption(0, 7, "王都", 3, 0, 10)
        var fail = true
        coEvery { templateDao.getNewSessionWorldPage(any(), any(), any(), any(), any(), any()) } answers {
            if (fail) throw IllegalStateException("database unavailable")
            listOf(option)
        }
        val viewModel = createViewModel(templateDao, mockk(relaxed = true), characterDao)
        runCurrent()
        viewModel.updatePremise("加冕前夜，证人失踪")
        try {
            viewModel.loadWorldPage("王都", null)
            org.junit.Assert.fail("Expected picker read failure")
        } catch (_: IllegalStateException) { }
        fail = false
        assertEquals(listOf(option), viewModel.loadWorldPage("王都", null).rows)
        assertEquals("加冕前夜，证人失踪", viewModel.state.value.premise)
        coVerify(exactly = 0) { templateDao.getAll() }
        coVerify(exactly = 0) { characterDao.getAll() }
    }

    @Test
    fun emptyPickerPagesAreSuccessfulAndDoNotPreloadLibrary() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
        val characterDao = mockk<CharacterDao>()
        coEvery { templateDao.getNewSessionWorldPage(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { characterDao.getNewSessionPickerPage(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        val viewModel = createViewModel(templateDao, mockk(relaxed = true), characterDao)
        runCurrent()
        assertTrue(viewModel.loadWorldPage("", null).rows.isEmpty())
        assertFalse(viewModel.loadWorldPage("", null).hasMore)
        assertTrue(viewModel.loadCharacterPage(null, "", null).rows.isEmpty())
        coVerify(exactly = 0) { templateDao.getAll() }
        coVerify(exactly = 0) { characterDao.getAll() }
    }

    @Test
    fun changingWorldDropsOnlyIncompatibleCharacters() = runTest(dispatcher) {
        val worlds = mockk<EncyclopediaDao>()
        val characters = mockk<CharacterDao>()
        val world = EncyclopediaEntity(id = 7, name = "王都")
        coEvery { worlds.getById(7) } returns world
        coEvery { characters.existingIdsForNewSession(any(), any()) } answers {
            if (secondArg<Long?>() == 7L) listOf(10L) else listOf(9L, 10L)
        }
        val viewModel = createViewModel(mockk(relaxed = true), worlds, characters)
        runCurrent()
        viewModel.setCharacterSelection(setOf(9L, 10L))
        viewModel.selectWorld(NewSessionWorldSelection(encyclopedia = world))
        runCurrent()
        assertEquals(setOf(10L), viewModel.state.value.selectedCharacterIds)
        assertEquals(setOf(10L), viewModel.state.value.selectedCharacterIdsAvailable)
    }

    @Test
    fun worldPickerReturnsFortyRowsAndUsesCursor() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
        val rows = (1L..41L).map { NewSessionWorldOption(0, it, "世界 $it", 0, 0, 42 - it) }
        coEvery { templateDao.getNewSessionWorldPage(any(), any(), any(), any(), any(), any()) } returns rows
        val viewModel = createViewModel(templateDao, mockk(relaxed = true), mockk(relaxed = true))
        runCurrent()
        val first = viewModel.loadWorldPage(" 世界 ", null)
        assertEquals(40, first.rows.size)
        assertTrue(first.hasMore)
        viewModel.loadWorldPage("", first.rows.last())
        coVerify { templateDao.getNewSessionWorldPage("世界", null, null, null, null, 41) }
        coVerify { templateDao.getNewSessionWorldPage("", 0, 0, 2, 40, 41) }
    }

    @Test
    fun characterPickerKeepsSelectionAcrossPagesAndSearchesDatabase() = runTest(dispatcher) {
        val characters = mockk<CharacterDao>()
        val rows = (1L..41L).map { NewSessionCharacterOption(it, "角色 $it", 0, false, 42 - it) }
        coEvery { characters.getNewSessionPickerPage(any(), any(), any(), any(), any(), any(), any()) } returns rows
        coEvery { characters.existingIdsForNewSession(listOf(41L), null) } returns listOf(41L)
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), characters)
        runCurrent()
        vm.setCharacterSelection(setOf(41L))
        assertEquals(setOf(41L), vm.state.value.selectedCharacterIds)
        val first = vm.loadCharacterPage(null, " 角色 41 ", null)
        assertEquals(40, first.rows.size)
        assertTrue(first.hasMore)
        vm.loadCharacterPage(null, "", first.rows.last())
        assertEquals(setOf(41L), vm.state.value.selectedCharacterIds)
        coVerify { characters.getNewSessionPickerPage(null, "角色 41", null, null, null, null, 41) }
        coVerify { characters.getNewSessionPickerPage(null, "", 0, false, 2, 40, 41) }
    }

    @Test
    fun createStoryWritesChaptersAndOpensSessionWithoutCandidateStep() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao> { coEvery { getWorldMappings() } returns emptyList() }
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        coEvery { templateDao.getAll() } returns emptyList()
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        coEvery { characterDao.getAll() } returns emptyList()
        val storyWriting = mockk<StoryWritingUseCase>()
        coEvery { storyWriting.write(any(), any(), any(), any(), any()) } returns StoryWritingResult(
            title = "十八岁系统",
            chapters = listOf(
                StoryChapter(1, "觉醒", "第一章正文"),
                StoryChapter(2, "任务", "第二章正文"),
            ),
            nextChoices = listOf("调查系统", "完成任务"),
        )
        every { storyWriting.toMessageContent(any(), any()) } answers {
            val chapter = args[0] as StoryChapter
            @Suppress("UNCHECKED_CAST")
            val choices = args[1] as List<String>
            "<NARRATION>${chapter.content}</NARRATION>" + choices.joinToString("") { "<OPTION>$it</OPTION>" }
        }
        every { storyWriting.toStructuredJson(any(), any()) } returns "{}"
        val secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"
            every { publicBaseUrl } returns "https://example.com"
            every { publicModel } returns "model"
        }
        val createSession = mockk<CreateSessionUseCase>()
        val messages = mutableListOf<MessageEntity>()
        coEvery {
            createSession.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { messages.addAll(arg<List<MessageEntity>>(14)); CreateSessionUseCase.Result.Created(42L) }
        val messageDao = mockk<MessageDao>()
        coEvery { messageDao.insert(capture(messages)) } returnsMany listOf(1L, 2L, 3L)
        val sessionDao = mockk<SessionDao>(relaxed = true)
        val viewModel = createViewModel(
            templateDao,
            encyclopediaDao,
            characterDao,
            storyWriting = storyWriting,
            secureStorage = secureStorage,
            createSession = createSession,
            sessionDao = sessionDao,
            messageDao = messageDao,
        )
        runCurrent()
        viewModel.updatePremise("现代社会，主角十八岁觉醒系统")
        var openedSessionId: Long? = null

        viewModel.createStory { openedSessionId = it }
        runCurrent()

        assertEquals(42L, openedSessionId)
        assertEquals(listOf("user", "narrator", "narrator"), messages.map { it.speakerType })
        assertTrue(messages.last().content.contains("<OPTION>调查系统</OPTION>"))
        coVerify(exactly = 0) { messageDao.insert(any()) }
        coVerify(exactly = 0) { sessionDao.bumpUpdatedAt(42L, any()) }
        coVerify(exactly = 1) {
            createSession.create(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                match { worldPrompt ->
                    worldPrompt?.contains("现代社会，主角十八岁觉醒系统") == true &&
                        worldPrompt.contains("其他人物知道")
                },
                any(), any(),
            )
        }
    }

    @Test
    fun stoppingRemoteGenerationKeepsDraftAndDoesNotPersist() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao> { coEvery { getWorldMappings() } returns emptyList() }
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        coEvery { templateDao.getAll() } returns emptyList()
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        coEvery { characterDao.getAll() } returns emptyList()
        val resultGate = CompletableDeferred<StoryWritingResult>()
        val storyWriting = mockk<StoryWritingUseCase>()
        coEvery { storyWriting.write(any(), any(), any(), any(), any()) } coAnswers { resultGate.await() }
        val secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"
            every { publicBaseUrl } returns "https://example.com"
            every { publicModel } returns "model"
        }
        val createSession = mockk<CreateSessionUseCase>(relaxed = true)
        val sessionDao = mockk<SessionDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val viewModel = createViewModel(
            templateDao,
            encyclopediaDao,
            characterDao,
            storyWriting = storyWriting,
            secureStorage = secureStorage,
            createSession = createSession,
            sessionDao = sessionDao,
            messageDao = messageDao,
        )
        runCurrent()
        viewModel.updatePremise("保留的故事背景")
        viewModel.updateDirection("保留的走向")
        viewModel.updateTone("保留的文风")
        viewModel.updateChapterCount(3)
        var createdCalls = 0
        viewModel.createStory { createdCalls++ }
        runCurrent()

        assertTrue(viewModel.state.value.isGenerating)
        assertFalse(viewModel.state.value.isSaving)
        assertTrue(viewModel.stopGeneration())
        runCurrent()

        assertFalse(viewModel.state.value.isGenerating)
        assertFalse(viewModel.state.value.isSaving)
        assertEquals("保留的故事背景", viewModel.state.value.premise)
        assertEquals("保留的走向", viewModel.state.value.direction)
        assertEquals("保留的文风", viewModel.state.value.tone)
        assertEquals(3, viewModel.state.value.chapterCount)
        assertNull(viewModel.state.value.error)
        assertEquals(0, createdCalls)
        coVerify(exactly = 0) {
            createSession.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { messageDao.insert(any()) }
        coVerify(exactly = 0) { sessionDao.bumpUpdatedAt(any(), any()) }
    }

    @Test
    fun stopAndLeaveWaitsForLatestPreviewToPersist() = runTest(dispatcher) {
        val progress = slot<(StoryWritingProgress) -> Unit>()
        val writing = mockk<StoryWritingUseCase>()
        coEvery { writing.write(any(), any(), any(), any(), capture(progress)) } coAnswers {
            CompletableDeferred<StoryWritingResult>().await()
        }
        val saveGate = CompletableDeferred<Unit>()
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) {
            coEvery { load() } returns null
            coEvery { persistGenerationPreview(any()) } coAnswers { saveGate.await() }
        }
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            storyWriting = writing, secureStorage = storage, inputDraftStore = drafts)
        runCurrent(); vm.updatePremise("雾港来信"); vm.createStory {}; runCurrent()
        progress.captured(StoryWritingProgress("接收正文", "model", 100, 20, 4, "已收到正文"))

        val leaving = async { vm.stopGenerationAndWaitForPreview() }
        runCurrent()
        assertFalse(leaving.isCompleted)
        saveGate.complete(Unit)
        assertTrue(leaving.await())
        coVerify(atLeast = 1) { drafts.persistGenerationPreview(match { it.preview == "已收到正文" }) }
    }

    @Test
    fun stopAndLeaveKeepsPreviewVisibleWhenSavingFails() = runTest(dispatcher) {
        val progress = slot<(StoryWritingProgress) -> Unit>()
        val writing = mockk<StoryWritingUseCase>()
        coEvery { writing.write(any(), any(), any(), any(), capture(progress)) } coAnswers {
            CompletableDeferred<StoryWritingResult>().await()
        }
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) {
            coEvery { load() } returns null
            coEvery { persistGenerationPreview(any()) } throws java.io.IOException("disk")
        }
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            storyWriting = writing, secureStorage = storage, inputDraftStore = drafts)
        runCurrent(); vm.updatePremise("雾港来信"); vm.createStory {}; runCurrent()
        progress.captured(StoryWritingProgress("接收正文", "model", 100, 20, 4, "已收到正文"))

        assertFalse(vm.stopGenerationAndWaitForPreview())
        assertEquals("已收到正文", vm.state.value.preview)
        assertTrue(vm.state.value.error.orEmpty().contains("复制预览"))
        assertFalse(vm.flushInputDraftBeforeLeaving())
    }

    @Test
    fun repeatedCreateStoryUsesOneCreationJob() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao> { coEvery { getWorldMappings() } returns emptyList() }
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        coEvery { templateDao.getAll() } returns emptyList()
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        coEvery { characterDao.getAll() } returns emptyList()
        val resultGate = CompletableDeferred<StoryWritingResult>()
        val storyWriting = mockk<StoryWritingUseCase>()
        coEvery { storyWriting.write(any(), any(), any(), any(), any()) } coAnswers { resultGate.await() }
        val secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"
            every { publicBaseUrl } returns "https://example.com"
            every { publicModel } returns "model"
        }
        val viewModel = createViewModel(
            templateDao,
            encyclopediaDao,
            characterDao,
            storyWriting = storyWriting,
            secureStorage = secureStorage,
        )
        runCurrent()
        viewModel.updatePremise("单飞故事")
        viewModel.createStory { }
        viewModel.createStory { }
        runCurrent()

        coVerify(exactly = 1) { storyWriting.write(any(), any(), any(), any(), any()) }
        assertTrue(viewModel.stopGeneration())
        runCurrent()
    }

    @Test
    fun generationResultIsDiscardedWhenInputChangesWhileWaiting() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao> { coEvery { getWorldMappings() } returns emptyList() }
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        coEvery { templateDao.getAll() } returns emptyList()
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        coEvery { characterDao.getAll() } returns emptyList()
        val resultGate = CompletableDeferred<StoryWritingResult>()
        val storyWriting = mockk<StoryWritingUseCase>()
        coEvery { storyWriting.write(any(), any(), any(), any(), any()) } coAnswers { resultGate.await() }
        val secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"
            every { publicBaseUrl } returns "https://example.com"
            every { publicModel } returns "model"
        }
        val viewModel = createViewModel(
            templateDao,
            encyclopediaDao,
            characterDao,
            storyWriting = storyWriting,
            secureStorage = secureStorage,
        )
        runCurrent()
        viewModel.updatePremise("旧梗概")
        viewModel.createStory { }
        viewModel.updatePremise("新梗概")

        resultGate.complete(
            StoryWritingResult(
                title = "旧结果",
                chapters = listOf(StoryChapter(1, "第一章", "旧正文")),
                nextChoices = listOf("继续", "转折"),
            ),
        )
        runCurrent()

        assertEquals("输入或绑定已变化，请重新生成", viewModel.state.value.error)
    }

    @Test
    fun staleGenerationFailureDoesNotOverwriteChangedDraft() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao> { coEvery { getWorldMappings() } returns emptyList() }
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        coEvery { templateDao.getAll() } returns emptyList()
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        coEvery { characterDao.getAll() } returns emptyList()
        val resultGate = CompletableDeferred<StoryWritingResult>()
        val storyWriting = mockk<StoryWritingUseCase>()
        coEvery { storyWriting.write(any(), any(), any(), any(), any()) } coAnswers { resultGate.await() }
        val secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"
            every { publicBaseUrl } returns "https://example.com"
            every { publicModel } returns "model"
        }
        val viewModel = createViewModel(
            templateDao,
            encyclopediaDao,
            characterDao,
            storyWriting = storyWriting,
            secureStorage = secureStorage,
        )
        runCurrent()
        viewModel.updatePremise("旧梗概")
        viewModel.createStory { }
        viewModel.updatePremise("新梗概")

        resultGate.completeExceptionally(IllegalStateException("旧请求失败"))
        runCurrent()

        assertEquals("新梗概", viewModel.state.value.premise)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.isGenerating)
    }

    @Test
    fun singleChapterFormatFailureKeepsInputAndCanRetryWithoutWritingMessages() = runTest(dispatcher) {
        val writing = mockk<StoryWritingUseCase>()
        val error = "模型返回的章节格式不正确，请重试"
        coEvery { writing.write(any(), any(), any(), any(), any()) } throws
            com.mojing.app.domain.story.StoryWritingException(error)
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "test-key"
            every { publicBaseUrl } returns "https://example.com"
            every { publicModel } returns "test-model"
        }
        val messages = mockk<MessageDao>(relaxed = true)
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            storyWriting = writing, secureStorage = storage, messageDao = messages)
        runCurrent()
        vm.updatePremise("雾港的一封来信")
        vm.updateChapterCount(1)
        repeat(2) {
            vm.createStory { throw AssertionError("Failed generation must not navigate") }
            runCurrent()
            assertEquals(error, vm.state.value.error)
            assertEquals("雾港的一封来信", vm.state.value.premise)
            assertEquals(1, vm.state.value.chapterCount)
            assertFalse(vm.state.value.isGenerating)
            assertFalse(vm.state.value.isSaving)
        }
        coVerify(exactly = 2) { writing.write(any(), any(), any(), match { it.chapterCount == 1 }, any()) }
        coVerify(exactly = 0) { messages.insert(any()) }
    }

    @Test
    fun stoppingAfterPreviewKeepsPreviewAndLateCallbackCannotMutateIt() = runTest(dispatcher) {
        val callback = slot<(StoryWritingProgress) -> Unit>()
        val writing = mockk<StoryWritingUseCase>()
        val gate = CompletableDeferred<StoryWritingResult>()
        coEvery { writing.write(any(), any(), any(), any(), capture(callback)) } coAnswers { gate.await() }
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), storyWriting = writing, secureStorage = storage)
        runCurrent(); vm.updatePremise("保留预览")
        vm.createStory { }; runCurrent()
        callback.captured(StoryWritingProgress("接收正文", "model", 120, 40, 4, "已收到"))
        assertEquals("已收到", vm.state.value.preview)
        assertTrue(vm.stopGeneration()); runCurrent()
        callback.captured(StoryWritingProgress("接收正文", "model", 999, 40, 99, "迟到回调"))
        assertEquals("已收到", vm.state.value.preview)
        assertEquals("已停止", vm.state.value.generationStage)
    }

    @Test
    fun lateCallbackFromStoppedOpeningCannotReplaceRetryRecoveryPreview() = runTest(dispatcher) {
        val callbacks = mutableListOf<(StoryWritingProgress) -> Unit>()
        val writing = mockk<StoryWritingUseCase>()
        coEvery { writing.write(any(), any(), any(), any(), any()) } coAnswers {
            callbacks += arg<(StoryWritingProgress) -> Unit>(4)
            CompletableDeferred<StoryWritingResult>().await()
        }
        val saved = mutableListOf<StoryOpeningGenerationState>()
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) {
            coEvery { load() } returns null
            coEvery { persistGenerationPreview(capture(saved)) } returns Unit
        }
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            storyWriting = writing, secureStorage = storage, inputDraftStore = drafts)
        runCurrent(); vm.updatePremise("雾港来信")

        vm.createStory {}; runCurrent()
        callbacks[0](StoryWritingProgress("接收正文", "model", 100, 20, 4, "旧预览"))
        assertTrue(vm.stopGeneration()); runCurrent()
        assertEquals("旧预览", saved.last().preview)
        val firstRequestId = saved.last().requestId

        vm.createStory {}; runCurrent()
        callbacks[1](StoryWritingProgress("接收正文", "model", 100, 20, 4, "新预览"))
        callbacks[0](StoryWritingProgress("接收正文", "model", 200, 20, 999, "迟到的旧正文"))
        assertEquals("新预览", vm.state.value.preview)
        assertTrue(vm.stopGeneration()); runCurrent()
        assertEquals("新预览", saved.last().preview)
        assertTrue(saved.last().requestId != firstRequestId)
    }

    @Test
    fun completeResultOnlyThenPersistsAndFailureDoesNotPersist() = runTest(dispatcher) {
        val writing = mockk<StoryWritingUseCase>()
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val create = mockk<CreateSessionUseCase>()
        coEvery { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CreateSessionUseCase.Result.Created(8)
        val messages = mockk<MessageDao>(relaxed = true)
        every { writing.toMessageContent(any(), any()) } returns "complete"
        every { writing.toStructuredJson(any(), any()) } returns "{}"
        coEvery { writing.write(any(), any(), any(), any(), any()) } returns StoryWritingResult("完整", listOf(StoryChapter(1, "一", "正文")), listOf("继续", "离开"))
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), storyWriting = writing, secureStorage = storage, createSession = create, messageDao = messages)
        runCurrent(); vm.updatePremise("完整故事"); vm.createStory { }; runCurrent()
        coVerify(exactly = 1) { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), match { it.size == 2 }, any()) }
        coVerify(exactly = 0) { messages.insert(any()) }

        val failed = mockk<StoryWritingUseCase>()
        coEvery { failed.write(any(), any(), any(), any(), any()) } throws IllegalStateException("failed")
        val failedMessages = mockk<MessageDao>(relaxed = true)
        val vmFailed = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), storyWriting = failed, secureStorage = storage, messageDao = failedMessages)
        runCurrent(); vmFailed.updatePremise("失败故事"); vmFailed.createStory { }; runCurrent()
        coVerify(exactly = 0) { failedMessages.insert(any()) }
        assertEquals("失败", vmFailed.state.value.generationStage)
    }

    @Test
    fun attemptProgressIsShownWhileWaiting() = runTest(dispatcher) {
        val callback = slot<(StoryWritingProgress) -> Unit>()
        val gate = CompletableDeferred<StoryWritingResult>()
        val writing = mockk<StoryWritingUseCase>()
        coEvery { writing.write(any(), any(), any(), any(), capture(callback)) } coAnswers {
            callback.captured(StoryWritingProgress("等待模型响应（第 2 次）", "model", 1_234, null, 0, "", 2, 500))
            gate.await()
        }
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), storyWriting = writing, secureStorage = storage)
        runCurrent(); vm.updatePremise("等待故事"); vm.createStory { }; runCurrent()
        assertEquals("等待模型响应（第 2 次）", vm.state.value.generationStage)
        assertEquals(1_234L, vm.state.value.generationElapsedMs)
        vm.stopGeneration(); runCurrent()
    }

    @Test
    fun failedSaveKeepsFullStoryAndRetriesWithoutAnotherModelRequest() = runTest(dispatcher) {
        val writing = mockk<StoryWritingUseCase>(relaxed = true)
        val fullBody = "完整正文".repeat(4_000) + "正文结尾"
        coEvery { writing.write(any(), any(), any(), any(), any()) } returns StoryWritingResult(
            "小说", listOf(StoryChapter(1, "第一章", fullBody)), listOf("去码头", "找守塔人"))
        every { writing.toMessageContent(any(), any()) } returns fullBody
        every { writing.toStructuredJson(any(), any()) } returns "{}"
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val create = mockk<CreateSessionUseCase>()
        var attempts = 0
        val saveGate = CompletableDeferred<Unit>()
        val packets = mutableListOf<List<MessageEntity>>()
        coEvery { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            packets += arg<List<MessageEntity>>(14)
            if (++attempts == 1) throw IllegalStateException("disk full")
            saveGate.await()
            CreateSessionUseCase.Result.Created(42L)
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), writing, storage, create)
        vm.updatePremise("雾港灯塔")
        vm.createStory { error("must not open failed save") }
        runCurrent()
        assertTrue(vm.state.value.hasPendingStory)
        assertFalse(vm.state.value.isSaving)
        assertTrue(vm.pendingStoryText().contains(fullBody))
        assertTrue(vm.pendingStoryText().contains("去码头"))
        vm.updatePremise("不应替换待保存设定")
        assertEquals("雾港灯塔", vm.state.value.premise)
        every { storage.publicApiKey } throws IllegalStateException("model configuration changed")
        var opened: Long? = null
        vm.createStory { opened = it }
        vm.createStory { opened = it }
        assertTrue(vm.state.value.isSaving)
        assertFalse(vm.discardPendingStory())
        saveGate.complete(Unit); runCurrent()
        assertEquals(42L, opened)
        assertFalse(vm.state.value.hasPendingStory)
        assertEquals(42L, vm.state.value.savedSessionId)
        assertEquals(packets[0].map { it.content }, packets[1].map { it.content })
        coVerify(exactly = 1) { writing.write(any(), any(), any(), any(), any()) }
        coVerify(exactly = 2) { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun failedGenerationCleanupDoesNotDiscardPendingFullStory() = runTest(dispatcher) {
        val body = "待保存的完整正文".repeat(4_000)
        val draft = StoryOpeningDraft(premise = "雾港", direction = "", tone = "悬疑", template = null,
            encyclopediaId = null, characterIds = emptyList(), worldPrompt = "雾港规则",
            result = StoryWritingResult("雾港", listOf(StoryChapter(1, "来信", body)), emptyList()), model = "原模型")
        val stored = mockk<StoryOpeningDraftStore>(relaxed = true) {
            coEvery { load() } returns StoryOpeningRecord.Pending(draft)
        }
        var cleanupFails = true
        val inputs = mockk<StoryOpeningInputDraftStore>(relaxed = true) {
            coEvery { clearGeneration() } coAnswers {
                if (cleanupFails) throw IllegalStateException("storage unavailable")
            }
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            draftStore = stored, inputDraftStore = inputs)
        runCurrent()

        assertFalse(vm.discardPendingStory())
        assertTrue(vm.state.value.hasPendingStory)
        assertTrue(vm.pendingStoryText().contains(body))
        coVerify(exactly = 0) { stored.discard(draft.id) }

        cleanupFails = false
        assertTrue(vm.discardPendingStory())
        assertFalse(vm.state.value.hasPendingStory)
        coVerify(exactly = 1) { stored.discard(draft.id) }
    }

    @Test
    fun navigationFailureReopensSavedIdWithoutCreatingAnotherSession() = runTest(dispatcher) {
        val writing = mockk<StoryWritingUseCase>(relaxed = true)
        coEvery { writing.write(any(), any(), any(), any(), any()) } returns StoryWritingResult("小说",
            listOf(StoryChapter(1, "第一章", "正文")), listOf("甲", "乙"))
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val create = mockk<CreateSessionUseCase>()
        coEvery { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CreateSessionUseCase.Result.Created(9L)
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), writing, storage, create)
        vm.updatePremise("故事")
        vm.createStory { throw IllegalStateException("navigation failed") }; runCurrent()
        assertEquals(9L, vm.state.value.savedSessionId)
        assertTrue(vm.state.value.error.orEmpty().contains("已保存"))
        var reopened: Long? = null
        vm.createStory { reopened = it }
        assertEquals(9L, reopened)
        assertNull(vm.state.value.error)
        coVerify(exactly = 1) { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { writing.write(any(), any(), any(), any(), any()) }
    }

    @Test
    fun restoredDraftSavesWithoutReadingModelCredentialsOrGeneratingAgain() = runTest(dispatcher) {
        val draft = StoryOpeningDraft(premise = "雾港灯塔", direction = "调查", tone = "悬疑", template = null,
            encyclopediaId = null, characterIds = emptyList(), worldPrompt = "灯塔规则",
            result = StoryWritingResult("雾港", listOf(StoryChapter(1, "来信", "完整正文".repeat(4000))), listOf("去码头")), model = "原模型")
        val store = mockk<StoryOpeningDraftStore>(relaxed = true) { coEvery { load() } returns StoryOpeningRecord.Pending(draft) }
        val storage = mockk<SecureStorage>()
        val writing = mockk<StoryWritingUseCase>(relaxed = true)
        val create = mockk<CreateSessionUseCase>()
        coEvery { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), draft.id) } returns CreateSessionUseCase.Result.Created(19L)
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), writing, storage, create, draftStore = store)
        runCurrent()
        assertTrue(vm.state.value.recoveredStory)
        assertTrue(vm.state.value.draftPersisted)
        assertEquals("雾港灯塔", vm.state.value.premise)
        assertTrue(vm.pendingStoryText().contains(draft.result.chapters.single().content))
        var opened: Long? = null
        vm.createStory { opened = it }; runCurrent()
        assertEquals(19L, opened)
        coVerify(exactly = 0) { writing.write(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { store.persist(draft) }
    }

    @Test
    fun failedDraftPersistenceRetainsFullBodyAndDoesNotCreateSession() = runTest(dispatcher) {
        val store = mockk<StoryOpeningDraftStore>(relaxed = true) {
            coEvery { load() } returns null
            coEvery { persist(any()) } throws IllegalStateException("disk unavailable")
        }
        val writing = mockk<StoryWritingUseCase>(relaxed = true)
        coEvery { writing.write(any(), any(), any(), any(), any()) } returns StoryWritingResult("小说", listOf(StoryChapter(1, "第一章", "正文")), listOf("继续"))
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val create = mockk<CreateSessionUseCase>()
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), writing, storage, create, draftStore = store)
        runCurrent(); vm.updatePremise("故事"); vm.createStory {}; runCurrent()
        assertTrue(vm.state.value.hasPendingStory)
        assertFalse(vm.state.value.draftPersisted)
        assertTrue(vm.pendingStoryText().contains("正文"))
        coVerify(exactly = 0) { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun savedReceiptOpensOriginalSessionAndNewStoryClearsOnlyReceipt() = runTest(dispatcher) {
        val id = java.util.UUID.randomUUID().toString()
        val store = mockk<StoryOpeningDraftStore>(relaxed = true) { coEvery { load() } returns StoryOpeningRecord.Saved(id, 18, "上次创作") }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), draftStore = store)
        runCurrent()
        var opened: Long? = null
        vm.createStory { opened = it }
        assertEquals(18L, opened)
        vm.startNewStory(); runCurrent()
        assertNull(vm.state.value.savedSessionId)
        assertFalse(vm.state.value.isRestoring)
        coVerify(exactly = 1) { store.clearSavedReceipt(id) }
    }

    @Test
    fun inputDraftRestoresBeforeEditingAndKeepsSelections() = runTest(dispatcher) {
        val input = StoryOpeningInputDraft("雾港灯塔", "先调查失踪者", "克制", 3, null, 7L, setOf(9L))
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) { coEvery { load() } returns input }
        val worlds = mockk<EncyclopediaDao> { coEvery { getById(7) } returns EncyclopediaEntity(id = 7, name = "雾港") }
        var availableCharacters = listOf(9L)
        val characters = mockk<CharacterDao> {
            coEvery { existingIdsForNewSession(listOf(9L), 7) } answers { availableCharacters }
        }
        val vm = createViewModel(mockk(relaxed = true), worlds, characters, inputDraftStore = drafts)
        runCurrent()
        assertTrue(vm.state.value.recoveredInputDraft)
        assertEquals("雾港灯塔", vm.state.value.premise)
        assertEquals("先调查失踪者", vm.state.value.direction)
        assertEquals(3, vm.state.value.chapterCount)
        assertEquals(7L, vm.state.value.selectedEncyclopediaId)
        assertEquals(setOf(9L), vm.state.value.selectedCharacterIds)
        vm.updateDirection("先去码头")
        coVerify { drafts.save(match { it.direction == "先去码头" && it.encyclopediaId == 7L && it.characterIds == setOf(9L) }) }
        assertTrue(vm.flushInputDraftBeforeLeaving())
        coVerify(exactly = 1) { drafts.commit(match { it.direction == "先去码头" && it.encyclopediaId == 7L && it.characterIds == setOf(9L) }) }
        availableCharacters = emptyList()
        vm.retrySelections(); runCurrent()
        assertEquals(setOf(9L), vm.state.value.selectedCharacterIds)
        assertTrue(vm.state.value.hasUnavailableSelections())
        vm.clearUnavailableSelections()
        assertTrue(vm.state.value.selectedCharacterIds.isEmpty())
        coVerify { drafts.save(match { it.characterIds.isEmpty() && it.direction == "先去码头" }) }
    }

    @Test
    fun inputDraftMustBeDurableBeforeModelRequest() = runTest(dispatcher) {
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) {
            coEvery { load() } returns null
            coEvery { commit(any()) } throws IllegalStateException("disk full")
        }
        val writing = mockk<StoryWritingUseCase>(relaxed = true)
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), writing, storage,
            inputDraftStore = drafts)
        runCurrent(); vm.updatePremise("雾港灯塔"); vm.createStory {}; runCurrent()
        assertFalse(vm.state.value.isGenerating)
        assertEquals("暂存失败", vm.state.value.generationStage)
        coVerify(exactly = 0) { writing.write(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { drafts.commit(match { it.premise == "雾港灯塔" }) }
    }

    @Test
    fun clearingInputDraftPreservesReferenceListsAndSavedReceiptClearsOldInput() = runTest(dispatcher) {
        val input = StoryOpeningInputDraft("旧设定", "旧走向", "悬疑", 2, null, null, emptySet())
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) { coEvery { load() } returns input }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), inputDraftStore = drafts)
        runCurrent()
        assertTrue(vm.clearInputDraft())
        assertEquals("", vm.state.value.premise)
        assertFalse(vm.state.value.hasInputDraft)
        coVerify(exactly = 1) { drafts.clear() }

        val id = java.util.UUID.randomUUID().toString()
        val receipt = mockk<StoryOpeningDraftStore>(relaxed = true) { coEvery { load() } returns StoryOpeningRecord.Saved(id, 18L, "已保存") }
        val savedVm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            draftStore = receipt, inputDraftStore = drafts)
        runCurrent(); savedVm.startNewStory(); runCurrent()
        coVerifyOrder { drafts.clear(); receipt.clearSavedReceipt(id) }
    }

    @Test
    fun unreadableInputDraftIsPreservedUntilExplicitDiscard() = runTest(dispatcher) {
        val raw = "{corrupt input draft}"
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) {
            coEvery { load() } throws UnreadableStoryInputDraft(raw)
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), inputDraftStore = drafts)
        runCurrent()
        assertTrue(vm.state.value.canCopyRecoveryData)
        assertEquals(raw, vm.recoveryDataText())
        coVerify(exactly = 0) { drafts.clear() }
        assertTrue(vm.discardUnreadableDraft())
        coVerify(exactly = 1) { drafts.discardUnreadable(raw) }
        assertNull(vm.state.value.recoveryError)
    }

    @Test
    fun interruptedGenerationRestoresBoundInputAndPreviewWithoutPretendingCompletion() = runTest(dispatcher) {
        val generation = StoryOpeningGenerationState(
            requestId = "request-1",
            input = StoryOpeningInputDraft("雾港灯塔", "先调查失踪者", "克制", 2, null, 7L, setOf(9L)),
            preview = "已经收到的开篇片段",
            model = "原模型",
            stage = "生成正文",
            receivedChars = 9,
            elapsedMs = 1_200,
        )
        val drafts = mockk<StoryOpeningInputDraftStore>(relaxed = true) {
            coEvery { load() } throws UnreadableStoryInputDraft("旧输入已损坏")
            every { loadGeneration() } returns generation
        }
        val vm = createViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), inputDraftStore = drafts)
        runCurrent()

        assertTrue(vm.state.value.hasInterruptedGeneration)
        assertEquals("上次生成中断", vm.state.value.generationStage)
        assertEquals("雾港灯塔", vm.state.value.premise)
        assertEquals("已经收到的开篇片段", vm.pendingStoryText())
        assertFalse(vm.state.value.hasPendingStory)
        assertNull(vm.state.value.savedSessionId)

        vm.retryInterruptedGeneration {}
        runCurrent()
        assertTrue(vm.state.value.hasInterruptedGeneration)
        assertEquals("已经收到的开篇片段", vm.pendingStoryText())

        vm.discardInterruptedGeneration()
        runCurrent()
        assertFalse(vm.state.value.hasInterruptedGeneration)
        coVerify { drafts.clearGeneration() }
    }

    @Test
    fun refreshedReferenceDataDoesNotDiscardResultOrReplaceSubmittedWorld() = runTest(dispatcher) {
        val templates = mockk<WorldTemplateDao> { coEvery { getWorldMappings() } returns emptyList() }
        val characters = mockk<CharacterDao>()
        var template = WorldTemplateEntity(id = 7, templateId = "harbor", label = "旧世界", worldPrompt = "旧规则")
        var character = CharacterEntity(id = 8, name = "守塔人", personaPrompt = "旧人物设定")
        coEvery { templates.getById(7) } answers { template }
        coEvery { characters.getById(8) } answers { character }
        coEvery { characters.existingIdsForNewSession(listOf(8L), null) } returns listOf(8L)
        val gate = CompletableDeferred<StoryWritingResult>()
        val writing = mockk<StoryWritingUseCase>(relaxed = true)
        coEvery { writing.write(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val create = mockk<CreateSessionUseCase>()
        coEvery { create.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CreateSessionUseCase.Result.Created(27)
        val stored = slot<StoryOpeningDraft>()
        val drafts = mockk<StoryOpeningDraftStore>(relaxed = true) {
            coEvery { load() } returns null
            coEvery { persist(capture(stored)) } returns Unit
        }
        val vm = createViewModel(templates, mockk(relaxed = true), characters, writing, storage, create, draftStore = drafts)
        runCurrent(); vm.updatePremise("灯塔来信")
        vm.selectWorld(NewSessionWorldSelection(template = template)); vm.setCharacterSelection(setOf(8L))
        val revision = vm.state.value.inputRevision
        var opened: Long? = null
        vm.createStory { opened = it }; runCurrent()
        template = template.copy(label = "新世界", worldPrompt = "新规则")
        character = character.copy(personaPrompt = "新人物设定")
        vm.retrySelections(); runCurrent()
        vm.updatePremise("灯塔来信") // A no-op input callback is not an edit.
        assertEquals(revision, vm.state.value.inputRevision)
        gate.complete(StoryWritingResult("来信", listOf(StoryChapter(1, "第一章", "完整正文")), listOf("继续")))
        runCurrent()
        assertEquals(27L, opened)
        assertTrue(stored.captured.worldPrompt.contains("旧规则"))
        assertTrue(stored.captured.worldPrompt.contains("旧人物设定"))
        assertFalse(stored.captured.worldPrompt.contains("新规则"))
        assertEquals("旧世界", stored.captured.template?.label)
        assertNull(vm.state.value.error)
    }

    @Test
    fun referenceRefreshDoesNotHideRequestFailure() = runTest(dispatcher) {
        val templates = mockk<WorldTemplateDao> { coEvery { getWorldMappings() } returns emptyList() }
        var rows = listOf(WorldTemplateEntity(id = 7, templateId = "harbor", label = "世界"))
        coEvery { templates.getById(7) } answers { rows.firstOrNull() }
        val gate = CompletableDeferred<StoryWritingResult>()
        val writing = mockk<StoryWritingUseCase>()
        coEvery { writing.write(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { publicApiKey } returns "key"; every { publicBaseUrl } returns "https://example.com"; every { publicModel } returns "model"
        }
        val vm = createViewModel(templates, mockk(relaxed = true), mockk(relaxed = true), writing, storage)
        runCurrent(); vm.updatePremise("灯塔")
        vm.selectWorld(NewSessionWorldSelection(template = rows.first())); vm.createStory {}; runCurrent()
        rows = emptyList(); vm.retrySelections(); runCurrent()
        assertEquals(7L, vm.state.value.selectedTemplateId)
        assertTrue(vm.state.value.hasUnavailableSelections())
        gate.completeExceptionally(IllegalStateException("request failed")); runCurrent()
        assertFalse(vm.state.value.isGenerating)
        assertEquals("失败", vm.state.value.generationStage)
        assertTrue(vm.state.value.error.orEmpty().isNotBlank())
    }

    private fun createViewModel(
        templateDao: WorldTemplateDao,
        encyclopediaDao: EncyclopediaDao,
        characterDao: CharacterDao,
        storyWriting: StoryWritingUseCase = mockk(relaxed = true),
        secureStorage: SecureStorage = mockk(relaxed = true),
        createSession: CreateSessionUseCase = mockk(relaxed = true),
        sessionDao: SessionDao = mockk(relaxed = true),
        messageDao: MessageDao = mockk(relaxed = true),
        draftStore: StoryOpeningDraftStore = mockk(relaxed = true) { coEvery { load() } returns null },
        inputDraftStore: StoryOpeningInputDraftStore = mockk(relaxed = true) { coEvery { load() } returns null },
    ) = StorySimulationViewModel(
        storyWriting = storyWriting,
        secureStorage = secureStorage,
        templateDao = templateDao,
        encyclopediaDao = encyclopediaDao,
        characterDao = characterDao,
        createSession = createSession,
        draftStore = draftStore,
        inputDraftStore = inputDraftStore,
    )
}
