package com.mojing.app.ui.story

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.StoryOpeningDraftStore
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.WorldTemplateDao
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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
    fun oneOptionFailureDoesNotBlockOthersAndRetryKeepsDraft() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        var failTemplates = true
        coEvery { templateDao.getAll() } answers {
            if (failTemplates) throw IllegalStateException("database unavailable")
            listOf(WorldTemplateEntity(id = 3, templateId = "court", label = "宫廷"))
        }
        coEvery { encyclopediaDao.getAll() } returns listOf(EncyclopediaEntity(id = 7, name = "王都"))
        coEvery { characterDao.getAll() } returns listOf(
            CharacterEntity(id = 9, name = "林岚", boundEncyclopediaId = 7),
        )

        val viewModel = createViewModel(templateDao, encyclopediaDao, characterDao)
        runCurrent()

        assertTrue(viewModel.state.value.templates.error?.contains("世界模板加载失败") == true)
        assertEquals(1, viewModel.state.value.encyclopedias.items.size)
        assertEquals(1, viewModel.state.value.characters.items.size)
        viewModel.updatePremise("加冕前夜，证人失踪")
        viewModel.updateDirection("偏政治博弈")
        viewModel.updateTone("克制、缓慢")

        failTemplates = false
        viewModel.retryTemplates()
        runCurrent()

        assertNull(viewModel.state.value.templates.error)
        assertEquals("宫廷", viewModel.state.value.templates.items.single().label)
        assertEquals("加冕前夜，证人失踪", viewModel.state.value.premise)
        assertEquals("偏政治博弈", viewModel.state.value.direction)
        assertEquals("克制、缓慢", viewModel.state.value.tone)
        coVerify(exactly = 2) { templateDao.getAll() }
        coVerify(exactly = 1) { encyclopediaDao.getAll() }
        coVerify(exactly = 1) { characterDao.getAll() }
    }

    @Test
    fun loadedEmptyIsNotReportedAsFailure() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        coEvery { templateDao.getAll() } returns emptyList()
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        coEvery { characterDao.getAll() } returns emptyList()

        val viewModel = createViewModel(templateDao, encyclopediaDao, characterDao)
        runCurrent()

        listOf(
            viewModel.state.value.templates,
            viewModel.state.value.encyclopedias,
            viewModel.state.value.characters,
        ).forEach { optionState ->
            assertFalse(optionState.isLoading)
            assertNull(optionState.error)
            assertTrue(optionState.items.isEmpty())
        }
    }

    @Test
    fun characterRetryDropsSelectionsThatAreNoLongerAvailable() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        var characters = listOf(CharacterEntity(id = 9, name = "林岚", boundEncyclopediaId = 7))
        coEvery { templateDao.getAll() } returns emptyList()
        coEvery { encyclopediaDao.getAll() } returns listOf(EncyclopediaEntity(id = 7, name = "王都"))
        coEvery { characterDao.getAll() } answers { characters }

        val viewModel = createViewModel(templateDao, encyclopediaDao, characterDao)
        runCurrent()
        viewModel.selectEncyclopedia(7)
        viewModel.toggleCharacter(9)
        assertEquals(setOf(9L), viewModel.state.value.selectedCharacterIds)

        characters = emptyList()
        viewModel.retryCharacters()
        runCurrent()

        assertTrue(viewModel.state.value.selectedCharacterIds.isEmpty())
        assertTrue(viewModel.state.value.characters.items.isEmpty())
    }

    @Test
    fun repeatedRetryWhileLoadingKeepsOneDaoRequest() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
        val encyclopediaDao = mockk<EncyclopediaDao>()
        val characterDao = mockk<CharacterDao>()
        val releaseLoad = CompletableDeferred<Unit>()
        coEvery { templateDao.getAll() } coAnswers {
            releaseLoad.await()
            emptyList()
        }
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        coEvery { characterDao.getAll() } returns emptyList()

        val viewModel = createViewModel(templateDao, encyclopediaDao, characterDao)
        viewModel.retryTemplates()
        viewModel.retryTemplates()
        releaseLoad.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { templateDao.getAll() }
        assertFalse(viewModel.state.value.templates.isLoading)
    }

    @Test
    fun createStoryWritesChaptersAndOpensSessionWithoutCandidateStep() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
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
        val templateDao = mockk<WorldTemplateDao>()
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
    fun repeatedCreateStoryUsesOneCreationJob() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao>()
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
        val templateDao = mockk<WorldTemplateDao>()
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
        val templateDao = mockk<WorldTemplateDao>()
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
    ) = StorySimulationViewModel(
        storyWriting = storyWriting,
        secureStorage = secureStorage,
        templateDao = templateDao,
        encyclopediaDao = encyclopediaDao,
        characterDao = characterDao,
        createSession = createSession,
        draftStore = draftStore,
    )
}
