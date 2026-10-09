package com.mojing.app.viewmodel

import kotlinx.coroutines.flow.first

import androidx.lifecycle.SavedStateHandle
import android.content.Context
import android.content.SharedPreferences
import com.mojing.app.data.ChatDraftSnapshot
import com.mojing.app.data.ChapterInputDraft
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.ReplyRecoveryLoadResult
import com.mojing.app.data.ReplyRecoverySnapshot
import com.mojing.app.data.SecureStorage
import com.mojing.app.media.AndroidTts
import com.mojing.app.media.AzureSpeech
import com.mojing.app.media.SynthesizedSpeechFile
import com.mojing.app.media.TtsPlayer
import com.mojing.app.media.newmedia.SpeechPlaybackControl
import com.mojing.app.data.local.dao.BookmarkDao
import com.mojing.app.data.local.AutoImageMetadata
import com.mojing.app.data.local.AutoVoiceMetadata
import com.mojing.app.data.local.entity.MessageBookmarkEntity
import com.mojing.app.data.local.dao.AttachmentDao
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.CharacterStateDao
import com.mojing.app.data.local.dao.ChatCharacterPresentationRow
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.MessagePreviewSource
import com.mojing.app.data.local.dao.SessionEventNodeDao
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.dao.MessageRecallResult
import com.mojing.app.data.local.dao.ParticipantDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.dao.SessionWorldDao
import com.mojing.app.data.local.dao.SessionMemoryCorrectionDao
import com.mojing.app.data.local.branch.BranchVisibilityIndexManager
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.BranchSwipeSelectionEntity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldCredentialDraft
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.SessionCharacterStateEntity
import com.mojing.app.domain.engine.ContextBuilder
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.engine.ChatEngine
import com.mojing.app.domain.engine.NarratorEngine
import com.mojing.app.domain.engine.SummaryMaintenanceUseCase
import com.mojing.app.domain.engine.StreamState
import com.mojing.app.domain.story.NovelChapter
import com.mojing.app.domain.usecase.MessageSubmissionTransaction
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.ui.chat.ChatViewModel
import com.mojing.app.ui.util.UserFacingStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Test fun contextBudgetErrorPersistsAndDoesNotTryBackupEndpoints() = runTest(testDispatcher) {
        val storage = validSecureStorage(baseUrl = "https://first.test/v1\nhttps://second.test/v1")
        every { storage.speakerTurnMode } returns "manual"
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainContextTail(42L, any()) } returns listOf(MessageEntity(id = 1, sessionId = 42, speakerType = "user", content = "已保存输入"))
        val engine = mockk<ChatEngine>(relaxed = true)
        every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf(StreamState.Error("容量需要调整", contextLimit = true))
        val vm = createViewModel(messageDao = messages, secureStorage = storage, characterDao = characters,
            participantDao = participants, chatEngine = engine, llmApiService = validLlmApiService())
        advanceUntilIdle()
        vm.setManualReplyCharacterId(3L)
        vm.updateInput("继续剧情")
        vm.sendMessage()
        advanceUntilIdle()
        assertFalse(vm.state.value.isGenerating)
        assertEquals("容量需要调整", vm.state.value.contextBudgetError)
        assertEquals(null, vm.state.value.error)
        verify(exactly = 1) { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        vm.clearContextBudgetError("旧错误")
        assertEquals("容量需要调整", vm.state.value.contextBudgetError)
        vm.clearContextBudgetError("容量需要调整")
        assertEquals(null, vm.state.value.contextBudgetError)
    }


    companion object {
        @JvmStatic @org.junit.BeforeClass fun installSpeechHandler() {
            io.mockk.mockkStatic(android.os.Looper::class)
            every { android.os.Looper.getMainLooper() } returns mockk(relaxed = true)
            io.mockk.mockkConstructor(android.os.Handler::class)
            every { anyConstructed<android.os.Handler>().post(any()) } answers {
                firstArg<Runnable>().run(); true
            }
        }

        @JvmStatic @org.junit.AfterClass fun removeSpeechHandler() {
            io.mockk.unmockkConstructor(android.os.Handler::class)
            io.mockk.unmockkStatic(android.os.Looper::class)
        }
    }

    private val testDispatcher = StandardTestDispatcher()

    private fun speechTestContext(): Context {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } answers {
            when (args[0] as String) {
                "global_engine" -> "system"
                "global_voice" -> ""
                else -> null
            }
        }
        return context
    }

    @Test
    fun playbackControlsExposeCurrentSegmentAndStopClearsOnlyCurrentRequest() = runTest(testDispatcher) {
        mockkObject(AndroidTts)
        lateinit var control: SpeechPlaybackControl
        lateinit var lease: SpeechPlaybackControl.Lease
        var pauses = 0
        var resumes = 0
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } coAnswers {
                control = arg(3)
                lease = control.bind(
                    onPause = { pauses++ },
                    onResume = {
                        resumes++
                        control.updateIfOwned(lease) { it.copy(phase = SpeechPlaybackControl.Phase.PLAYING) }
                    },
                )!!
                control.updateIfOwned(lease) {
                    it.copy(phase = SpeechPlaybackControl.Phase.PLAYING, segmentIndex = 2, segmentCount = 12)
                }
                awaitCancellation()
            }
            val vm = createViewModel(appContext = speechTestContext())
            advanceUntilIdle()
            vm.speakMessage("当前分段朗读")
            runCurrent()
            assertEquals(2, vm.speechPlayback.value.segmentIndex)
            assertEquals(12, vm.speechPlayback.value.segmentCount)
            vm.pauseSpeaking(); runCurrent()
            assertEquals(SpeechPlaybackControl.Phase.PAUSED, vm.speechPlayback.value.phase)
            assertEquals(1, pauses)
            vm.resumeSpeaking(); runCurrent()
            assertEquals(SpeechPlaybackControl.Phase.PLAYING, vm.speechPlayback.value.phase)
            assertEquals(1, resumes)
            vm.stopSpeaking(); runCurrent()
            assertFalse(vm.speechActive.value)
            assertEquals(SpeechPlaybackControl.Snapshot(), vm.speechPlayback.value)
            control.updateIfOwned(lease) { it.copy(phase = SpeechPlaybackControl.Phase.PLAYING) }
            assertEquals(SpeechPlaybackControl.Snapshot(), vm.speechPlayback.value)
        } finally { unmockkObject(AndroidTts) }
    }

    @Test
    fun pauseDuringTextPreparationIsKeptWhenMediaStarts() = runTest(testDispatcher) {
        val scheduler = TestCoroutineScheduler()
        mockkObject(AndroidTts)
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } coAnswers {
                val control = arg<SpeechPlaybackControl>(3)
                assertTrue(control.isPaused())
                control.bind({}, {})
                awaitCancellation()
            }
            val vm = createViewModel(appContext = speechTestContext())
            advanceUntilIdle()
            vm.preparationDispatcher = StandardTestDispatcher(scheduler)
            vm.speakMessage("准备中的暂停")
            runCurrent()
            vm.pauseSpeaking(); runCurrent()
            assertEquals(SpeechPlaybackControl.Phase.PAUSED, vm.speechPlayback.value.phase)
            scheduler.advanceUntilIdle(); runCurrent()
            coVerify(exactly = 1) { AndroidTts.speakAwaitCompletion(any(), "准备中的暂停", any(), any()) }
            assertEquals(SpeechPlaybackControl.Phase.PAUSED, vm.speechPlayback.value.phase)
            vm.stopSpeaking(); runCurrent()
        } finally { unmockkObject(AndroidTts) }
    }

    @Test
    fun replacedPlaybackCannotResetNewProgressWhenOldFinallyCompletes() = runTest(testDispatcher) {
        val oldFinish = CompletableDeferred<Unit>()
        mockkObject(AndroidTts)
        var calls = 0
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } coAnswers {
                val control = arg<SpeechPlaybackControl>(3)
                val lease = control.bind({}, {})!!
                calls++
                control.updateIfOwned(lease) {
                    it.copy(phase = SpeechPlaybackControl.Phase.PLAYING, segmentIndex = calls, segmentCount = 8)
                }
                if (calls == 1) {
                    withContext(NonCancellable) { oldFinish.await() }
                    control.updateIfOwned(lease) { it.copy(segmentIndex = 7) }
                    false
                } else awaitCancellation()
            }
            val vm = createViewModel(appContext = speechTestContext())
            advanceUntilIdle()
            vm.speakMessage("旧播放"); runCurrent()
            vm.speakMessage("新播放"); runCurrent()
            oldFinish.complete(Unit); runCurrent()
            assertTrue(vm.speechActive.value)
            assertEquals(2, vm.speechPlayback.value.segmentIndex)
            assertEquals(null, vm.state.value.speechRetryNotice)
            vm.stopSpeaking(); runCurrent()
        } finally {
            oldFinish.complete(Unit)
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun chatDisplaysWindowBeforeEstimateAndIgnoresOldWindowResult() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(
            MessageEntity(id = 7L, sessionId = 42L, content = "长篇正文"),
        )
        val oldEstimateScheduler = TestCoroutineScheduler()
        val vm = createViewModel(messageDao = messages)
        vm.tokenEstimateDispatcher = StandardTestDispatcher(oldEstimateScheduler)

        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)
        assertEquals(null, vm.state.value.conversationTokenEstimate)
        assertEquals(listOf(7L), vm.state.value.messages.map { it.id })

        vm.tokenEstimateDispatcher = testDispatcher
        assertTrue(vm.returnToLatestMessages())
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoadingHistory)
        assertEquals(null, vm.state.value.error)
        assertEquals(listOf(7L), vm.state.value.messages.map { it.id })
        oldEstimateScheduler.advanceUntilIdle()
        assertEquals(com.mojing.app.domain.engine.TokenCounter.estimateScaledPrefix("长篇正文"),
            vm.state.value.conversationTokenEstimate)
    }

    @Test
    fun speechFailureKeepsSnapshotAndRetryUsesSameRequest() = runTest(testDispatcher) {
        val context = speechTestContext()
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returnsMany listOf(false, true)
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()

            vm.speakMessage("可恢复的朗读")
            advanceUntilIdle()
            val notice = vm.state.value.speechRetryNotice
            assertNotNull(notice)

            vm.retryFailedSpeech(notice!!.token)
            advanceUntilIdle()

            assertEquals(null, vm.state.value.speechRetryNotice)
            coVerify(exactly = 2) { AndroidTts.speakAwaitCompletion(context, "可恢复的朗读", any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun retryKeepsCompleteLongTextAndOriginalResolvedVoice() = runTest(testDispatcher) {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        var voice = "voice-a"
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } answers {
            when (args[0] as String) {
                "global_engine" -> "system"
                "global_voice" -> voice
                else -> null
            }
        }
        val capturedText = mutableListOf<String>()
        val capturedChoices = mutableListOf<com.mojing.app.data.VoiceChoice>()
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } coAnswers {
                capturedText += args[1] as String
                capturedChoices += args[2] as com.mojing.app.data.VoiceChoice
                capturedText.size > 1
            }
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()
            val text = "长文本。".repeat(8_500)

            vm.speakMessage(text)
            advanceUntilIdle()
            val notice = vm.state.value.speechRetryNotice!!
            voice = "voice-b"
            vm.retryFailedSpeech(notice.token)
            advanceUntilIdle()

            assertEquals(text.length, capturedText[0].length)
            assertEquals(text, capturedText[0])
            assertEquals(text, capturedText[1])
            assertEquals("voice-a", capturedChoices[0].voiceId)
            assertEquals("voice-a", capturedChoices[1].voiceId)
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun stoppingSpeechInvalidatesRetryAndDuplicateRetryDoesNotStartAnotherJob() = runTest(testDispatcher) {
        val context = speechTestContext()
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returns false
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()

            vm.speakMessage("失败后应可关闭")
            advanceUntilIdle()
            val token = vm.state.value.speechRetryNotice!!.token
            vm.retryFailedSpeech(token)
            vm.retryFailedSpeech(token)
            advanceUntilIdle()
            assertNotNull(vm.state.value.speechRetryNotice)
            assertNotEquals(token, vm.state.value.speechRetryNotice!!.token)
            coVerify(exactly = 2) { AndroidTts.speakAwaitCompletion(context, "失败后应可关闭", any(), any()) }

            vm.stopSpeaking()
            assertEquals(null, vm.state.value.speechRetryNotice)
            vm.retryFailedSpeech(token)
            advanceUntilIdle()
            coVerify(exactly = 2) { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun lateFailureFromReplacedSpeechCannotPublishRetryNotice() = runTest(testDispatcher) {
        val context = speechTestContext()
        val oldSpeech = CompletableDeferred<Unit>()
        var calls = 0
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } coAnswers {
                calls++
                if (calls == 1) {
                    withContext(NonCancellable) { oldSpeech.await() }
                    false
                } else true
            }
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()

            vm.speakMessage("旧朗读")
            runCurrent()
            vm.speakMessage("新朗读")
            runCurrent()
            oldSpeech.complete(Unit)
            advanceUntilIdle()

            assertEquals(null, vm.state.value.speechRetryNotice)
            assertEquals(2, calls)
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun preparationFromReplacedSpeechCannotPublishOrPlay() = runTest(testDispatcher) {
        val context = speechTestContext()
        val preparationScheduler = TestCoroutineScheduler()
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returns true
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            vm.preparationDispatcher = StandardTestDispatcher(preparationScheduler)
            advanceUntilIdle()

            vm.speakMessage("旧准备")
            runCurrent()
            vm.speakMessage("新准备")
            runCurrent()
            preparationScheduler.advanceUntilIdle()
            advanceUntilIdle()

            coVerify(exactly = 1) { AndroidTts.speakAwaitCompletion(context, "新准备", any(), any()) }
            assertEquals(null, vm.state.value.speechRetryNotice)
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun azureFailureCreatesRetryNoticeAndEmptyTextDoesNot() = runTest(testDispatcher) {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } answers {
            when (args[0] as String) {
                "global_engine" -> "azure"
                "global_voice" -> "zh-CN-XiaoxiaoNeural"
                else -> null
            }
        }
        io.mockk.mockkConstructor(SecureStorage::class)
        every { anyConstructed<SecureStorage>().init(any()) } returns Unit
        every { anyConstructed<SecureStorage>().azureSpeechRegion } returns "test-region"
        every { anyConstructed<SecureStorage>().azureSpeechKey } returns "synthetic-key"
        mockkObject(AzureSpeech)
        try {
            coEvery { AzureSpeech.speak(any(), any(), any(), any(), any(), any()) } throws AzureSpeech.SpeechException("failed")
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()

            vm.speakMessage("Azure 失败")
            assertNotNull(vm.state.first { it.speechRetryNotice != null }.speechRetryNotice)

            vm.speakMessage("   ")
            advanceUntilIdle()
            assertEquals(null, vm.state.value.speechRetryNotice)
            coVerify(exactly = 1) { AzureSpeech.speak(any(), any(), any(), any(), any(), any()) }
        } finally {
            unmockkObject(AzureSpeech)
            io.mockk.unmockkConstructor(SecureStorage::class)
        }
    }

    @Test
    fun detachedSpeechCannotStartUntilScreenAttachesAgain() = runTest(testDispatcher) {
        val context = speechTestContext()
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returns true
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()

            vm.detachSpeechScreen()
            vm.speakMessage("离页期间不应播放")
            advanceUntilIdle()
            assertEquals(null, vm.state.value.speechRetryNotice)
            coVerify(exactly = 0) { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) }

            vm.attachSpeechScreen()
            vm.speakMessage("重新进入后可以播放")
            advanceUntilIdle()
            coVerify(exactly = 1) { AndroidTts.speakAwaitCompletion(context, "重新进入后可以播放", any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun nonCancellableFailureAfterDetachCannotPublishRetryNotice() = runTest(testDispatcher) {
        val context = speechTestContext()
        val failureGate = CompletableDeferred<Unit>()
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } coAnswers {
                withContext(NonCancellable) { failureGate.await() }
                false
            }
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()

            vm.speakMessage("离页后仍会晚到失败")
            runCurrent()
            coVerify(exactly = 1) { AndroidTts.speakAwaitCompletion(context, "离页后仍会晚到失败", any(), any()) }
            vm.detachSpeechScreen()
            failureGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(null, vm.state.value.speechRetryNotice)
            vm.attachSpeechScreen()
            vm.speakMessage("晚到失败之后的新请求")
            advanceUntilIdle()
            coVerify(exactly = 2) { AndroidTts.speakAwaitCompletion(context, any(), any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun branchSwitchBlocksSpeechAndRetryWhileDatabaseReadIsSuspended() = runTest(testDispatcher) {
        val context = speechTestContext()
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchRead = CompletableDeferred<List<MessageEntity>>()
        val branch = SessionBranchEntity(sessionId = 42L, branchId = "B", sourceMessageId = 1L)
        coEvery { branchDao.getBySession(42L) } returns listOf(branch)
        coEvery { messageDao.getVisibleMessagesTail(42L, "B", any()) } coAnswers { branchRead.await() }
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returnsMany listOf(false, true)
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context, messageDao = messageDao, sessionBranchDao = branchDao)
            advanceUntilIdle()

            vm.speakMessage("切线前失败")
            advanceUntilIdle()
            val retryToken = vm.state.value.speechRetryNotice!!.token
            vm.switchBranch("B")
            runCurrent()
            assertEquals("正在打开故事线…", vm.state.value.branchNavigationLabel)
            vm.speakMessage("切线读取期间不应播放")
            vm.retryFailedSpeech(retryToken)
            runCurrent()
            coVerify(exactly = 1) { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) }

            branchRead.complete(emptyList())
            advanceUntilIdle()
            vm.speakMessage("切线完成后可以播放")
            advanceUntilIdle()
            coVerify(exactly = 2) { AndroidTts.speakAwaitCompletion(context, any(), any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun speechIsBlockedDuringVoiceSaveAndUsesNewChoiceAfterSaveCompletes() = runTest(testDispatcher) {
        val context = speechTestContext()
        val oldChoice = com.mojing.app.data.VoiceChoice("system", "old-voice")
        val newChoice = com.mojing.app.data.VoiceChoice("system", "new-voice")
        var currentChoice = oldChoice
        val saveStarted = CountDownLatch(1)
        val saveFinished = CountDownLatch(1)
        val saveGate = CountDownLatch(1)
        mockkConstructor(com.mojing.app.data.VoicePreferences::class)
        mockkObject(AndroidTts)
        try {
            every { anyConstructed<com.mojing.app.data.VoicePreferences>().sessionSelection(any()) } answers { currentChoice }
            every { anyConstructed<com.mojing.app.data.VoicePreferences>().global() } answers { currentChoice }
            every { anyConstructed<com.mojing.app.data.VoicePreferences>().saveSession(42L, newChoice) } answers {
                saveStarted.countDown()
                check(saveGate.await(5, TimeUnit.SECONDS))
                currentChoice = newChoice
                saveFinished.countDown()
            }
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returns true
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context)
            advanceUntilIdle()

            vm.selectVoiceChoice(newChoice) {}
            runCurrent()
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
            vm.speakMessage("保存进行中不应使用旧音色")
            runCurrent()
            coVerify(exactly = 0) { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) }

            saveGate.countDown()
            assertTrue(saveFinished.await(5, TimeUnit.SECONDS))
            vm.state.first { !it.voiceSelectionSaving }
            vm.speakMessage("保存完成使用新音色")
            advanceUntilIdle()
            coVerify(exactly = 1) {
                AndroidTts.speakAwaitCompletion(context, "保存完成使用新音色", newChoice, any())
            }
        } finally {
            saveGate.countDown()
            unmockkObject(AndroidTts)
            unmockkConstructor(com.mojing.app.data.VoicePreferences::class)
        }
    }

    @Test
    fun switchingToInvalidBranchStillInvalidatesSpeechRetry() = runTest(testDispatcher) {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42L) } returns emptyList()
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returns false
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(appContext = context, sessionBranchDao = branches)
            advanceUntilIdle()

            vm.speakMessage("切线前的失败")
            advanceUntilIdle()
            assertNotNull(vm.state.value.speechRetryNotice)
            vm.switchBranch("missing")
            advanceUntilIdle()
            assertEquals(null, vm.state.value.speechRetryNotice)
        } finally {
            unmockkObject(AndroidTts)
        }
    }

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
        every { it.loadReplyRecovery(any()) } returns ReplyRecoveryLoadResult.Missing
        every { it.saveReplyRecovery(any()) } returns true
        every { it.checkpointReplyRecovery(any()) } returns true
        every { it.clearReplyRecovery(any(), any()) } returns true
    }

    @Test
    fun nextChapterKeepsEditorInputWhenLocalDraftCannotBeSaved() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        val input = ChapterInputDraft("第二章", "雨夜重逢")
        every { draftStore.saveChapterInput(42L, "main", input, true) } returns false
        val vm = createViewModel(chatDraftStore = draftStore)
        advanceUntilIdle()

        assertFalse(vm.requestNextChapter(input.title, input.direction))
        assertEquals("章节输入未能保存到本机，请检查存储空间", vm.state.value.error)
        verify(exactly = 0) { draftStore.clearChapterInputIfMatching(any(), any(), any()) }
    }

    @Test
    fun nextChapterClearsMatchingInputOnlyAfterChapterIsCommitted() = runTest(testDispatcher) {
        val input = ChapterInputDraft("第二章", "雨夜重逢")
        val draftStore = emptyDraftStore()
        every { draftStore.saveChapterInput(42L, "main", input, true) } returns true
        val messages = mockk<MessageDao>(relaxed = true)
        var committed = false
        coEvery { messages.insert(match { it.speakerType == "narrator" }) } answers {
            committed = true
            8L
        }
        every { draftStore.clearChapterInputIfMatching(42L, "main", input) } answers {
            assertTrue(committed)
            true
        }
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, gameplayMode = "小说创作")
        val engine = mockk<ChatEngine>(relaxed = true)
        every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf(StreamState.Done("第二章\n雨夜重逢。"))
        val vm = createViewModel(messageDao = messages, sessionWorldDao = world,
            chatDraftStore = draftStore, secureStorage = validSecureStorage(),
            llmApiService = validLlmApiService(), chatEngine = engine)
        advanceUntilIdle()

        assertTrue(vm.requestNextChapter(input.title, input.direction))
        advanceUntilIdle()

        assertTrue(committed)
        verify(exactly = 1) { draftStore.clearChapterInputIfMatching(42L, "main", input) }
    }

    @Test fun chapterForkRejectsStaleBranchWithoutSavingOrCreating() = runTest(testDispatcher) {
        val drafts = emptyDraftStore()
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val vm = createViewModel(chatDraftStore = drafts, sessionBranchDao = branches)
        advanceUntilIdle()
        assertFalse(vm.requestChapterFork(7, "stale", "题", "方向"))
        verify(exactly = 0) { drafts.saveChapterInput(any(), any(), any(), any()) }
        coVerify(exactly = 0) { branches.insertEditedBranch(any(), any(), any()) }
    }

    @Test fun chapterForkRejectsChangedOrInvalidTarget() = runTest(testDispatcher) {
        val drafts = emptyDraftStore()
        every { drafts.saveChapterInput(any(), any(), any(), any()) } returns true
        val messages = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val vm = createViewModel(chatDraftStore = drafts, messageDao = messages, sessionBranchDao = branches)
        advanceUntilIdle()
        val target = MessageEntity(id = 7, sessionId = 42, speakerType = "narrator", content = "原文",
            structuredContentJson = NovelChapter.draftMetadata("{}", 1, "第一章"))
        listOf(null, target.copy(sessionId = 99), target.copy(speakerType = "user"),
            target.copy(content = ""), target.copy(structuredContentJson = NovelChapter.metadata("{}", 1, "第一章")),
            target.copy(structuredContentJson = "{\"chapter_incomplete\":true}")).forEach { row ->
            coEvery { messages.getMainMessageById(42, 7) } returns row
            assertTrue(vm.requestChapterFork(7, "main", "", "方向"))
            advanceUntilIdle()
            assertEquals("main", vm.state.value.currentBranchId)
            assertFalse(vm.state.value.isGenerating)
        }
        coVerify(exactly = 0) { branches.insertEditedBranch(any(), any(), any()) }
    }

    @Test fun chapterForkKeepsCreatedLineWithoutGeneratingWhenChildDraftSaveFails() = runTest(testDispatcher) {
        val drafts = emptyDraftStore()
        every { drafts.saveChapterInput(any(), match { it.startsWith("chapter_fork:") }, any(), true) } returns true
        every { drafts.saveChapterInput(any(), match { it.startsWith("chapter_7_") }, any(), true) } returns false
        val messages = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val target = MessageEntity(id = 7, sessionId = 42, speakerType = "narrator", content = "完整原文",
            structuredContentJson = NovelChapter.draftMetadata("{\"extra\":42}", 1, "第一章"))
        coEvery { messages.getMainMessageById(42, 7) } returns target
        coEvery { branches.insertEditedBranch(any(), any(), any()) } answers {
            val clone = args[1] as MessageEntity
            assertEquals(target, clone.copy(id = target.id, branchId = target.branchId,
                regeneratedFromMessageId = target.regeneratedFromMessageId, createdAt = target.createdAt))
            99L
        }
        val vm = createViewModel(chatDraftStore = drafts, messageDao = messages, sessionBranchDao = branches)
        advanceUntilIdle()
        assertTrue(vm.requestChapterFork(7, "main", "", "方向"))
        advanceUntilIdle()
        assertFalse(vm.state.value.isGenerating)
        assertTrue(vm.state.value.error!!.contains("已创建"))
        coVerify(exactly = 1) { branches.insertEditedBranch(any(), any(), any()) }
        verify(exactly = 0) { drafts.clearChapterInputIfMatching(any(), any(), any()) }
    }

    @Test fun chapterForkDoesNotGenerateAfterRefreshPreferenceOrTailFailure() = runTest(testDispatcher) {
        for (failure in listOf("refresh", "preference", "tail")) {
            val drafts = emptyDraftStore()
            every { drafts.saveChapterInput(any(), any(), any(), any()) } returns true
            every { drafts.clearChapterInputIfMatching(any(), any(), any()) } returns true
            val messages = mockk<MessageDao>(relaxed = true)
            val branches = mockk<SessionBranchDao>(relaxed = true)
            val preferences = uiPreferences()
            val target = MessageEntity(id = 7, sessionId = 42, speakerType = "narrator", content = "原文",
                structuredContentJson = NovelChapter.draftMetadata("{}", 1, "第一章"))
            val created = mutableListOf<SessionBranchEntity>()
            var clone: MessageEntity? = null
            coEvery { messages.getMainMessageById(42, 7) } returns target
            coEvery { branches.getBySession(42) } answers { created.toList() }
            coEvery { branches.insertEditedBranch(any(), any(), any()) } answers {
                created += args[0] as SessionBranchEntity
                clone = (args[1] as MessageEntity).copy(id = 99)
                99L
            }
            coEvery { messages.getVisibleMessagesTail(42, any(), 81) } answers {
                if (failure == "refresh") throw IllegalStateException("synthetic refresh failure")
                listOfNotNull(clone)
            }
            coEvery { messages.getVisibleMessagesTail(42, any(), 1) } answers { listOfNotNull(clone?.copy(id = 100)) }
            if (failure == "preference") coEvery { preferences.setLastChatBranch(42, any()) } throws IllegalStateException("synthetic preference failure")
            val vm = createViewModel(chatDraftStore = drafts, messageDao = messages,
                sessionBranchDao = branches, uiPreferencesRepository = preferences)
            advanceUntilIdle()
            assertTrue(vm.requestChapterFork(7, "main", "", "方向"))
            advanceUntilIdle()
            assertEquals(1, created.size)
            assertFalse(vm.state.value.isGenerating)
            assertTrue(vm.state.value.error!!.contains("尚未开始生成"))
        }
    }

    @Test fun chapterGenerationStartsAtOneAndKeepsExistingNumberProgression() = runTest(testDispatcher) {
        for ((latest, expected) in listOf(0 to 1, 2 to 3)) {
            val messages = mockk<MessageDao>(relaxed = true)
            coEvery { messages.getMainMaxChapter(42) } returns latest
            var saved: MessageEntity? = null
            coEvery { messages.insert(match { it.speakerType == "narrator" }) } answers {
                saved = args[0] as MessageEntity; 99L
            }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42) } returns SessionWorldEntity(sessionId = 42, gameplayMode = "小说创作")
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                flowOf(StreamState.Done("海风\n岸边的故事。"))
            val vm = createViewModel(messageDao = messages, sessionWorldDao = world, chatEngine = engine,
                secureStorage = validSecureStorage(), llmApiService = validLlmApiService())
            advanceUntilIdle()
            assertTrue(vm.requestNarrator(nextChapter = true))
            advanceUntilIdle()
            assertEquals(expected, NovelChapter.number(saved!!.structuredContentJson))
        }
    }

    @Test fun chapterGenerationEmptyBodyReportsModelFailureWithoutCompletedWrite() = runTest(testDispatcher) {
        for (raw in listOf("", "第1章", "<NARRATION>第1章</NARRATION>")) {
            val messages = mockk<MessageDao>(relaxed = true)
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42) } returns SessionWorldEntity(sessionId = 42, gameplayMode = "小说创作")
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                flowOf(StreamState.Done(raw))
            val vm = createViewModel(messageDao = messages, sessionWorldDao = world, chatEngine = engine,
                secureStorage = validSecureStorage(), llmApiService = validLlmApiService())
            advanceUntilIdle()
            assertTrue(vm.requestNarrator(nextChapter = true))
            advanceUntilIdle()
            assertEquals("模型未返回章节正文，请重试", vm.state.value.error)
            coVerify(exactly = 0) { messages.insert(any()) }
        }
    }

    @Test fun chapterGenerationDatabaseFailureKeepsLocalSaveFeedback() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.insert(any()) } throws IllegalStateException("synthetic storage failure")
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42) } returns SessionWorldEntity(sessionId = 42, gameplayMode = "小说创作")
        val engine = mockk<ChatEngine>(relaxed = true)
        every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf(StreamState.Done("第1章\n海风吹来。"))
        val vm = createViewModel(messageDao = messages, sessionWorldDao = world, chatEngine = engine,
            secureStorage = validSecureStorage(), llmApiService = validLlmApiService())
        advanceUntilIdle()
        assertTrue(vm.requestNarrator(nextChapter = true))
        advanceUntilIdle()
        assertEquals(UserFacingStrings.localSaveFailed("旁白回复"), vm.state.value.error)
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
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId)),
        messageDao: MessageDao = mockk(relaxed = true),
        bookmarkDao: BookmarkDao = mockk(relaxed = true),
        sessionBranchDao: SessionBranchDao = mockk(relaxed = true),
        sessionDao: SessionDao = existingSessionDao(sessionId),
        characterDao: CharacterDao = mockk(relaxed = true),
        sessionWorldDao: SessionWorldDao = mockk(relaxed = true),
        attachmentDao: AttachmentDao = mockk(relaxed = true),
        branchVisibilityIndexManager: BranchVisibilityIndexManager = mockk(relaxed = true) {
            coEvery { ensureReady() } returns Unit
        },
        messageSubmissionTransaction: MessageSubmissionTransaction? = null,
        memoryCorrectionDao: SessionMemoryCorrectionDao = mockk(relaxed = true),
        eventNodeDao: SessionEventNodeDao = mockk(relaxed = true),
        memorySegmentDao: com.mojing.app.data.local.dao.SessionMemorySegmentDao = mockk(relaxed = true),
        contextMemory: com.mojing.app.domain.engine.UniversalContextMemoryManager = mockk(relaxed = true),
        compactor: com.mojing.app.domain.engine.MemoryCompactor = mockk(relaxed = true),
        events: com.mojing.app.domain.engine.MemoryV2Manager = mockk(relaxed = true),
        sediment: com.mojing.app.domain.engine.SedimentEngine = mockk(relaxed = true),
        contextBuilder: ContextBuilder = mockk(relaxed = true),
        promptBuilder: com.mojing.app.domain.engine.PromptBuilder = mockk(relaxed = true),
        participantDao: ParticipantDao = mockk(relaxed = true),
        characterStateDao: CharacterStateDao = mockk(relaxed = true),
        chatDraftStore: ChatDraftStore = emptyDraftStore(),
        secureStorage: SecureStorage = mockk(relaxed = true) { every { sessionModelSelection(any()) } returns null },
        llmApiService: LlmApiService = mockk(relaxed = true),
        appContext: Context = mockk(relaxed = true),
        uiPreferencesRepository: UiPreferencesRepository = uiPreferences(),
        chatEngine: ChatEngine = mockk(relaxed = true),
        imageRepository: com.mojing.app.data.repository.ImageRepository = mockk(relaxed = true),
        sourceMessageId: Long = 0L,
        sourceBranchId: String = "",
        budgetManager: com.mojing.app.domain.engine.TokenBudgetManager = mockk(relaxed = true),
        summaryMaintenance: SummaryMaintenanceUseCase = mockk(relaxed = true),
    ) = ChatViewModel(
        savedStateHandle = savedStateHandle.apply {
            this["sourceMessageId"] = sourceMessageId
            this["sourceBranchId"] = sourceBranchId
        },
        messageDao = messageDao,
        sessionDao = sessionDao,
        characterDao = characterDao,
        participantDao = participantDao,
        sessionWorldDao = sessionWorldDao,
        sessionBranchDao = sessionBranchDao,
        branchVisibilityIndexManager = branchVisibilityIndexManager,
        memorySegmentDao = memorySegmentDao,
        memoryCorrectionDao = memoryCorrectionDao,
        eventNodeDao = eventNodeDao,
        costRecorder = mockk(relaxed = true),
        chatEngine = chatEngine,
        secureStorage = secureStorage,
        promptBuilder = promptBuilder,
        memoryCompactor = compactor,
        summaryMaintenance = summaryMaintenance,
        contextBuilder = contextBuilder,
        tokenBudgetManager = budgetManager,
        slidingWindowBuilder = mockk(relaxed = true),
        snapshotExtractor = mockk(relaxed = true),
        memoryV2Manager = events,
        universalContextMemoryManager = contextMemory,
        sedimentEngine = sediment,
        characterStateDao = characterStateDao,
        attachmentDao = attachmentDao,
        messageSubmissionTransaction = messageSubmissionTransaction
            ?: submissionTransaction(messageDao, attachmentDao),
        bookmarkDao = bookmarkDao,
        imageRepository = imageRepository,
        llmApiService = llmApiService,
        imageApiService = mockk(relaxed = true),
        uiPreferencesRepository = uiPreferencesRepository,
        narratorEngine = NarratorEngine(),
        chatDraftStore = chatDraftStore,
        appContext = appContext,
    ).also {
        it.preparationDispatcher = testDispatcher
        it.tokenEstimateDispatcher = testDispatcher
        it.attachSpeechScreen()
    }

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
    fun manualSpeakerChangedDuringValidationPreservesNewSelectionAndDraft() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 99L))
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(99L) } returns CharacterEntity(id = 99L, name = "原角色")
        val storage = validSecureStorage()
        every { storage.speakerTurnMode } returns "manual"
        val vm = createViewModel(messageDao = messages, participantDao = participants, characterDao = characters,
            secureStorage = storage, llmApiService = validLlmApiService())
        advanceUntilIdle()
        val gate = CompletableDeferred<CharacterEntity>()
        coEvery { characters.getById(99L) } coAnswers { gate.await() }
        vm.setManualReplyCharacterId(99L)
        vm.updateInput("换人后再发送")
        vm.sendMessage()
        runCurrent()
        vm.setManualReplyCharacterId(100L)
        gate.complete(CharacterEntity(id = 99L, name = "原角色"))
        advanceUntilIdle()
        assertEquals(100L, vm.state.value.manualReplyCharacterId)
        assertEquals("换人后再发送", vm.state.value.inputText)
        assertEquals("发言角色已变更，请重新发送", vm.state.value.error)
        coVerify(exactly = 0) { messages.insert(any()) }
    }

    @Test
    fun invalidSelectedManualSpeakerIsRejectedBeforeUserMessageCommit() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns emptyList()
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(99L) } returns CharacterEntity(id = 99L, name = "已移除角色")
        val storage = validSecureStorage()
        every { storage.speakerTurnMode } returns "manual"
        val vm = createViewModel(
            messageDao = messages,
            participantDao = participants,
            characterDao = characters,
            secureStorage = storage,
            llmApiService = validLlmApiService(),
        )
        advanceUntilIdle()

        vm.setManualReplyCharacterId(99L)
        vm.updateInput("保留这段草稿")
        val quoted = MessageEntity(id = 7L, sessionId = 42L, speakerType = "narrator", content = "码头见")
        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.Quote(quoted))
        advanceUntilIdle()
        assertTrue(vm.queueLocalImageAttachment("F:/pending/keep.png", expectedBranchId = "main"))
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals("保留这段草稿", vm.state.value.inputText)
        assertEquals(listOf("F:/pending/keep.png"), vm.state.value.pendingLocalImagePaths)
        assertEquals(quoted, vm.state.value.quotingMessage)
        assertEquals(null, vm.state.value.manualReplyCharacterId)
        assertEquals("选中的发言角色已移出当前对话，请重新选择", vm.state.value.error)
        coVerify(exactly = 0) { messages.insert(any()) }
    }

    @Test
    fun voiceDraftUpdateAcceptsCurrentBranchAndRejectsStaleBranchWithoutPersisting() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        every { draftStore.save(any(), any()) } returns Unit
        val vm = createViewModel(chatDraftStore = draftStore)
        advanceUntilIdle()

        assertTrue(vm.updateInput("已有文字识别结果", expectedBranchId = "main"))
        assertEquals("已有文字识别结果", vm.state.value.inputText)
        verify(exactly = 1) {
            draftStore.save(42L, match { it.inputText == "已有文字识别结果" })
        }

        assertFalse(vm.updateInput("旧故事线结果", expectedBranchId = "stale-branch"))
        assertEquals("已有文字识别结果", vm.state.value.inputText)
        verify(exactly = 0) {
            draftStore.save(42L, match { it.inputText == "旧故事线结果" })
        }
        verify(exactly = 1) {
            draftStore.save(42L, match { it.inputText == "已有文字识别结果" })
        }
    }

    @Test
    fun participantRemovedAfterSubmissionKeepsCommittedUserMessageAndClearsSelection() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val stored = mutableListOf<MessageEntity>()
        coEvery { messages.insert(any()) } answers {
            val message = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
            stored += message
            message.id
        }
        coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
        val participants = mockk<ParticipantDao>(relaxed = true)
        var participantPresent = true
        val participant = SessionParticipantEntity(sessionId = 42L, characterId = 99L)
        coEvery { participants.getBySession(42L) } answers { if (participantPresent) listOf(participant) else emptyList() }
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(99L) } returns CharacterEntity(id = 99L, name = "迟到角色")
        val transaction = mockk<MessageSubmissionTransaction>()
        coEvery { transaction(any(), any(), any()) } coAnswers {
            val message = firstArg<MessageEntity>()
            val messageId = messages.insert(message)
            @Suppress("UNCHECKED_CAST")
            (args[2] as (Long) -> Unit)(messageId)
            participantPresent = false
            messageId
        }
        val storage = validSecureStorage()
        every { storage.speakerTurnMode } returns "manual"
        val vm = createViewModel(
            messageDao = messages,
            participantDao = participants,
            characterDao = characters,
            secureStorage = storage,
            messageSubmissionTransaction = transaction,
            llmApiService = validLlmApiService(),
        )
        advanceUntilIdle()

        vm.setManualReplyCharacterId(99L)
        vm.updateInput("提交后角色被移出")
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals(listOf("提交后角色被移出"), stored.filter { it.speakerType == "user" }.map { it.content })
        assertEquals(null, vm.state.value.manualReplyCharacterId)
        assertEquals("选中的发言角色已移出当前对话，请重新选择", vm.state.value.error)
    }

    @Test
    fun characterDeletedAfterSubmissionKeepsCommittedUserMessageAndClearsSelection() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val stored = mutableListOf<MessageEntity>()
        coEvery { messages.insert(any()) } answers {
            val message = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
            stored += message
            message.id
        }
        coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
        val participants = mockk<ParticipantDao>(relaxed = true)
        val participant = SessionParticipantEntity(sessionId = 42L, characterId = 99L)
        coEvery { participants.getBySession(42L) } returns listOf(participant)
        val characters = mockk<CharacterDao>(relaxed = true)
        var characterPresent = true
        coEvery { characters.getById(99L) } answers {
            if (characterPresent) CharacterEntity(id = 99L, name = "已删除角色") else null
        }
        val transaction = mockk<MessageSubmissionTransaction>()
        coEvery { transaction(any(), any(), any()) } coAnswers {
            val message = firstArg<MessageEntity>()
            val messageId = messages.insert(message)
            @Suppress("UNCHECKED_CAST")
            (args[2] as (Long) -> Unit)(messageId)
            characterPresent = false
            messageId
        }
        val storage = validSecureStorage()
        every { storage.speakerTurnMode } returns "manual"
        val vm = createViewModel(
            messageDao = messages,
            participantDao = participants,
            characterDao = characters,
            secureStorage = storage,
            messageSubmissionTransaction = transaction,
            llmApiService = validLlmApiService(),
        )
        advanceUntilIdle()

        vm.setManualReplyCharacterId(99L)
        vm.updateInput("提交后角色被删除")
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals(listOf("提交后角色被删除"), stored.filter { it.speakerType == "user" }.map { it.content })
        assertEquals(null, vm.state.value.manualReplyCharacterId)
        assertEquals("选中的发言角色已移出当前对话，请重新选择", vm.state.value.error)
    }

    @Test
    fun manualModeWithoutSelectionStillCommitsUserOnlyMessage() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val stored = mutableListOf<MessageEntity>()
        coEvery { messages.insert(any()) } answers {
            val message = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
            stored += message
            message.id
        }
        coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns emptyList()
        val storage = validSecureStorage()
        every { storage.speakerTurnMode } returns "manual"
        val vm = createViewModel(
            messageDao = messages,
            participantDao = participants,
            secureStorage = storage,
            llmApiService = validLlmApiService(),
        )
        advanceUntilIdle()

        vm.updateInput("只保存用户正文")
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals(listOf("只保存用户正文"), stored.filter { it.speakerType == "user" }.map { it.content })
        assertEquals(UserFacingStrings.chatNoParticipant(), vm.state.value.error)
    }

    @Test
    fun initialDisplayLinesHideEmptyCommandsButKeepAttachedMessages() = runTest(testDispatcher) {
        val hidden = MessageEntity(
            id = 1L, sessionId = 42L, speakerType = "character",
            content = "<NARRATION></NARRATION>", createdAt = 1L,
        )
        val visible = MessageEntity(
            id = 2L, sessionId = 42L, speakerType = "character",
            content = "故事继续。", createdAt = 2L,
        )
        val attached = MessageEntity(
            id = 3L, sessionId = 42L, speakerType = "character",
            content = "", createdAt = 3L,
        )
        val messages = mockk<MessageDao>(relaxed = true)
        val attachments = mockk<AttachmentDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(attached, visible, hidden)
        coEvery { attachments.getByMessages(any()) } returns listOf(
            MessageAttachmentEntity(messageId = 3L, mimeType = "image/png", storagePath = "image.png"),
        )

        val vm = createViewModel(messageDao = messages, attachmentDao = attachments)
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        assertEquals(listOf(2L, 3L), vm.state.value.displayLines.map { it.selectedMessage().id })
    }

    @Test
    fun restoredReplyNeedsExplicitKeepAndIsInsertedOnlyOnce() = runTest(testDispatcher) {
        val snapshot = ReplyRecoverySnapshot(
            token = "123e4567-e89b-12d3-a456-426614174000", sessionId = 42L,
            branchId = "main", speakerType = "narrator", rawText = "进程退出前的回复",
            startedAt = 100L, updatedAt = 200L,
        )
        var pending = true
        val store = emptyDraftStore()
        every { store.loadReplyRecovery(42L) } answers {
            if (pending) ReplyRecoveryLoadResult.Valid(snapshot) else ReplyRecoveryLoadResult.Missing
        }
        every { store.clearReplyRecovery(42L, snapshot.token) } answers { pending = false; true }
        val messages = mockk<MessageDao>(relaxed = true)
        var inserts = 0
        coEvery { messages.findReplyRecoveryMessageId(42L, "main", snapshot.token) } answers {
            if (inserts > 0) 1L else null
        }
        coEvery { messages.insertReplyRecoveryIfAbsent(any(), snapshot.token, null) } answers {
            inserts++
            1L
        }
        val vm = createViewModel(messageDao = messages, chatDraftStore = store)
        advanceUntilIdle()

        assertEquals("进程退出前的回复", vm.state.value.replyRecovery?.text)
        assertEquals(0, inserts)
        vm.updateInput("未提交的新输入")
        vm.sendMessage()
        assertFalse(vm.submitNarratorGuidance("新的旁白方向"))
        advanceUntilIdle()
        assertEquals("未提交的新输入", vm.state.value.inputText)
        assertFalse(vm.state.value.isGenerating)
        coVerify(exactly = 0) { messages.insert(any()) }
        assertEquals("进程退出前的回复", vm.state.value.replyRecovery?.text)
        vm.keepRecoveredReply()
        advanceUntilIdle()

        assertEquals(1, inserts)
        assertEquals(null, vm.state.value.replyRecovery)
        vm.keepRecoveredReply()
        advanceUntilIdle()
        assertEquals(1, inserts)
    }

    @Test
    fun recoveryLookupFinishesBeforeSessionAcceptsGeneration() = runTest(testDispatcher) {
        val snapshot = ReplyRecoverySnapshot(
            token = "123e4567-e89b-12d3-a456-426614174002", sessionId = 42L,
            branchId = "main", speakerType = "narrator", rawText = "待核对的回复",
            startedAt = 100L, updatedAt = 200L,
        )
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = emptyDraftStore()
        every { store.loadReplyRecovery(42L) } returns ReplyRecoveryLoadResult.Valid(snapshot)
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.findReplyRecoveryMessageId(42L, "main", snapshot.token) } coAnswers {
            gate.await()
            null
        }
        val vm = createViewModel(messageDao = messages, chatDraftStore = store)
        advanceUntilIdle()
        assertFalse(vm.state.value.isReady)
        vm.updateInput("核对期间输入")
        vm.sendMessage()
        assertFalse(vm.submitNarratorGuidance("核对期间旁白"))
        advanceUntilIdle()
        coVerify(exactly = 0) { messages.insert(any()) }
        assertEquals("核对期间输入", vm.state.value.inputText)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)
        assertEquals(snapshot.rawText, vm.state.value.replyRecovery?.text)
    }

    @Test
    fun changedBranchTailKeepsRecoveryAvailableToCopyOrDiscard() = runTest(testDispatcher) {
        val snapshot = ReplyRecoverySnapshot(
            token = "123e4567-e89b-12d3-a456-426614174001", sessionId = 42L,
            branchId = "main", speakerType = "narrator", anchorMessageId = 7L,
            rawText = "旧故事线回复", startedAt = 100L, updatedAt = 200L,
        )
        var pending = true
        val store = emptyDraftStore()
        every { store.loadReplyRecovery(42L) } answers {
            if (pending) ReplyRecoveryLoadResult.Valid(snapshot) else ReplyRecoveryLoadResult.Missing
        }
        every { store.clearReplyRecovery(42L, snapshot.token) } answers { pending = false; true }
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.findReplyRecoveryMessageId(42L, "main", snapshot.token) } returns null
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(
            MessageEntity(id = 8L, sessionId = 42L, speakerType = "user", content = "新消息"),
        )
        val vm = createViewModel(messageDao = messages, chatDraftStore = store)
        advanceUntilIdle()

        assertTrue(vm.state.value.replyRecovery?.issue?.contains("末尾") == true)
        vm.keepRecoveredReply()
        advanceUntilIdle()
        coVerify(exactly = 0) { messages.insertReplyRecoveryIfAbsent(any(), any(), any()) }
        vm.discardRecoveredReply()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.replyRecovery)
    }

    @Test
    fun alreadyCommittedReplyClearsHandoffWithoutAnotherInsert() = runTest(testDispatcher) {
        val snapshot = ReplyRecoverySnapshot(
            token = "123e4567-e89b-12d3-a456-426614174002", sessionId = 42L,
            branchId = "main", speakerType = "narrator", rawText = "已经写入的回复",
            startedAt = 100L, updatedAt = 200L,
        )
        var pending = true
        val store = emptyDraftStore()
        every { store.loadReplyRecovery(42L) } answers {
            if (pending) ReplyRecoveryLoadResult.Valid(snapshot) else ReplyRecoveryLoadResult.Missing
        }
        every { store.clearReplyRecovery(42L, snapshot.token) } answers { pending = false; true }
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.findReplyRecoveryMessageId(42L, "main", snapshot.token) } returns 9L

        val vm = createViewModel(messageDao = messages, chatDraftStore = store)
        advanceUntilIdle()

        assertEquals(null, vm.state.value.replyRecovery)
        assertFalse(pending)
        coVerify(exactly = 0) { messages.insertReplyRecoveryIfAbsent(any(), any(), any()) }
    }

    @Test fun chapterRenameRejectsStaleDirectoryAndSourceWithoutWriting() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val vm = createViewModel(messageDao = messages)
        advanceUntilIdle()
        var completed = 0
        vm.renameChapter(501L, "新名", "old-branch", "main") { completed++ }
        advanceUntilIdle()
        assertNotNull(vm.state.value.novelMetadataError)
        coEvery { messages.getMainMessageById(42L, 501L) } returns MessageEntity(id = 501L, sessionId = 42L, branchId = "other")
        vm.renameChapter(501L, "新名", "main", "main") { completed++ }
        advanceUntilIdle()
        assertNotNull(vm.state.value.novelMetadataError)
        coEvery { messages.getMainMessageById(42L, 501L) } returns null
        vm.renameChapter(501L, "新名", "main", "main") { completed++ }
        advanceUntilIdle()
        assertEquals(0, completed)
        assertFalse(vm.state.value.novelMetadataSaving)
        coVerify(exactly = 0) { messages.renameNovelChapter(any(), any(), any()) }
    }

    @Test fun chapterRenameOwnsWriteAgainstNavigationGenerationAndDuplicateSave() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val gate = CompletableDeferred<Unit>()
        coEvery { messages.getMainMessageById(42L, 501L) } returns MessageEntity(id = 501L, sessionId = 42L)
        coEvery { messages.renameNovelChapter(501L, 42L, "新名") } coAnswers { gate.await() }
        val vm = createViewModel(messageDao = messages)
        advanceUntilIdle()
        var completed = 0
        vm.renameChapter(501L, "新名", "main", "main") { completed++ }
        runCurrent()
        assertTrue(vm.state.value.novelMetadataSaving)
        vm.switchBranch("B")
        assertFalse(vm.openMessageInHistory(501L))
        assertFalse(vm.requestNarrator(nextChapter = true))
        vm.renameChapter(501L, "重复") { completed++ }
        runCurrent()
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(0, completed)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, completed)
        assertFalse(vm.state.value.novelMetadataSaving)
        coVerify(exactly = 1) { messages.renameNovelChapter(any(), any(), any()) }
    }

    @Test fun chapterRenameWriteFailureKeepsEditorAndReleasesOwnerForRetry() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessageById(42L, 501L) } returns MessageEntity(id = 501L, sessionId = 42L)
        coEvery { messages.renameNovelChapter(any(), any(), any()) } throws IllegalStateException("write failed")
        val vm = createViewModel(messageDao = messages)
        advanceUntilIdle()
        var completed = 0
        vm.renameChapter(501L, "新名", "main", "main") { completed++ }; advanceUntilIdle()
        assertEquals(0, completed)
        assertNotNull(vm.state.value.novelMetadataError)
        assertFalse(vm.state.value.novelMetadataSaving)
        coEvery { messages.renameNovelChapter(any(), any(), any()) } returns Unit
        vm.renameChapter(501L, "新名", "main", "main") { completed++ }; advanceUntilIdle()
        assertEquals(1, completed)
        assertEquals(null, vm.state.value.novelMetadataError)
        coVerify(exactly = 2) { messages.renameNovelChapter(any(), any(), any()) }
    }

    @Test fun novelRenameWaitsForSaveAndIgnoresDuplicateSubmission() = runTest(testDispatcher) {
        val sessions = existingSessionDao(42L)
        val gate = CompletableDeferred<Unit>()
        coEvery { sessions.updateTitle(42L, any(), any()) } coAnswers { gate.await() }
        val vm = createViewModel(sessionDao = sessions)
        advanceUntilIdle()
        var completed = 0
        vm.renameNovel("新的小说") { completed++ }
        vm.renameNovel("重复输入") { completed++ }
        runCurrent()
        assertTrue(vm.state.value.novelMetadataSaving)
        assertEquals(0, completed)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, completed)
        assertEquals("新的小说", vm.state.value.sessionTitle)
        assertFalse(vm.state.value.novelMetadataSaving)
        coVerify(exactly = 1) { sessions.updateTitle(42L, any(), any()) }
    }

    @Test fun novelRenameFailureKeepsEditorOpenAndAllowsRetry() = runTest(testDispatcher) {
        val sessions = existingSessionDao(42L)
        coEvery { sessions.updateTitle(42L, any(), any()) } throws IllegalStateException("write failed")
        val vm = createViewModel(sessionDao = sessions)
        advanceUntilIdle()
        var completed = 0
        vm.renameNovel("待保存") { completed++ }; advanceUntilIdle()
        assertEquals(0, completed)
        assertNotNull(vm.state.value.novelMetadataError)
        assertFalse(vm.state.value.novelMetadataSaving)
        coEvery { sessions.updateTitle(42L, any(), any()) } returns Unit
        vm.renameNovel("待保存") { completed++ }; advanceUntilIdle()
        assertEquals(1, completed)
        assertEquals(null, vm.state.value.novelMetadataError)
    }

    @Test fun novelRenameChapterRefreshFailureDoesNotReportWriteFailure() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val vm = createViewModel(messageDao = messages)
        advanceUntilIdle()
        coEvery { messages.getMainMessageById(42L, 501L) } returns MessageEntity(id = 501L, sessionId = 42L)
        coEvery { messages.getMainMessagesTail(42L, any()) } throws IllegalStateException("refresh failed")
        var completed = false
        vm.renameChapter(501L, "新章名") { completed = true }; advanceUntilIdle()
        assertTrue(completed)
        assertEquals(null, vm.state.value.novelMetadataError)
        assertEquals("章节名称已保存，对话刷新失败，请重新打开对话", vm.state.value.error)
        assertFalse(vm.state.value.novelMetadataSaving)
    }

    @Test fun chapterRenameRefreshDoesNotRestorePreviousBranchAfterNavigation() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val chapter = MessageEntity(id = 501L, sessionId = 42L, content = "原线章节")
        val branchMessage = MessageEntity(id = 502L, sessionId = 42L, branchId = "B", content = "新线章节")
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(chapter)
        coEvery { messages.getMainMessageById(42L, 501L) } returns chapter
        coEvery { messages.getVisibleMessagesTail(42L, "B", any()) } returns listOf(branchMessage)
        coEvery { branches.getBySession(42L) } returns listOf(
            SessionBranchEntity(sessionId = 42L, branchId = "B", sourceMessageId = 501L),
        )
        val vm = createViewModel(messageDao = messages, sessionBranchDao = branches)
        advanceUntilIdle()
        assertEquals(listOf("B"), vm.state.value.branchAnchorsByMessageId[501L]?.map { it.branchId })

        val oldRefresh = CompletableDeferred<Unit>()
        coEvery { messages.getMainMessagesTail(42L, any()) } coAnswers {
            oldRefresh.await()
            listOf(chapter)
        }
        vm.renameChapter(501L, "新章名") {}
        runCurrent()
        assertTrue(vm.state.value.novelMetadataSaving)

        vm.switchBranch("B")
        advanceUntilIdle()
        assertEquals("B", vm.state.value.currentBranchId)
        assertEquals(listOf(branchMessage), vm.state.value.messages)

        oldRefresh.complete(Unit)
        advanceUntilIdle()
        assertEquals("B", vm.state.value.currentBranchId)
        assertEquals(listOf(branchMessage), vm.state.value.messages)
        assertFalse(vm.state.value.novelMetadataSaving)
    }

    @Test fun slowSameBranchRefreshCannotReplaceACompletedNavigationRefresh() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val original = MessageEntity(id = 501L, sessionId = 42L, content = "旧窗口")
        val latest = MessageEntity(id = 502L, sessionId = 42L, content = "新窗口")
        coEvery { messages.getMainMessageById(42L, 501L) } returns original
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(original)
        val vm = createViewModel(messageDao = messages)
        advanceUntilIdle()

        val oldRead = CompletableDeferred<List<MessageEntity>>()
        var refreshReads = 0
        coEvery { messages.getMainMessagesTail(42L, any()) } coAnswers {
            if (++refreshReads == 1) oldRead.await() else listOf(latest)
        }
        vm.renameChapter(501L, "新章名") {}
        runCurrent()
        vm.switchBranch("main")
        advanceUntilIdle()
        assertEquals(listOf(latest), vm.state.value.messages)

        oldRead.complete(listOf(original))
        advanceUntilIdle()
        assertEquals(listOf(latest), vm.state.value.messages)
        assertEquals(2, refreshReads)
    }

    @Test fun slowTailRefreshCannotPullReaderBackFromAnOlderMessage() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val latest = MessageEntity(id = 501L, sessionId = 42L, content = "最新章节")
        val older = MessageEntity(id = 1L, sessionId = 42L, content = "较早章节")
        coEvery { messages.getMainMessageById(42L, 501L) } returns latest
        coEvery { messages.getMainMessageById(42L, 1L) } returns older
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(latest)
        val vm = createViewModel(messageDao = messages)
        advanceUntilIdle()

        val oldRead = CompletableDeferred<List<MessageEntity>>()
        coEvery { messages.getMainMessagesTail(42L, any()) } coAnswers { oldRead.await() }
        vm.renameChapter(501L, "新章名") {}
        runCurrent()
        assertTrue(vm.openMessageInHistory(1L))
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.focusedMessageId)
        assertEquals(listOf(older), vm.state.value.messages)

        oldRead.complete(listOf(latest))
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.focusedMessageId)
        assertEquals(listOf(older), vm.state.value.messages)
    }

    @Test fun lateHistoryLookupCannotReplaceACompletedLatestWindow() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val latest = MessageEntity(id = 501L, sessionId = 42L, content = "最新章节")
        val older = MessageEntity(id = 1L, sessionId = 42L, content = "较早章节")
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(latest)
        coEvery { messages.getMainMessageById(42L, 501L) } returns latest
        val vm = createViewModel(messageDao = messages)
        advanceUntilIdle()

        val oldLookup = CompletableDeferred<MessageEntity>()
        coEvery { messages.getMainMessageById(42L, 1L) } coAnswers { oldLookup.await() }
        assertTrue(vm.openMessageInHistory(1L))
        runCurrent()
        vm.renameChapter(501L, "新章名") {}
        advanceUntilIdle()
        assertEquals(listOf(latest), vm.state.value.messages)

        oldLookup.complete(older)
        advanceUntilIdle()
        assertEquals(listOf(latest), vm.state.value.messages)
        assertEquals(null, vm.state.value.focusedMessageId)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun bookmarkActionsKeepHistoryAndIgnoreDuplicateClicks() = runTest(testDispatcher) {
        val message = MessageEntity(id = 501L, sessionId = 42L, content = "保留当前阅读位置")
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(message)
        coEvery { messages.getMessagePreviewPrefixesInSession(42L, listOf(501L)) } returns
            listOf(MessagePreviewSource(501L, "user", message.content))
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        var stored: MessageBookmarkEntity? = null
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { bookmarks.getByMessageId(501L) } answers { stored }
        coEvery { bookmarks.insert(any()) } coAnswers {
            release.await()
            stored = firstArg<MessageBookmarkEntity>().copy(id = 1L)
            1L
        }
        coEvery { bookmarks.deleteByMessageId(501L) } coAnswers { stored = null }
        val vm = createViewModel(messageDao = messages, bookmarkDao = bookmarks)
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)
        val history = vm.state.value.messages
        vm.toggleBookmark(501L)
        vm.toggleBookmark(501L)
        assertTrue(501L in vm.state.value.bookmarkBusyIds)
        release.complete(Unit)
        vm.state.first { 501L in it.bookmarkedMessageIds && it.bookmarkBusyIds.isEmpty() }
        coVerify(exactly = 1) { bookmarks.insert(any()) }
        assertTrue(history === vm.state.value.messages)
        assertEquals("保留当前阅读位置", vm.state.value.bookmarkPreviews[501L])
        vm.removeBookmark(501L)
        advanceUntilIdle()
        vm.removeBookmark(501L)
        advanceUntilIdle()
        assertFalse(501L in vm.state.value.bookmarkedMessageIds)
        assertTrue(history === vm.state.value.messages)
        coVerify(exactly = 1) { bookmarks.insert(any()) }
        coVerify(exactly = 1) { messages.getMainMessagesTail(42L, any()) }
    }

    @Test
    fun bookmarkListUsesBoundedSourcePreviewsAndKeepsOriginalIds() = runTest(testDispatcher) {
        val marks = listOf(
            MessageBookmarkEntity(id = 1L, sessionId = 42L, messageId = 501L),
            MessageBookmarkEntity(id = 2L, sessionId = 42L, messageId = 502L),
        )
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { bookmarks.getFirstPage(42L, 41) } returns marks
        coEvery { messages.getMessagePreviewPrefixesInSession(42L, match { it.toSet() == setOf(501L, 502L) }) } returns listOf(
            MessagePreviewSource(501L, "narrator", "<NARRATION>雨夜里的渡口。"),
            MessagePreviewSource(502L, "character", "<CHOICES><OPTION>仅有选项</OPTION></CHOICES>"),
        )
        val vm = createViewModel(messageDao = messages, bookmarkDao = bookmarks)
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        assertTrue(vm.state.value.bookmarks.isEmpty())
        coVerify(exactly = 0) { bookmarks.getFirstPage(any(), any()) }
        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        assertEquals("雨夜里的渡口。", vm.state.value.bookmarkPreviews[501L])
        assertEquals("（暂无摘要，可打开原文）", vm.state.value.bookmarkPreviews[502L])
        assertEquals(listOf(501L, 502L), vm.state.value.bookmarks.map { it.messageId })
        assertTrue(vm.state.value.bookmarksLoaded)
        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        coVerify(exactly = 1) { bookmarks.getFirstPage(42L, 41) }
        coVerify(exactly = 1) {
            messages.getMessagePreviewPrefixesInSession(42L, match { it.toSet() == setOf(501L, 502L) })
        }
    }

    @Test
    fun bookmarkFailureKeepsSelectionAndAllowsRetry() = runTest(testDispatcher) {
        val mark = MessageBookmarkEntity(id = 1L, sessionId = 42L, messageId = 501L)
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        coEvery { bookmarks.getFirstPage(42L, any()) } returns listOf(mark)
        coEvery { bookmarks.getByMessageId(501L) } returns mark
        coEvery { bookmarks.deleteByMessageId(501L) } throws IllegalStateException("storage unavailable")
        val vm = createViewModel(bookmarkDao = bookmarks)
        advanceUntilIdle()
        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        vm.removeBookmark(501L)
        advanceUntilIdle()
        assertTrue(vm.state.value.bookmarks.any { it.messageId == 501L })
        assertTrue(vm.state.value.bookmarkBusyIds.isEmpty())
        assertEquals("取消收藏失败，请重试", vm.state.value.error)
        coEvery { bookmarks.deleteByMessageId(501L) } returns Unit
        vm.removeBookmark(501L)
        advanceUntilIdle()
        assertTrue(vm.state.value.bookmarks.none { it.messageId == 501L })
    }

    @Test
    fun bookmarkPagesLoadOnlyVisibleRowsAndKeepWindowFlags() = runTest(testDispatcher) {
        val marks = (1L..41L).map { id ->
            MessageBookmarkEntity(id = id, sessionId = 42L, messageId = id, createdAt = 1000L - id)
        }
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        coEvery { bookmarks.getFirstPage(42L, 41) } returns marks
        coEvery { bookmarks.getBefore(42L, marks[39].createdAt, marks[39].id, 41) } returns marks.drop(40)
        coEvery { bookmarks.getBookmarkedMessageIds(42L, listOf(41L)) } returns listOf(41L)
        coEvery { bookmarks.getBookmarkedMessageIds(42L, listOf(1L)) } returns listOf(1L)
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(MessageEntity(id = 41L, sessionId = 42L, content = "可见原文"))
        coEvery { messages.getMainMessageById(42L, 1L) } returns MessageEntity(id = 1L, sessionId = 42L, content = "较早原文")
        val vm = createViewModel(messageDao = messages, bookmarkDao = bookmarks)
        advanceUntilIdle()

        assertTrue(vm.state.value.bookmarks.isEmpty())
        coVerify(exactly = 0) { bookmarks.getFirstPage(any(), any()) }
        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        assertEquals(40, vm.state.value.bookmarks.size)
        assertTrue(vm.state.value.bookmarksHasMore)
        assertEquals(setOf(41L), vm.state.value.bookmarkedMessageIds)
        vm.loadMoreBookmarks()
        advanceUntilIdle()
        assertEquals(41, vm.state.value.bookmarks.size)
        assertFalse(vm.state.value.bookmarksHasMore)
        assertEquals(setOf(41L), vm.state.value.bookmarkedMessageIds)
        assertTrue(vm.openMessageInHistory(1L))
        advanceUntilIdle()
        assertEquals(setOf(1L), vm.state.value.bookmarkedMessageIds)
        coVerify(exactly = 1) { bookmarks.getBefore(42L, marks[39].createdAt, marks[39].id, 41) }
    }

    @Test
    fun bookmarkFirstPageFailureCanRetryWithoutBlockingChat() = runTest(testDispatcher) {
        val mark = MessageBookmarkEntity(id = 1L, sessionId = 42L, messageId = 501L)
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        coEvery { bookmarks.getFirstPage(42L, 41) } throws IllegalStateException("storage unavailable")
        val vm = createViewModel(bookmarkDao = bookmarks)
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)

        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        assertEquals("收藏读取失败，请重试", vm.state.value.bookmarksLoadError)
        assertFalse(vm.state.value.bookmarksLoadingMore)
        assertTrue(vm.state.value.messages.isEmpty())

        coEvery { bookmarks.getFirstPage(42L, 41) } returns listOf(mark)
        vm.loadMoreBookmarks()
        advanceUntilIdle()
        assertEquals(listOf(501L), vm.state.value.bookmarks.map { it.messageId })
        assertEquals(null, vm.state.value.bookmarksLoadError)
        assertTrue(vm.state.value.isReady)
    }

    @Test
    fun encyclopediaFoundationLoadsOnlyWhenWorldTabOpensAndCanRetry() = runTest(testDispatcher) {
        val world = SessionWorldEntity(sessionId = 42L, encyclopediaId = 7L, worldPrompt = "当前场景")
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        val builder = mockk<ContextBuilder>(relaxed = true)
        coEvery { worldDao.getBySession(42L) } returns world
        coEvery { builder.encyclopediaFoundation(world) } throws IllegalStateException("read failed")
        val vm = createViewModel(sessionWorldDao = worldDao, contextBuilder = builder)
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        assertFalse(vm.state.value.encyclopediaFoundationLoaded)
        coVerify(exactly = 0) { builder.encyclopediaFoundation(any()) }
        vm.loadEncyclopediaFoundationIfNeeded()
        advanceUntilIdle()
        assertEquals("百科基础设定读取失败，请重试", vm.state.value.encyclopediaFoundationLoadError)
        assertFalse(vm.state.value.encyclopediaFoundationLoaded)

        coEvery { builder.encyclopediaFoundation(world) } returns "百科正文"
        vm.loadEncyclopediaFoundationIfNeeded(force = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.encyclopediaFoundationLoaded)
        assertEquals("百科正文", vm.state.value.encyclopediaFoundation)
        assertEquals(null, vm.state.value.encyclopediaFoundationLoadError)
        coVerify(exactly = 2) { builder.encyclopediaFoundation(world) }
    }

    @Test
    fun contextMemoryDisplayLoadsOnlyWhenOpenedAndCanRetry() = runTest(testDispatcher) {
        val memory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
        coEvery { memory.getFormattedMemory(42L, "main") } throws IllegalStateException("read failed")
        val vm = createViewModel(contextMemory = memory)
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)
        assertFalse(vm.state.value.contextMemoryLoaded)
        coVerify(exactly = 0) { memory.getFormattedMemory(42L, "main") }

        vm.loadContextMemoryIfNeeded()
        advanceUntilIdle()
        assertFalse(vm.state.value.contextMemoryLoaded)
        assertEquals("长期记忆读取失败，请重试", vm.state.value.contextMemoryLoadError)
        coEvery { memory.getFormattedMemory(42L, "main") } returns "码头约定"
        vm.loadContextMemoryIfNeeded(force = true)
        advanceUntilIdle()
        assertEquals("码头约定", vm.state.value.contextMemoryText)
        assertTrue(vm.state.value.contextMemoryLoaded)
        assertEquals(null, vm.state.value.contextMemoryLoadError)
        coVerify(exactly = 2) { memory.getFormattedMemory(42L, "main") }
    }

    @Test
    fun clearingContextMemoryUpdatesDisplayedMemoryAndKeepsStateOnFailure() = runTest(testDispatcher) {
        val memory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
        coEvery { memory.getFormattedMemory(42L, "main") } returns "码头约定"
        val vm = createViewModel(contextMemory = memory)
        advanceUntilIdle()
        vm.loadContextMemoryIfNeeded()
        advanceUntilIdle()
        assertEquals("码头约定", vm.state.value.contextMemoryText)
        coEvery { memory.clear(42L, "main") } throws IllegalStateException("busy")
        vm.clearCurrentContextMemory()
        advanceUntilIdle()
        assertEquals("码头约定", vm.state.value.contextMemoryText)
        assertEquals("长期记忆清空失败，请重试", vm.state.value.contextMemoryClearError)
        assertFalse(vm.state.value.memoryOperationRunning)
        coEvery { memory.clear(42L, "main") } returns Unit
        vm.clearCurrentContextMemory()
        advanceUntilIdle()
        assertEquals("", vm.state.value.contextMemoryText)
        assertEquals(null, vm.state.value.contextMemoryClearError)
        assertFalse(vm.state.value.memoryOperationRunning)
    }

    @Test
    fun staleExpectedBranchDoesNotClearMemory() = runTest(testDispatcher) {
        val memory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
        val vm = createViewModel(contextMemory = memory)
        advanceUntilIdle()

        vm.clearCurrentContextMemory(expectedBranchId = "stale-branch")
        advanceUntilIdle()

        coVerify(exactly = 0) { memory.clear(any(), any()) }
        assertFalse(vm.state.value.memoryOperationRunning)
    }

    @Test
    fun generationAndBusyStateDoNotSubmitDuplicateClears() = runTest(testDispatcher) {
        val memory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insert(any()) } coAnswers { awaitCancellation() }
        val vm = createViewModel(contextMemory = memory, messageDao = messageDao)
        advanceUntilIdle()

        vm.updateInput("生成中的消息")
        vm.sendMessage()
        runCurrent()
        vm.clearCurrentContextMemory(expectedBranchId = "main")
        runCurrent()
        coVerify(exactly = 0) { memory.clear(any(), any()) }
        vm.stopGeneration()
        advanceUntilIdle()

        coEvery { memory.clear(42L, "main") } coAnswers { awaitCancellation() }
        vm.clearCurrentContextMemory(expectedBranchId = "main")
        runCurrent()
        vm.clearCurrentContextMemory(expectedBranchId = "main")
        runCurrent()
        coVerify(exactly = 1) { memory.clear(42L, "main") }
    }

    @Test
    fun exposesSessionIdFromSavedState() = runTest(testDispatcher) {
        val vm = createViewModel()
        assertNotNull(vm)
        assertEquals(42L, vm.state.value.sessionId)
        advanceUntilIdle()
    }

    @Test
    fun memoryCorrectionsLoadOnlyWhenTheirPanelOpens() = runTest(testDispatcher) {
        val correction = SessionMemoryCorrectionEntity(sessionId = 42L, content = "主线纠正")
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns listOf(correction)

        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        assertFalse(vm.state.value.memoryCorrectionsLoaded)
        coVerify(exactly = 0) { dao.getVisibleFirstPage(42L, "main", any()) }
        coVerify(exactly = 0) { dao.getVisible(42L, "main") }
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(correction), vm.state.value.memoryCorrections)
        assertTrue(vm.state.value.memoryCorrectionsLoaded)
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        coVerify(exactly = 1) { dao.getVisibleFirstPage(42L, "main", 17) }
    }

    @Test
    fun memoryCorrectionsFirstReadFailureCanRetryWithoutBlockingChat() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val correction = SessionMemoryCorrectionEntity(sessionId = 42L, content = "可恢复的纠正")
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } throws IllegalStateException("read failed")
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()

        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)
        assertFalse(vm.state.value.memoryCorrectionsLoaded)
        assertFalse(vm.state.value.memoryCorrectionsLoading)
        assertEquals("用户纠正读取失败，请重试", vm.state.value.memoryCorrectionsLoadError)

        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns listOf(correction)
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(correction), vm.state.value.memoryCorrections)
        assertTrue(vm.state.value.memoryCorrectionsLoaded)
        assertEquals(null, vm.state.value.memoryCorrectionsLoadError)
    }

    @Test
    fun memoryCorrectionsPageThroughEqualTimestampsWithoutRepeatingRows() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val corrections = (19L downTo 1L).map { id ->
            SessionMemoryCorrectionEntity(id = id, sessionId = 42L, content = "纠正 $id", createdAt = 100L)
        }
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns corrections.take(17)
        coEvery { dao.getVisibleBefore(42L, "main", 100L, 4L, 17) } returns corrections.drop(16)
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()

        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        assertEquals(corrections.take(16).map { it.id }, vm.state.value.memoryCorrections.map { it.id })
        assertTrue(vm.state.value.memoryCorrectionsHasMore)

        vm.loadMoreMemoryCorrections()
        advanceUntilIdle()
        assertEquals(corrections.map { it.id }, vm.state.value.memoryCorrections.map { it.id })
        assertFalse(vm.state.value.memoryCorrectionsHasMore)
        coVerify(exactly = 1) { dao.getVisibleBefore(42L, "main", 100L, 4L, 17) }
    }

    @Test
    fun failedOlderCorrectionPageKeepsCurrentRowsAndCanRetry() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val corrections = (17L downTo 1L).map { id ->
            SessionMemoryCorrectionEntity(id = id, sessionId = 42L, content = "纠正 $id", createdAt = 100L)
        }
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns corrections
        coEvery { dao.getVisibleBefore(42L, "main", 100L, 2L, 17) } throws IllegalStateException("read failed")
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()

        vm.loadMoreMemoryCorrections()
        advanceUntilIdle()
        assertEquals(corrections.take(16).map { it.id }, vm.state.value.memoryCorrections.map { it.id })
        assertTrue(vm.state.value.memoryCorrectionsHasMore)
        assertEquals("更多用户纠正读取失败，请重试", vm.state.value.memoryCorrectionsLoadError)

        coEvery { dao.getVisibleBefore(42L, "main", 100L, 2L, 17) } returns listOf(corrections.last())
        vm.loadMoreMemoryCorrections()
        advanceUntilIdle()
        assertEquals(corrections.map { it.id }, vm.state.value.memoryCorrections.map { it.id })
        assertFalse(vm.state.value.memoryCorrectionsHasMore)
        assertEquals(null, vm.state.value.memoryCorrectionsLoadError)
    }

    @Test
    fun savingCorrectionOutsideItsPanelDoesNotReadTheDisplayList() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.saveMemoryCorrection(null, "从消息保存的纠正", "main")
        advanceUntilIdle()

        assertFalse(vm.state.value.memoryCorrectionsLoaded)
        coVerify(exactly = 0) { dao.getVisibleFirstPage(42L, "main", any()) }
    }

    @Test
    fun delayedFullRefreshKeepsNewlySavedCorrections() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        var corrections = emptyList<SessionMemoryCorrectionEntity>()
        coEvery { dao.getVisibleFirstPage(42L, "main", any()) } answers { corrections }
        val vm = createViewModel(memoryCorrectionDao = dao, eventNodeDao = events)
        advanceUntilIdle()
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        val release = CompletableDeferred<List<SessionEventNodeEntity>>()
        coEvery { events.getPageForBranch(42L, "main", null, null, any()) } coAnswers { release.await() }
        vm.switchBranch("main")
        runCurrent()
        corrections = listOf(SessionMemoryCorrectionEntity(sessionId = 42, content = "已保存的新纠正"))
        vm.saveMemoryCorrection(null, "已保存的新纠正", "main")
        advanceUntilIdle()
        assertEquals(corrections, vm.state.value.memoryCorrections)
        release.complete(emptyList())
        advanceUntilIdle()
        assertEquals(corrections, vm.state.value.memoryCorrections)
    }

    @Test
    fun delayedCorrectionRefreshCannotOverwriteAnotherBranch() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val other = SessionMemoryCorrectionEntity(sessionId = 42, branchId = "branch-1", content = "另一条故事线")
        coEvery { branchDao.getBySession(42L) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "branch-1", sourceMessageId = 1))
        coEvery { dao.getVisibleFirstPage(42L, "main", any()) } returns emptyList()
        coEvery { dao.getVisibleFirstPage(42L, "branch-1", any()) } returns listOf(other)
        val vm = createViewModel(memoryCorrectionDao = dao, sessionBranchDao = branchDao)
        advanceUntilIdle()
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        val oldRead = CompletableDeferred<List<SessionMemoryCorrectionEntity>>()
        coEvery { dao.getVisibleFirstPage(42L, "main", any()) } coAnswers { oldRead.await() }
        vm.saveMemoryCorrection(null, "主线纠正", "main")
        runCurrent()
        vm.switchBranch("branch-1")
        advanceUntilIdle()
        assertTrue(vm.state.value.memoryCorrections.isEmpty())
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(other), vm.state.value.memoryCorrections)
        oldRead.complete(listOf(other.copy(branchId = "main", content = "主线旧查询")))
        advanceUntilIdle()
        assertEquals("branch-1", vm.state.value.currentBranchId)
        assertEquals(listOf(other), vm.state.value.memoryCorrections)
        coVerify(exactly = 1) { dao.getVisibleFirstPage(42L, "branch-1", 17) }
    }

    @Test
    fun latestCorrectionRefreshWinsWhenSavesFinishOutOfOrder() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        coEvery { dao.getVisibleFirstPage(42L, "main", any()) } returns emptyList()
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        val oldRead = CompletableDeferred<List<SessionMemoryCorrectionEntity>>()
        val latest = SessionMemoryCorrectionEntity(sessionId = 42, content = "最新纠正")
        var reads = 0
        coEvery { dao.getVisibleFirstPage(42L, "main", any()) } coAnswers { if (++reads == 1) oldRead.await() else listOf(latest) }
        vm.saveMemoryCorrection(null, "第一次", "main")
        runCurrent()
        vm.saveMemoryCorrection(null, "第二次", "main")
        advanceUntilIdle()
        assertEquals(listOf(latest), vm.state.value.memoryCorrections)
        oldRead.complete(emptyList())
        advanceUntilIdle()
        assertEquals(listOf(latest), vm.state.value.memoryCorrections)
        assertEquals(2, reads)
    }

    @Test
    fun summaryPanelLoadsOnDemandAndRetriesItsFirstRead() = runTest(testDispatcher) {
        val segments = mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed = true)
        val summary = com.mojing.app.data.local.entity.SessionMemorySegmentEntity(
            id = 7, sessionId = 42, endMessageId = 12, summary = "已整理",
        )
        var fail = true
        coEvery { segments.getRecentForBranch(42L, "main", 17) } answers {
            if (fail) error("read failed") else listOf(summary)
        }
        val vm = createViewModel(memorySegmentDao = segments)
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        assertFalse(vm.state.value.memorySegmentsLoaded)
        coVerify(exactly = 0) { segments.getRecentForBranch(42L, "main", any()) }
        vm.loadMemorySummariesIfNeeded()
        advanceUntilIdle()
        assertFalse(vm.state.value.memorySegmentsLoaded)
        assertEquals("摘要读取失败，请重试", vm.state.value.memorySegmentsLoadError)

        fail = false
        vm.loadMemorySummariesIfNeeded()
        advanceUntilIdle()
        assertTrue(vm.state.value.memorySegmentsLoaded)
        assertEquals(listOf(summary), vm.state.value.memorySegments)
        assertEquals(null, vm.state.value.memorySegmentsLoadError)
        coVerify(exactly = 2) { segments.getRecentForBranch(42L, "main", 17) }
    }

    @Test
    fun switchingBranchDiscardsOpenedMemoryPanelsUntilNewBranchIsRead() = runTest(testDispatcher) {
        val branch = SessionBranchEntity(sessionId = 42L, branchId = "branch-1", sourceMessageId = 1L)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val segments = mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed = true)
        val contextMemory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
        val mainSummary = com.mojing.app.data.local.entity.SessionMemorySegmentEntity(
            id = 1, sessionId = 42, branchId = "main", endMessageId = 1, summary = "主线",
        )
        val branchSummary = mainSummary.copy(id = 2, branchId = "branch-1", summary = "支线")
        coEvery { branches.getBySession(42L) } returns listOf(branch)
        coEvery { segments.getRecentForBranch(42L, "main", 17) } returns listOf(mainSummary)
        coEvery { segments.getRecentForBranch(42L, "branch-1", 17) } returns listOf(branchSummary)
        coEvery { contextMemory.getFormattedMemory(42L, "main") } returns "主线记忆"
        coEvery { contextMemory.getFormattedMemory(42L, "branch-1") } returns "支线记忆"
        val vm = createViewModel(sessionBranchDao = branches, memorySegmentDao = segments,
            contextMemory = contextMemory)
        advanceUntilIdle()
        vm.loadMemorySummariesIfNeeded()
        vm.loadContextMemoryIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(mainSummary), vm.state.value.memorySegments)
        assertEquals("主线记忆", vm.state.value.contextMemoryText)

        vm.switchBranch("branch-1")
        advanceUntilIdle()
        assertEquals("branch-1", vm.state.value.currentBranchId)
        assertFalse(vm.state.value.memorySegmentsLoaded)
        assertTrue(vm.state.value.memorySegments.isEmpty())
        assertFalse(vm.state.value.contextMemoryLoaded)
        assertEquals("", vm.state.value.contextMemoryText)
        coVerify(exactly = 0) { segments.getRecentForBranch(42L, "branch-1", any()) }
        coVerify(exactly = 0) { contextMemory.getFormattedMemory(42L, "branch-1") }
        vm.loadMemorySummariesIfNeeded()
        vm.loadContextMemoryIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(branchSummary), vm.state.value.memorySegments)
        assertEquals("支线记忆", vm.state.value.contextMemoryText)
    }

    @Test
    fun switchingBranchLoadsCorrectionsOnlyWhenOpenedWithoutTouchingAutomaticMemory() = runTest(testDispatcher) {
        val correction = SessionMemoryCorrectionEntity(sessionId = 42L, branchId = "branch-1", content = "分支纠正")
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        coEvery { dao.getVisibleFirstPage(42L, "main", any()) } returns emptyList()
        coEvery { dao.getVisibleFirstPage(42L, "branch-1", any()) } returns listOf(correction)
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
        val results = mutableListOf<String?>()
        vm.switchBranch("branch-1") { results += it }
        advanceUntilIdle()

        assertEquals("branch-1", vm.state.value.currentBranchId)
        assertEquals(listOf<String?>(null), results)
        assertFalse(vm.state.value.memoryCorrectionsLoaded)
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(correction), vm.state.value.memoryCorrections)
        assertEquals(null, vm.state.value.branchNavigationLabel)
        coVerify(exactly = 1) { preferences.setLastChatBranch(42L, "branch-1") }
    }

    @Test
    fun switchingBranchRejectsAStaleIdWithoutChangingOrPersisting() = runTest(testDispatcher) {
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        var branches = listOf(SessionBranchEntity(sessionId = 42L, branchId = "missing-branch", sourceMessageId = 1L))
        coEvery { branchDao.getBySession(42L) } answers { branches }
        val preferences = uiPreferences()
        val vm = createViewModel(
            sessionBranchDao = branchDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()
        assertEquals(1, vm.state.value.branches.size)

        branches = emptyList()
        val results = mutableListOf<String?>()
        vm.switchBranch("missing-branch") { results += it }
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(emptyList<SessionBranchEntity>(), vm.state.value.branches)
        assertEquals(listOf("故事线已不存在，请刷新后重试"), results)
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
        val branchRead = CompletableDeferred<List<MessageEntity>>()
        coEvery { branchDao.getBySession(42L) } returns listOf(branch)
        coEvery { messageDao.getVisibleMessagesTail(42L, "branch-1", any()) } coAnswers { branchRead.await() }
        val preferences = uiPreferences()
        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        val results = mutableListOf<String?>()
        vm.switchBranch("branch-1") { results += it }
        runCurrent()
        assertEquals("正在打开故事线…", vm.state.value.branchNavigationLabel)
        assertEquals(emptyList<String?>(), results)
        branchRead.completeExceptionally(IllegalStateException("database unavailable"))
        advanceUntilIdle()

        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(listOf("故事线切换失败，请重试"), results)
        assertEquals("故事线切换失败，请重试", vm.state.value.error)
        assertEquals(null, vm.state.value.branchNavigationLabel)
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
        coEvery { corrections.getVisibleFirstPage(42L, "branch-1", 17) } returns listOf(correction)

        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            memoryCorrectionDao = corrections,
            uiPreferencesRepository = uiPreferences("branch-1"),
        )
        advanceUntilIdle()

        assertEquals("branch-1", vm.state.value.currentBranchId)
        assertEquals(listOf("分支中的回复"), vm.state.value.messages.map { it.content })
        assertFalse(vm.state.value.memoryCorrectionsLoaded)
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
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
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns listOf(existing)
        coEvery { dao.insert(any()) } throws IllegalStateException("write failed")

        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        vm.saveMemoryCorrection(null, "新纠正", "main", null)
        advanceUntilIdle()

        assertEquals(listOf(existing), vm.state.value.memoryCorrections)
        assertTrue(vm.state.value.error.orEmpty().contains("保存失败"))
    }

    @Test
    fun correctionSavedButRefreshFailedStillReportsSuccessfulWrite() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns emptyList()
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } throws IllegalStateException("read failed")
        val results = mutableListOf<Boolean>()
        vm.saveMemoryCorrection(null, "已保存", "main", onResult = { results += it })
        advanceUntilIdle()
        assertEquals(listOf(true), results)
        coVerify(exactly = 1) { dao.insert(any()) }
        assertTrue(vm.state.value.error.orEmpty().contains("纠正已保存"))
    }

    @Test
    fun deletedCorrectionRemainsDeletedWhenListRefreshFails() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val correction = SessionMemoryCorrectionEntity(id = 7L, sessionId = 42L, content = "待删除纠正")
        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns listOf(correction)
        coEvery { dao.deleteById(42L, 7L) } returns 1
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.loadMemoryCorrectionsIfNeeded()
        advanceUntilIdle()

        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } throws IllegalStateException("read failed")
        val results = mutableListOf<Boolean>()
        vm.deleteMemoryCorrection(7L) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true), results)
        assertTrue(vm.state.value.memoryCorrections.isEmpty())
        assertTrue(vm.state.value.error.orEmpty().contains("纠正已删除"))
        assertEquals("纠正列表刷新失败，请重试", vm.state.value.memoryCorrectionsLoadError)

        coEvery { dao.getVisibleFirstPage(42L, "main", 17) } returns emptyList()
        vm.loadMoreMemoryCorrections()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.memoryCorrectionsLoadError)
    }

    @Test
    fun correctionWriteRejectsDuplicateWhileStorageIsPending() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val write = CompletableDeferred<Long>()
        coEvery { dao.insert(any()) } coAnswers { write.await() }
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        vm.saveMemoryCorrection(null, "纠正", "main", onResult = { results += it })
        runCurrent()
        vm.saveMemoryCorrection(null, "纠正", "main", onResult = { results += it })
        assertEquals(listOf(false), results)
        write.complete(7L)
        advanceUntilIdle()
        assertEquals(listOf(false, true), results)
        coVerify(exactly = 1) { dao.insert(any()) }
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
    fun recreatedCorrectionEditorReadsPendingWriteReceiptWithoutReplaying() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val write = CompletableDeferred<Long>()
        coEvery { dao.insert(any()) } coAnswers { write.await() }
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.saveMemoryCorrectionFromEditor("editor-one", null, "纠正", "main", null)
        runCurrent()
        assertTrue(vm.knowsCorrectionEditorRequest("editor-one"))
        assertEquals(null, vm.correctionSaveReceipt.value)
        vm.saveMemoryCorrectionFromEditor("editor-one", null, "纠正", "main", null)
        write.complete(7L); advanceUntilIdle()
        assertEquals(ChatViewModel.CorrectionSaveReceipt("editor-one", true), vm.correctionSaveReceipt.value)
        vm.saveMemoryCorrectionFromEditor("editor-one", null, "纠正", "main", null)
        advanceUntilIdle()
        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    fun correctionEditorFailureReceiptKeepsRetryIndependentOfOlderResult() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        coEvery { dao.insert(any()) } throws IllegalStateException("write failed")
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.saveMemoryCorrectionFromEditor("failed", null, "纠正", "main", null)
        advanceUntilIdle()
        assertEquals(ChatViewModel.CorrectionSaveReceipt("failed", false), vm.correctionSaveReceipt.value)
        coEvery { dao.insert(any()) } returns 7L
        vm.saveMemoryCorrectionFromEditor("retry", null, "纠正", "main", null)
        advanceUntilIdle()
        assertEquals(ChatViewModel.CorrectionSaveReceipt("retry", true), vm.correctionSaveReceipt.value)
        assertFalse(vm.knowsCorrectionEditorRequest("failed"))
        coVerify(exactly = 2) { dao.insert(any()) }
    }

    @Test
    fun restoredCorrectionEditorUpdatesSameIdAndPreservesSourceAndCreatedAt() = runTest(testDispatcher) {
        val dao = mockk<SessionMemoryCorrectionDao>(relaxed = true)
        val original = SessionMemoryCorrectionEntity(id = 7L, sessionId = 42L,
            branchId = "main", content = "旧纠正", sourceMessageId = 123L, createdAt = 10L)
        coEvery { dao.getById(42L, 7L) } returns original
        val vm = createViewModel(memoryCorrectionDao = dao)
        advanceUntilIdle()
        vm.saveMemoryCorrectionFromEditor("edit", 7L, "新纠正", "main", 123L)
        advanceUntilIdle()
        coVerify(exactly = 1) { dao.update(match { it.id == 7L && it.content == "新纠正" &&
            it.branchId == "main" && it.sourceMessageId == 123L && it.createdAt == 10L }) }
        coVerify(exactly = 0) { dao.insert(any()) }
        assertEquals(ChatViewModel.CorrectionSaveReceipt("edit", true), vm.correctionSaveReceipt.value)
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
    fun rememberedBranchWaitsForVisibilityRepairBeforePublishingReadyOrReadingTimeline() =
        runTest(testDispatcher) {
            val branch = SessionBranchEntity(
                sessionId = 42L,
                branchId = "branch-1",
                sourceMessageId = 7L,
            )
            val branchDao = mockk<SessionBranchDao>(relaxed = true)
            val messages = mockk<MessageDao>(relaxed = true)
            val memorySegments = mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed = true)
            val eventNodes = mockk<SessionEventNodeDao>(relaxed = true)
            val visibilityManager = mockk<BranchVisibilityIndexManager>(relaxed = true)
            val repairFinished = CompletableDeferred<Unit>()
            coEvery { branchDao.getBySession(42L) } returns listOf(branch)
            coEvery { visibilityManager.ensureReady() } coAnswers { repairFinished.await() }
            coEvery { messages.getVisibleMessagesTail(42L, "branch-1", any()) } returns emptyList()

            val vm = createViewModel(
                messageDao = messages,
                sessionBranchDao = branchDao,
                memorySegmentDao = memorySegments,
                eventNodeDao = eventNodes,
                branchVisibilityIndexManager = visibilityManager,
                uiPreferencesRepository = uiPreferences("branch-1"),
            )
            runCurrent()

            assertFalse(vm.state.value.isReady)
            assertEquals(null, vm.state.value.initialLoadError)
            coVerify(exactly = 0) { messages.getVisibleMessagesTail(42L, "branch-1", any()) }
            coVerify(exactly = 0) { memorySegments.getRecentForBranch(42L, "branch-1", any()) }
            coVerify(exactly = 0) { eventNodes.getPageForBranch(42L, "branch-1", null, null, any()) }

            repairFinished.complete(Unit)
            advanceUntilIdle()

            assertTrue(vm.state.value.isReady)
            coVerify(exactly = 1) { messages.getVisibleMessagesTail(42L, "branch-1", any()) }
            coVerify(exactly = 0) { memorySegments.getRecentForBranch(42L, "branch-1", any()) }
            coVerify(exactly = 0) { eventNodes.getPageForBranch(42L, "branch-1", null, null, any()) }
            vm.loadMemorySummariesIfNeeded()
            advanceUntilIdle()
            coVerify(exactly = 1) { memorySegments.getRecentForBranch(42L, "branch-1", 17) }
            vm.loadEventNodesIfNeeded()
            advanceUntilIdle()
            coVerify(exactly = 1) { eventNodes.getPageForBranch(42L, "branch-1", null, null, any()) }
        }

    @Test
    fun visibilityRepairFailureIsRetryableDuringInitialLoad() = runTest(testDispatcher) {
        val branch = SessionBranchEntity(
            sessionId = 42L,
            branchId = "branch-1",
            sourceMessageId = 7L,
        )
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val messages = mockk<MessageDao>(relaxed = true)
        val visibilityManager = mockk<BranchVisibilityIndexManager>(relaxed = true)
        coEvery { branchDao.getBySession(42L) } returns listOf(branch)
        coEvery { visibilityManager.ensureReady() } throws IllegalStateException("repair failed") andThen Unit
        coEvery { messages.getVisibleMessagesTail(42L, "branch-1", any()) } returns emptyList()

        val vm = createViewModel(
            messageDao = messages,
            sessionBranchDao = branchDao,
            branchVisibilityIndexManager = visibilityManager,
            uiPreferencesRepository = uiPreferences("branch-1"),
        )
        advanceUntilIdle()

        assertFalse(vm.state.value.isReady)
        assertEquals("对话加载失败，请重试", vm.state.value.initialLoadError)
        coVerify(exactly = 0) { messages.getVisibleMessagesTail(42L, "branch-1", any()) }

        vm.retryInitialization()
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        assertEquals(null, vm.state.value.initialLoadError)
        coVerify(exactly = 1) { messages.getVisibleMessagesTail(42L, "branch-1", any()) }
        coVerify(exactly = 2) { visibilityManager.ensureReady() }
    }

    @Test
    fun mainSessionWithoutBranchesSkipsVisibilityRepair() = runTest(testDispatcher) {
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val visibilityManager = mockk<BranchVisibilityIndexManager>(relaxed = true)
        coEvery { branchDao.getBySession(42L) } returns emptyList()

        val vm = createViewModel(
            sessionBranchDao = branchDao,
            branchVisibilityIndexManager = visibilityManager,
            uiPreferencesRepository = uiPreferences("main"),
        )
        advanceUntilIdle()

        assertTrue(vm.state.value.isReady)
        coVerify(exactly = 0) { visibilityManager.ensureReady() }
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

    @Test fun sessionWorldCredentialWriteRetainsOwnerAndRejectsDuplicateUntilReceipt() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val dao = mockk<SessionWorldDao>(relaxed = true)
        val original = SessionWorldEntity(id=17,sessionId=42,worldPrompt="原设定",sessionLlmApiKey="old-key")
        coEvery { dao.getBySession(42L) } returns original
        val finish = CompletableDeferred<Unit>()
        coEvery { dao.upsert(any()) } coAnswers { finish.await();17L }
        val vm = registry.acquire(42L) { store -> createViewModel(sessionWorldDao=dao).also { store.put("vm",it) } }
        var readers = 1
        try {
            advanceUntilIdle()
            val results = mutableListOf<Boolean>()
            vm.saveSessionWorldCredentials(SessionWorldCredentialDraft(sessionLlmApiKey=" new-key ")) { results+=it }
            runCurrent()
            assertTrue(vm.state.value.worldCredentialsSaving)
            assertTrue(results.isEmpty())
            vm.saveSessionWorldCredentials(SessionWorldCredentialDraft(sessionLlmApiKey="duplicate")) { results+=it }
            assertEquals(listOf(false),results)
            registry.release(42L);readers--
            assertTrue(registry.contains(42L));assertFalse(42L in registry.running.value)
            val reopened=registry.acquire<ChatViewModel>(42L) { error("Duplicate write owner") };readers++
            assertTrue(reopened===vm)
            finish.complete(Unit);advanceUntilIdle()
            assertFalse(vm.state.value.worldCredentialsSaving)
            assertEquals(listOf(false,true),results)
            assertEquals(original.copy(sessionLlmApiKey="new-key",updatedAt=vm.state.value.world!!.updatedAt),vm.state.value.world)
            coVerify(exactly=1) { dao.upsert(any()) }
        } finally {
            finish.complete(Unit)
            repeat(readers) { registry.release(42L) }
            advanceUntilIdle()
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
        assertFalse(vm.state.value.worldCredentialsSaving)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test
    fun initialChatLoadsOnlyParticipantPresentationAndUsesFirstParticipantsThinkingFlag() = runTest(testDispatcher) {
        val characters = mockk<CharacterDao>(relaxed = true)
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(
            SessionParticipantEntity(id = 1L, sessionId = 42L, characterId = 7L),
            SessionParticipantEntity(id = 2L, sessionId = 42L, characterId = 8L),
        )
        coEvery { characters.getChatPresentationByIds(listOf(7L, 8L)) } returns listOf(
            ChatCharacterPresentationRow(8L, "乙", "#222222", "avatar-b", "", false),
            ChatCharacterPresentationRow(7L, "甲", "#111111", "avatar-a", "card-a", true),
        )

        val vm = createViewModel(characterDao = characters, participantDao = participants)
        advanceUntilIdle()

        assertEquals("甲", vm.state.value.characterNames[7L])
        assertEquals("乙", vm.state.value.characterNames[8L])
        assertEquals(listOf("甲", "乙"), vm.state.value.characterNames.values.toList())
        assertEquals("card-a", vm.state.value.characterCardImages[7L])
        assertTrue(vm.state.value.characterForcesThinkMax)
        coVerify(exactly = 1) { characters.getChatPresentationByIds(listOf(7L, 8L)) }
        coVerify(exactly = 0) { characters.getById(any()) }
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
        coEvery { characterDao.getChatPresentationByIds(listOf(7L)) } returns listOf(
            ChatCharacterPresentationRow(7L, "青鸾", character.avatarColor,
                character.avatarImagePath, character.cardImagePath, character.thinkMaxEnabled),
        )
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
        assertEquals("添加角色失败，请重试", vm.state.value.participantAddError)
        assertFalse(vm.state.value.participantAdding)
        assertEquals(null, vm.state.value.participantAddedId)
        assertFalse(vm.state.value.error.orEmpty().contains("database unavailable"))
    }

    @Test fun participantWriteRetainsOwnerRejectsDuplicateAndPublishesReceipt() = runTest(testDispatcher) {
        val registry=com.mojing.app.ui.chat.RetainedChatSessions.stores
        val characters=mockk<CharacterDao>(relaxed=true)
        val participants=mockk<ParticipantDao>(relaxed=true)
        val worlds=mockk<SessionWorldDao>(relaxed=true)
        val finish=CompletableDeferred<Unit>()
        var committed=false
        coEvery { characters.getById(7L) } returns CharacterEntity(id=7,name="青鸾",boundEncyclopediaId=3)
        coEvery { worlds.getBySession(42L) } returns SessionWorldEntity(sessionId=42,encyclopediaId=3)
        coEvery { participants.getBySession(42L) } answers { if(committed) listOf(SessionParticipantEntity(id=9,sessionId=42,characterId=7)) else emptyList() }
        coEvery { participants.upsert(any()) } coAnswers { finish.await();committed=true;9L }
        val vm=registry.acquire(42L) { store->createViewModel(characterDao=characters,participantDao=participants,sessionWorldDao=worlds).also { store.put("vm",it) } }
        var readers=1
        try {
            advanceUntilIdle()
            val results=mutableListOf<Boolean>()
            vm.addParticipant(7) { results+=it };runCurrent()
            assertTrue(vm.state.value.participantAdding)
            vm.addParticipant(7) { results+=it }
            assertEquals(listOf(false),results)
            registry.release(42);readers--
            assertTrue(registry.contains(42));assertFalse(42L in registry.running.value)
            val reopened=registry.acquire<ChatViewModel>(42) { error("Duplicate participant owner") };readers++
            assertTrue(reopened===vm)
            vm.clearParticipantAddFeedback();assertTrue(vm.state.value.participantAdding)
            finish.complete(Unit);advanceUntilIdle()
            assertFalse(vm.state.value.participantAdding)
            assertEquals(7L,vm.state.value.participantAddedId)
            assertEquals(listOf(7L),vm.state.value.participants.map { it.characterId })
            assertEquals(listOf(false,true),results)
            coVerify(exactly=1) { participants.upsert(any()) }
            vm.clearParticipantAddFeedback();assertEquals(null,vm.state.value.participantAddedId)
        } finally { finish.complete(Unit);repeat(readers) { registry.release(42) };advanceUntilIdle() }
    }

    @Test fun participantQualificationFailureKeepsRetryableFeedbackAndNoWrite() = runTest(testDispatcher) {
        val characters=mockk<CharacterDao>(relaxed=true)
        val participants=mockk<ParticipantDao>(relaxed=true)
        coEvery { characters.getById(7L) } returns CharacterEntity(id=7,name="青鸾",boundEncyclopediaId=0)
        val vm=createViewModel(characterDao=characters,participantDao=participants)
        advanceUntilIdle()
        vm.addParticipant(7);advanceUntilIdle()
        assertFalse(vm.state.value.participantAdding)
        assertEquals("该角色尚未绑定世界资料，无法加入当前对话",vm.state.value.participantAddError)
        assertEquals(null,vm.state.value.participantAddedId)
        coVerify(exactly=0) { participants.upsert(any()) }
        vm.clearParticipantAddFeedback();assertEquals(null,vm.state.value.participantAddError)
    }

    @Test
    fun addParticipantPickerReadsOnlyBoundEligiblePageWithStableCursor() = runTest(testDispatcher) {
        val characterDao = mockk<CharacterDao>(relaxed = true)
        val worldDao = mockk<SessionWorldDao>(relaxed = true)
        coEvery { worldDao.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L, encyclopediaId = 3L,
        )
        val options = (1L..41L).map { id ->
            NewSessionCharacterOption(id, "角色$id", 5L, false, 100L - id)
        }
        coEvery { characterDao.getAddParticipantPage(42L, 3L, "青", null, null, null, null, 41) } returns options
        coEvery { characterDao.getAddParticipantPage(42L, 3L, "青", 5L, false, 60L, 40L, 41) } returns
            listOf(options.last())
        val vm = createViewModel(characterDao = characterDao, sessionWorldDao = worldDao)
        advanceUntilIdle()

        val first = vm.loadAddParticipantPage(" 青 ", null)
        val second = vm.loadAddParticipantPage("青", first.rows.last())

        assertEquals(40, first.rows.size)
        assertTrue(first.hasMore)
        assertEquals(listOf(41L), second.rows.map { it.id })
        assertFalse(second.hasMore)
        coVerify(exactly = 0) { characterDao.getAll() }
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
        assertTrue(vm.state.value.participantTalkativenessSaving.isEmpty())
    }

    @Test fun talkativenessWriteRetainsOwnerAndRejectsDuplicateUntilCommitted() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val original = SessionParticipantEntity(id = 9, sessionId = 42, characterId = 7, talkativeness = 0.7f)
        val participants = mockk<ParticipantDao>(relaxed = true)
        val finish = CompletableDeferred<Unit>()
        var stored = original
        coEvery { participants.getBySession(42) } answers { listOf(stored) }
        coEvery { participants.getById(9) } answers { stored }
        coEvery { participants.upsert(any()) } coAnswers { finish.await(); stored = firstArg(); 9L }
        val vm = registry.acquire(42L) { store -> createViewModel(participantDao = participants).also { store.put("vm", it) } }
        var readers = 1
        try {
            advanceUntilIdle()
            val results = mutableListOf<Boolean>()
            vm.updateParticipantTalkativeness(9, 0.25f) { results += it }; runCurrent()
            assertEquals(mapOf(9L to 0.25f), vm.state.value.participantTalkativenessSaving)
            vm.updateParticipantTalkativeness(9, 0.9f) { results += it }
            vm.toggleMute(9); vm.removeParticipant(9)
            assertEquals(listOf(false), results)
            registry.release(42); readers--
            assertTrue(registry.contains(42)); assertFalse(42L in registry.running.value)
            val reopened = registry.acquire<ChatViewModel>(42) { error("Duplicate talkativeness owner") }; readers++
            assertTrue(reopened === vm)
            assertEquals(0.7f, reopened.state.value.participants.single().talkativeness)
            finish.complete(Unit); advanceUntilIdle()
            assertTrue(reopened.state.value.participantTalkativenessSaving.isEmpty())
            assertEquals(original.copy(talkativeness = 0.25f), reopened.state.value.participants.single())
            assertEquals(listOf(false, true), results)
            coVerify(exactly = 1) { participants.upsert(any()) }
            coVerify(exactly = 0) { participants.delete(any()) }
        } finally { finish.complete(Unit); repeat(readers) { registry.release(42) }; advanceUntilIdle() }
    }

    @Test fun talkativenessRejectsForeignSessionParticipantAndReleasesBusy() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getById(9) } returns SessionParticipantEntity(id = 9, sessionId = 43, characterId = 7)
        val vm = createViewModel(participantDao = participants)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        vm.updateParticipantTalkativeness(9, 0.25f) { results += it }; advanceUntilIdle()
        assertEquals(listOf(false), results)
        assertTrue(vm.state.value.participantTalkativenessSaving.isEmpty())
        coVerify(exactly = 0) { participants.upsert(any()) }
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
    fun thinkMaxSaveFailureKeepsPersistedChoiceAndCanRetry() = runTest(testDispatcher) {
        val sessions = existingSessionDao(42L)
        val storage = validSecureStorage()
        every { storage.allowSessionThinkMax } returns true
        var persisted = SessionEntity(id = 42L)
        coEvery { sessions.getById(42L) } answers { persisted }
        coEvery { sessions.updateThinkMax(42L, true, any()) } throws IllegalStateException("database unavailable")
        val vm = createViewModel(sessionDao = sessions, secureStorage = storage)
        advanceUntilIdle()

        vm.setSessionThinkMax(true) {}
        assertTrue(vm.state.value.sessionThinkMaxSaving)
        vm.setSessionThinkMax(true) {}
        advanceUntilIdle()
        coVerify(exactly = 1) { sessions.updateThinkMax(42L, true, any()) }
        assertFalse(vm.state.value.sessionThinkMaxEnabled)
        assertFalse(vm.state.value.sessionThinkMaxSaving)
        assertEquals("思考/Max 设置未保存，请重试", vm.state.value.sessionThinkMaxSaveError)

        coEvery { sessions.updateThinkMax(42L, true, any()) } answers { persisted = persisted.copy(thinkMaxEnabled = true) }
        vm.setSessionThinkMax(true) {}
        assertEquals(null, vm.state.value.sessionThinkMaxSaveError)
        advanceUntilIdle()
        assertTrue(vm.state.value.sessionThinkMaxEnabled)
        assertFalse(vm.state.value.sessionThinkMaxSaving)
        val reopened = createViewModel(sessionDao = sessions, secureStorage = storage)
        advanceUntilIdle()
        assertTrue(reopened.state.value.sessionThinkMaxEnabled)
    }

    @Test
    fun delayedAutoNarratorIsCancelledByStopBranchOrLeaving() = runTest(testDispatcher) {
        for (action in listOf("none", "stop", "branch", "leave", "newRound")) {
            val storage = validSecureStorage()
            every { storage.speakerTurnMode } returns "manual"
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, narratorEnabled = true)
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val messages = mockk<MessageDao>(relaxed = true)
            coEvery { messages.getMainContextTail(42L, any()) } returns (1L..4L).map {
                MessageEntity(id = it, sessionId = 42L, speakerType = "character", content = "已有剧情$it")
            }
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                flowOf(StreamState.Done("角色回复"))
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                flowOf(StreamState.Done("旁白回复"))
            val vm = createViewModel(messageDao = messages, secureStorage = storage, sessionWorldDao = world,
                characterDao = characters, participantDao = participants, chatEngine = engine,
                llmApiService = validLlmApiService())
            advanceUntilIdle()
            vm.setManualReplyCharacterId(3L)
            vm.updateInput("继续剧情")
            vm.sendMessage()
            runCurrent()
            assertFalse("action=$action error=${vm.state.value.error}", vm.state.value.isGenerating)
            verify(exactly = 1) { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            testScheduler.advanceTimeBy(200)
            when (action) {
                "stop" -> vm.stopGeneration()
                "branch" -> vm.switchBranch("main")
                "leave" -> vm.cancelPendingAutoNarrator()
                "newRound" -> {
                    vm.setManualReplyCharacterId(3L)
                    vm.updateInput("新一轮")
                    vm.sendMessage()
                }
            }
            runCurrent()
            testScheduler.advanceTimeBy(250)
            runCurrent()
            verify(exactly = if (action == "none") 1 else 0) {
                engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            advanceUntilIdle()
            verify(exactly = if (action == "none" || action == "newRound") 1 else 0) {
                engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Test
    fun thinkMaxSaveBlocksReplyUntilPersistedAndRejectsDuplicateChanges() = runTest(testDispatcher) {
        val sessions = existingSessionDao(42L)
        val storage = validSecureStorage()
        val messages = mockk<MessageDao>(relaxed = true)
        every { storage.allowSessionThinkMax } returns true
        val saving = CompletableDeferred<Unit>()
        coEvery { sessions.updateThinkMax(42L, true, any()) } coAnswers { saving.await() }
        val vm = createViewModel(sessionDao = sessions, secureStorage = storage, messageDao = messages)
        advanceUntilIdle()
        vm.setSessionThinkMax(true) {}
        runCurrent()
        vm.setSessionThinkMax(false) {}
        vm.updateInput("保留待发送内容")
        vm.sendMessage()
        runCurrent()
        assertTrue(vm.state.value.sessionThinkMaxSaving)
        assertFalse(vm.state.value.isGenerating)
        assertEquals("保留待发送内容", vm.state.value.inputText)
        coVerify(exactly = 0) { messages.insert(any()) }
        coVerify(exactly = 0) { sessions.updateThinkMax(42L, false, any()) }
        saving.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.sessionThinkMaxEnabled)
        assertFalse(vm.state.value.sessionThinkMaxSaving)
    }

    @Test
    fun thinkMaxSaveRetainsLocalOwnerAndReturnsCommittedChoiceAfterReopen() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val sessions = existingSessionDao(42L)
        val storage = validSecureStorage()
        every { storage.allowSessionThinkMax } returns true
        val saving = CompletableDeferred<Unit>()
        coEvery { sessions.updateThinkMax(42L, true, any()) } coAnswers { saving.await() }
        val vm = registry.acquire(42L) { store -> createViewModel(sessionDao = sessions, secureStorage = storage).also { store.put("vm", it) } }
        try {
            advanceUntilIdle(); vm.setSessionThinkMax(true) {}; runCurrent()
            registry.release(42L)
            assertTrue(registry.contains(42L)); assertFalse(42L in registry.running.value)
            val reopened = registry.acquire<ChatViewModel>(42L) { error("Lost think/Max save owner") }
            assertTrue(reopened === vm); assertTrue(vm.state.value.sessionThinkMaxSaving)
            vm.setSessionThinkMax(false) {}; runCurrent()
            coVerify(exactly = 0) { sessions.updateThinkMax(42L, false, any()) }
            saving.complete(Unit); advanceUntilIdle()
            assertTrue(vm.state.value.sessionThinkMaxEnabled); assertFalse(vm.state.value.sessionThinkMaxSaving)
            coVerify(exactly = 1) { sessions.updateThinkMax(42L, true, any()) }
        } finally { saving.complete(Unit); registry.stop(42L); registry.release(42L); advanceUntilIdle() }
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
        assertTrue(vm.state.value.participantRemoving.isEmpty())
    }

    @Test fun participantRemovalRetainsOwnerAndBlocksConflictingRowActions() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val original = SessionParticipantEntity(id = 9, sessionId = 42, characterId = 7)
        val participants = mockk<ParticipantDao>(relaxed = true)
        val finish = CompletableDeferred<Unit>()
        var removed = false
        coEvery { participants.getBySession(42) } answers { if(removed) emptyList() else listOf(original) }
        coEvery { participants.getById(9) } returns original
        coEvery { participants.delete(9) } coAnswers { finish.await(); removed = true }
        val vm = registry.acquire(42L) { store -> createViewModel(participantDao = participants).also { store.put("vm", it) } }
        var readers = 1
        try {
            advanceUntilIdle(); vm.setManualReplyCharacterId(7)
            vm.removeParticipant(9); runCurrent()
            assertEquals(setOf(9L), vm.state.value.participantRemoving)
            vm.removeParticipant(9); vm.toggleMute(9)
            val results = mutableListOf<Boolean>()
            vm.updateParticipantTalkativeness(9, 0.25f) { results += it }
            assertEquals(listOf(false), results)
            registry.release(42); readers--
            assertTrue(registry.contains(42)); assertFalse(42L in registry.running.value)
            val reopened = registry.acquire<ChatViewModel>(42) { error("Duplicate removal owner") }; readers++
            assertTrue(reopened === vm)
            finish.complete(Unit); advanceUntilIdle()
            assertTrue(reopened.state.value.participantRemoving.isEmpty())
            assertTrue(reopened.state.value.participants.isEmpty())
            assertEquals(null, reopened.state.value.manualReplyCharacterId)
            coVerify(exactly = 1) { participants.delete(9) }
            coVerify(exactly = 0) { participants.upsert(any()) }
        } finally { finish.complete(Unit); repeat(readers) { registry.release(42) }; advanceUntilIdle() }
    }

    @Test fun participantRemovalRejectsForeignRowAndReleasesBusy() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getById(9) } returns SessionParticipantEntity(id = 9, sessionId = 43, characterId = 7)
        val vm = createViewModel(participantDao = participants)
        advanceUntilIdle(); vm.removeParticipant(9); advanceUntilIdle()
        assertTrue(vm.state.value.participantRemoving.isEmpty())
        assertEquals("该角色已不在当前对话中", vm.state.value.error)
        coVerify(exactly = 0) { participants.delete(any()) }
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
        assertTrue(vm.state.value.participantMuteSaving.isEmpty())
    }

    @Test fun participantMuteWriteRetainsOwnerAndBlocksConflictingRowActions() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val original = SessionParticipantEntity(id = 9, sessionId = 42, characterId = 7, talkativeness = 0.25f)
        val participants = mockk<ParticipantDao>(relaxed = true)
        val finish = CompletableDeferred<Unit>()
        var stored = original
        coEvery { participants.getBySession(42) } answers { listOf(stored) }
        coEvery { participants.getById(9) } answers { stored }
        coEvery { participants.upsert(any()) } coAnswers { finish.await(); stored = firstArg(); 9L }
        val vm = registry.acquire(42L) { store -> createViewModel(participantDao = participants).also { store.put("vm", it) } }
        var readers = 1
        try {
            advanceUntilIdle()
            vm.toggleMute(9); runCurrent()
            assertEquals(setOf(9L), vm.state.value.participantMuteSaving)
            vm.toggleMute(9); vm.removeParticipant(9)
            val results = mutableListOf<Boolean>()
            vm.updateParticipantTalkativeness(9, 0.9f) { results += it }
            assertEquals(listOf(false), results)
            registry.release(42); readers--
            assertTrue(registry.contains(42)); assertFalse(42L in registry.running.value)
            val reopened = registry.acquire<ChatViewModel>(42) { error("Duplicate mute owner") }; readers++
            assertTrue(reopened === vm)
            finish.complete(Unit); advanceUntilIdle()
            assertTrue(reopened.state.value.participantMuteSaving.isEmpty())
            assertEquals(original.copy(muted = true), reopened.state.value.participants.single())
            coVerify(exactly = 1) { participants.upsert(any()) }
            coVerify(exactly = 0) { participants.delete(any()) }
        } finally { finish.complete(Unit); repeat(readers) { registry.release(42) }; advanceUntilIdle() }
    }

    @Test fun participantMuteRejectsForeignRowAndReleasesBusy() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getById(9) } returns SessionParticipantEntity(id = 9, sessionId = 43, characterId = 7)
        val vm = createViewModel(participantDao = participants)
        advanceUntilIdle(); vm.toggleMute(9); advanceUntilIdle()
        assertTrue(vm.state.value.participantMuteSaving.isEmpty())
        assertEquals("该角色已不在当前对话中", vm.state.value.error)
        coVerify(exactly = 0) { participants.upsert(any()) }
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

    @Test fun worldSettingWriteRetainsOwnerAndRejectsConflictsBeforeDraftSubmission() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val dao = mockk<SessionWorldDao>(relaxed = true)
        val messages = mockk<MessageDao>(relaxed = true)
        val drafts = emptyDraftStore()
        val original = SessionWorldEntity(id=17,sessionId=42,narratorEnabled=false,worldPrompt="原设定",sessionLlmApiKey="synthetic-old")
        var stored = original
        coEvery { dao.getBySession(42) } answers { stored }
        val gate = CompletableDeferred<Unit>()
        coEvery { dao.upsert(any()) } coAnswers { gate.await();stored=firstArg();17L }
        val vm = registry.acquire(42L) { store -> createViewModel(sessionWorldDao=dao,messageDao=messages,chatDraftStore=drafts).also { store.put("vm",it) } }
        var readers=1
        try {
            advanceUntilIdle();vm.updateInput("未发送草稿");advanceUntilIdle()
            vm.updateWorldSetting("narratorEnabled",true);runCurrent()
            vm.updateWorldSetting("antiCheatEnabled",false)
            val results=mutableListOf<Boolean>()
            vm.saveSessionWorldCredentials(SessionWorldCredentialDraft(sessionLlmApiKey="synthetic-new")) { results+=it }
            vm.sendMessage();runCurrent()
            assertEquals(listOf(false),results)
            assertEquals("未发送草稿",vm.state.value.inputText);assertFalse(vm.state.value.isGenerating)
            assertEquals("本场设置正在保存，请稍候再发送",vm.state.value.error)
            coVerify(exactly=0) { messages.insert(any()) }
            verify(exactly=0) { drafts.saveBeforeSubmission(any(),any()) }
            registry.release(42);readers--
            assertTrue(registry.contains(42));assertFalse(42L in registry.running.value)
            val reopened=registry.acquire<ChatViewModel>(42) { error("Lost world write owner") };readers++
            assertTrue(reopened===vm)
            gate.complete(Unit);advanceUntilIdle()
            assertEquals(original.copy(narratorEnabled=true),stored)
            assertEquals(stored,reopened.state.value.world)
            coVerify(exactly=1) { dao.upsert(any()) }
        } finally { gate.complete(Unit);repeat(readers) { registry.release(42) };advanceUntilIdle() }
    }

    @Test fun credentialsWriteRejectsWorldMutationAndSendingUntilComplete() = runTest(testDispatcher) {
        val dao=mockk<SessionWorldDao>(relaxed=true)
        val messages=mockk<MessageDao>(relaxed=true)
        val drafts=emptyDraftStore()
        val original=SessionWorldEntity(id=17,sessionId=42,narratorEnabled=false)
        var stored=original
        coEvery { dao.getBySession(42) } answers { stored }
        val gate=CompletableDeferred<Unit>()
        coEvery { dao.upsert(any()) } coAnswers { gate.await();stored=firstArg();17L }
        val vm=createViewModel(sessionWorldDao=dao,messageDao=messages,chatDraftStore=drafts)
        advanceUntilIdle();vm.updateInput("未发送草稿");advanceUntilIdle()
        vm.saveSessionWorldCredentials(SessionWorldCredentialDraft(sessionLlmBaseUrl="https://example.test/v1"));runCurrent()
        vm.updateWorldSetting("narratorEnabled",true);vm.sendMessage();runCurrent()
        assertEquals("本场设置正在保存，请稍候再发送",vm.state.value.error)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(original.copy(sessionLlmBaseUrl="https://example.test/v1",updatedAt=stored.updatedAt),stored)
        assertEquals(stored,vm.state.value.world);assertEquals("未发送草稿",vm.state.value.inputText)
        coVerify(exactly=1) { dao.upsert(any()) }
        coVerify(exactly=0) { messages.insert(any()) }
        verify(exactly=0) { drafts.saveBeforeSubmission(any(),any()) }
    }

    @Test fun sixWorldSettingsUseLatestPersistedRowAndFailureAllowsRetry() = runTest(testDispatcher) {
        val dao=mockk<SessionWorldDao>(relaxed=true)
        val original=SessionWorldEntity(id=17,sessionId=42,narratorEnabled=false,choiceGenerationEnabled=false,
            antiCheatEnabled=false,autoSedimentEnabled=false,autoCharacterImageGen=false,autoCharacterSpeech=false)
        var stored=original
        coEvery { dao.getBySession(42) } answers { stored }
        var fails=false
        coEvery { dao.upsert(any()) } answers { if(fails) error("private disk error");stored=firstArg();17L }
        val vm=createViewModel(sessionWorldDao=dao)
        advanceUntilIdle()
        vm.updateWorldSetting("unknown",true);runCurrent()
        assertFalse(vm.state.value.worldSettingSaving)
        coVerify(exactly=0) { dao.upsert(any()) }
        stored=original.copy(worldPrompt="持久层更新",sessionLlmBaseUrl="https://example.test/v1")
        val latest=stored
        val keys=listOf("narratorEnabled","choiceGenerationEnabled","antiCheatEnabled","autoSedimentEnabled","autoCharacterImageGen","autoCharacterSpeech")
        keys.forEach { key ->
            val before=stored
            fails=true;vm.updateWorldSetting(key,true);advanceUntilIdle()
            assertEquals(before,stored);assertFalse(vm.state.value.worldSettingSaving)
            assertEquals("本场玩法保存失败，请重试",vm.state.value.error)
            fails=false;vm.updateWorldSetting(key,true);advanceUntilIdle()
            assertFalse(vm.state.value.worldSettingSaving)
            assertEquals(stored,vm.state.value.world)
        }
        assertEquals(latest.copy(narratorEnabled=true,choiceGenerationEnabled=true,antiCheatEnabled=true,
            autoSedimentEnabled=true,autoCharacterImageGen=true,autoCharacterSpeech=true),stored)
        coVerify(exactly=12) { dao.upsert(any()) }
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
    fun followingConfiguredModelsRetainsSelectionOnFailureAndCanRetry() = runTest(testDispatcher) {
        val storage = validSecureStorage()
        var selection: Pair<String, String>? = "a" to "one"
        every { storage.modelPlatforms() } returns listOf(com.mojing.app.data.ModelPlatform("a", "A", "https://a.test", "test-key", listOf("one")))
        every { storage.sessionModelSelection(42L) } answers { selection }
        every { storage.clearSessionModelSelection(42L) } throws IllegalStateException("write failed")
        val vm = createViewModel(secureStorage = storage)
        advanceUntilIdle()
        var saved = false
        vm.followConfiguredChatModels { saved = true }
        vm.followConfiguredChatModels { saved = true }
        vm.state.first { !it.modelSelectionSaving }
        assertFalse(saved)
        assertNotNull(vm.state.value.modelSelectionError)
        assertEquals("A · one", vm.modelSelectionLabel.value)
        verify(exactly = 1) { storage.clearSessionModelSelection(42L) }
        every { storage.clearSessionModelSelection(42L) } answers { selection = null }
        vm.followConfiguredChatModels { saved = true }
        vm.state.first { !it.modelSelectionSaving }
        assertTrue(saved)
        assertEquals(null, vm.state.value.modelSelectionError)
        assertEquals(null, vm.currentChatModelSelection())
        assertEquals("跟随角色与模型设置", vm.modelSelectionLabel.value)
    }

    @Test
    fun failedModelSelectionKeepsOldLabelAndCanRetry() = runTest(testDispatcher) {
        val storage = validSecureStorage()
        val platform = com.mojing.app.data.ModelPlatform("a", "A", "https://a.test", "test-key", listOf("old", "new"))
        var selection = "a" to "old"
        every { storage.modelPlatforms() } returns listOf(platform)
        every { storage.sessionModelSelection(42L) } answers { selection }
        every { storage.selectSessionModel(42L, "a", "new") } throws IllegalStateException("write failed")
        val vm = createViewModel(secureStorage = storage)
        advanceUntilIdle()
        var saved = false
        vm.selectChatModel("a", "new") { saved = true }
        assertTrue(vm.state.value.modelSelectionSaving)
        vm.selectChatModel("a", "new") { saved = true }
        vm.state.first { !it.modelSelectionSaving }
        assertFalse(saved)
        assertNotNull(vm.state.value.modelSelectionError)
        assertEquals("A · old", vm.modelSelectionLabel.value)
        verify(exactly = 1) { storage.selectSessionModel(42L, "a", "new") }

        every { storage.selectSessionModel(42L, "a", "new") } answers { selection = "a" to "new" }
        vm.selectChatModel("a", "new") { saved = true }
        assertEquals(null, vm.state.value.modelSelectionError)
        vm.state.first { !it.modelSelectionSaving }
        assertTrue(saved)
        assertEquals("A · new", vm.modelSelectionLabel.value)
        assertEquals(null, vm.state.value.modelSelectionError)
    }

    @Test
    fun resumeRefreshesDefaultModelLabelWithoutReplacingSessionSelection() = runTest(testDispatcher) {
        val storage = validSecureStorage()
        var defaultModel = "old-default"
        var selection: Pair<String, String>? = null
        var platform = com.mojing.app.data.ModelPlatform("a", "A", "https://a.test/v1", "test-key", listOf("session-model"))
        every { storage.publicModel } answers { defaultModel }
        every { storage.sessionModelSelection(42L) } answers { selection }
        every { storage.modelPlatforms() } answers { listOf(platform) }
        val vm = createViewModel(secureStorage = storage)
        advanceUntilIdle()
        assertEquals("跟随角色与模型设置", vm.modelSelectionLabel.value)

        defaultModel = "new-default"
        vm.refreshModelSelection()
        assertEquals("跟随角色与模型设置", vm.modelSelectionLabel.value)

        selection = "a" to "session-model"
        vm.refreshModelSelection()
        assertEquals("A · session-model", vm.modelSelectionLabel.value)
        assertEquals("a" to "session-model", vm.currentChatModelSelection())
        platform = platform.copy(name = "Renamed")
        defaultModel = "another-default"
        vm.refreshModelSelection()
        assertEquals("Renamed · session-model", vm.modelSelectionLabel.value)

        platform = platform.copy(models = listOf("replacement"))
        vm.refreshModelSelection()
        assertEquals("请选择模型", vm.modelSelectionLabel.value)
        assertEquals("a" to "session-model", selection)
        verify(exactly = 0) { storage.selectSessionModel(any(), any(), any()) }
    }

    @Test
    fun inheritedChatRouteKeepsModelWithResolvedConnection() = runTest(testDispatcher) {
        for (worldOverride in listOf(false, true)) {
            val storage = validSecureStorage()
            every { storage.publicModel } returns "public-model"
            every { storage.modelPlatforms() } returns emptyList()
            every { storage.sessionModelSelection(42L) } returns null
            every { storage.speakerTurnMode } returns "manual"
            val routes = mutableListOf<List<String>>()
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                routes.add(listOf(args[6] as String, args[7] as String, args[8] as String))
                flowOf(StreamState.Done("角色回复"))
            }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L,
                sessionLlmApiKey = if (worldOverride) "world-key" else "",
                sessionLlmBaseUrl = if (worldOverride) "https://world.test/v1" else "")
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色",
                apiKey = "character-key", apiBaseUrl = "https://character.test/v1", modelName = "character-model")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val vm = createViewModel(secureStorage = storage, sessionWorldDao = world, characterDao = characters,
                participantDao = participants, chatEngine = engine, llmApiService = validLlmApiService())
            advanceUntilIdle()
            vm.setManualReplyCharacterId(3L)
            vm.updateInput("你好")
            vm.sendMessage()
            advanceUntilIdle()
            val expected = if (worldOverride) listOf("world-key", "https://world.test/v1", "public-model")
                else listOf("character-key", "https://character.test/v1", "character-model")
            assertEquals("worldOverride=$worldOverride error=${vm.state.value.error}", listOf(expected), routes)
        }
    }

    @Test
    fun manualMaintenanceFreezesPlatformBeforeAsyncPreparation() = runTest(testDispatcher) {
        for ((summary, identified) in listOf(false to true, true to true, false to false, true to false)) {
            val a = com.mojing.app.data.ModelPlatform("a", "A", "https://a.test/v1", "fake-a", listOf("a-model"), modelContextWindows = mapOf("a-model" to 6000))
            val b = com.mojing.app.data.ModelPlatform("b", "B", "https://b.test/v1", "fake-b", listOf("b-model"), modelContextWindows = mapOf("b-model" to 9000))
            var selection: Pair<String, String>? = if (identified) "a" to "a-model" else null
            val storage = validSecureStorage()
            every { storage.modelPlatforms() } returns listOf(a, b)
            every { storage.sessionModelSelection(42L) } answers { selection }
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "甲")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val memory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
            val compactor = mockk<com.mojing.app.domain.engine.MemoryCompactor>(relaxed = true)
            coEvery { compactor.pendingBatch(any(), any(), any()) } returns com.mojing.app.domain.engine.MemoryCompactor.PendingBatch(100, 10)
            val vm = createViewModel(secureStorage = storage, characterDao = characters, participantDao = participants,
                contextMemory = memory, compactor = compactor, llmApiService = validLlmApiService())
            advanceUntilIdle()
            if (summary) vm.continueCurrentStorySummary() else vm.rebuildCurrentContextMemory()
            selection = "b" to "b-model"
            advanceUntilIdle()
            val key = if (identified) "fake-a" else "sk-test"
            val base = if (identified) "https://a.test/v1" else "https://api.test.com/v1"
            val model = if (identified) "a-model" else "test-model"
            val capacity = if (identified) 6000 else null
            if (summary) coVerify(exactly = 1) { compactor.compactIfNeeded(42L, "main", key, base, model, any(), any(), any(), any(), capacity) }
            else coVerify(exactly = 1) { memory.rebuild(42L, "main", any(), key, base, model, any(), any(), capacity) }
            assertFalse(vm.state.value.memoryOperationRunning)
        }
    }

    @Test
    fun manualMaintenanceMissingPlatformReportsWithoutStartingOperation() = runTest(testDispatcher) {
        val storage = validSecureStorage()
        every { storage.modelPlatforms() } returns emptyList()
        every { storage.sessionModelSelection(42L) } returns ("removed" to "model")
        val vm = createViewModel(secureStorage = storage)
        advanceUntilIdle()
        val notices = mutableListOf<String>()
        vm.continueCurrentStorySummary { notices += it }
        vm.rebuildCurrentContextMemory { notices += it }
        assertEquals(2, notices.size)
        assertTrue(notices.all { it.contains("重新选择") })
        assertFalse(vm.state.value.memoryOperationRunning)
    }

    @Test
    fun postMaintenanceKeepsFrozenRouteAfterGenerationEndsAndSelectionChanges() = runTest(testDispatcher) {
        for (narrator in listOf(false, true)) {
            val a = com.mojing.app.data.ModelPlatform("a", "A", "https://a.test/v1", "fake-a", listOf("a-model"), modelContextWindows = mapOf("a-model" to 6000))
            val b = com.mojing.app.data.ModelPlatform("b", "B", "https://b.test/v1", "fake-b", listOf("b-model"), modelContextWindows = mapOf("b-model" to 9000))
            var selection = "a" to "a-model"
            val storage = validSecureStorage()
            every { storage.modelPlatforms() } returns listOf(a, b)
            every { storage.sessionModelSelection(42L) } answers { selection }
            every { storage.speakerTurnMode } returns "manual"
            val worlds = mockk<SessionWorldDao>(relaxed = true)
            coEvery { worlds.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, autoSedimentEnabled = true, encyclopediaId = 9)
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "甲")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf(StreamState.Done("已完成角色正文"))
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf(StreamState.Done("已完成旁白正文"))
            val memory = mockk<com.mojing.app.domain.engine.UniversalContextMemoryManager>(relaxed = true)
            val events = mockk<com.mojing.app.domain.engine.MemoryV2Manager>(relaxed = true)
            val sediment = mockk<com.mojing.app.domain.engine.SedimentEngine>(relaxed = true)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
            coEvery { memory.updateAfterMessages(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                entered.complete(Unit); release.await(); com.mojing.app.domain.engine.UniversalContextMemoryUpdateResult.FAILED
            }
            coEvery { sediment.sedimentFromMessages(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { finished.complete(Unit); Unit }
            val vm = createViewModel(secureStorage = storage, sessionWorldDao = worlds, characterDao = characters,
                participantDao = participants, chatEngine = engine, contextMemory = memory, events = events,
                sediment = sediment, llmApiService = validLlmApiService())
            advanceUntilIdle()
            if (narrator) assertTrue(vm.requestNarrator()) else { vm.setManualReplyCharacterId(3L); vm.updateInput("继续"); vm.sendMessage() }
            advanceUntilIdle(); entered.await()
            assertFalse(vm.state.value.isGenerating)
            selection = "b" to "b-model"; release.complete(Unit); finished.await()
            coVerify(exactly = 1) { memory.updateAfterMessages(42L, "main", any(), "fake-a", "https://a.test/v1", "a-model", any(), any(), 6000) }
            coVerify(exactly = 1) { events.extractEventNodes(42L, "main", any(), any(), "fake-a", "https://a.test/v1", "a-model", 6000) }
            coVerify(exactly = 1) { sediment.sedimentFromMessages(9L, 42L, "main", any(), "fake-a", "https://a.test/v1", "a-model", 6000) }
            assertEquals(null, vm.state.value.error)
        }
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
            lateinit var vm: ChatViewModel
            val routes = mutableListOf<List<String>>()
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals(args[5], vm.state.value.lastRequestModel)
                assertEquals(if (args[3] == "fake-a") "A" else "B", vm.state.value.lastRequestPlatform)
                assertEquals(if (args[3] == "fake-a") "a" else "b", args[10])
                routes.add(listOf(args[3] as String, args[4] as String, args[5] as String))
                flowOf(StreamState.Done("旁白测试回复"))
            }
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals(args[8], vm.state.value.lastRequestModel)
                assertEquals(if (args[6] == "fake-a") "A" else "B", vm.state.value.lastRequestPlatform)
                assertEquals(if (args[6] == "fake-a") "a" else "b", args[11])
                routes.add(listOf(args[6] as String, args[7] as String, args[8] as String))
                flowOf(StreamState.Done("角色测试回复"))
            }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L)
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "测试角色", modelName = "old-character-model")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            vm = createViewModel(secureStorage = storage, sessionWorldDao = world,
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
                assertEquals(model, vm.state.value.lastRequestModel)
            }
            assertEquals("narrator=$narrator error=${vm.state.value.error}", listOf(
                listOf("fake-a", "https://a.test/v1", "a-one"),
                listOf("fake-a", "https://a.test/v1", "a-two"),
                listOf("fake-b", "https://b.test/v1", "b-one"),
            ), routes)
        }
    }

    @Test
    fun followingSettingsUsesFrozenMatchingPlatformCapacityForCharactersAndNarrator() = runTest(testDispatcher) {
        for (narrator in listOf(false, true)) {
            val storage = validSecureStorage()
            val saved = com.mojing.app.data.ModelPlatform("saved", "Saved", "https://api.test.com/v1", "sk-test",
                listOf("test-model"), modelContextWindows = mapOf("test-model" to 16384))
            var platforms = listOf(saved)
            every { storage.modelPlatforms() } answers { platforms }
            every { storage.speakerTurnMode } returns "manual"
            val engine = mockk<ChatEngine>(relaxed = true)
            val capacities = mutableListOf<Int?>()
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals("sk-test", args[3]); assertEquals("test-model", args[5])
                assertEquals(null, args[10])
                capacities += args[11] as Int?
                flowOf(StreamState.Done("旁白测试回复"))
            }
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals("sk-test", args[6]); assertEquals("test-model", args[8])
                assertEquals(null, args[11])
                capacities += (args[5] as com.mojing.app.domain.engine.TokenBudget).contextWindow
                flowOf(StreamState.Done("角色测试回复"))
            }
            val worlds = mockk<SessionWorldDao>(relaxed = true)
            coEvery { worlds.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L)
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色", modelName = "test-model")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val vm = createViewModel(secureStorage = storage, sessionWorldDao = worlds, characterDao = characters,
                participantDao = participants, chatEngine = engine, llmApiService = validLlmApiService(),
                budgetManager = com.mojing.app.domain.engine.TokenBudgetManager())
            advanceUntilIdle()
            if (narrator) assertTrue(vm.requestNarrator()) else {
                vm.setManualReplyCharacterId(3L); vm.updateInput("继续"); vm.sendMessage()
            }
            platforms = listOf(saved.copy(modelContextWindows = mapOf("test-model" to 32000)))
            advanceUntilIdle()
            assertEquals(listOf(16384), capacities)
            assertFalse(vm.state.value.isGenerating)
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
        vm.refreshModelSelection()
        assertEquals("B · b-model", vm.modelSelectionLabel.value)
        assertEquals(a, route.invoke(vm))
        vm.stopGeneration()
        advanceUntilIdle()
        vm.updateInput("下一条")
        vm.sendMessage()
        assertTrue(vm.state.value.isGenerating)
        assertEquals(b, route.invoke(vm))
        vm.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun quoteChosenDuringGenerationRemainsAvailableForNextSend() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insert(any()) } coAnswers { awaitCancellation() }
        val vm = createViewModel(messageDao = messageDao, secureStorage = validSecureStorage())
        advanceUntilIdle()
        vm.updateInput("第一条")
        vm.sendMessage()
        runCurrent()
        assertTrue(vm.state.value.isGenerating)
        val quoted = MessageEntity(id = 7L, sessionId = 42L, speakerType = "narrator", content = "码头见")
        vm.handleMessageAction(com.mojing.app.ui.chat.MessageAction.Quote(quoted))
        vm.updateInput("下一条")
        vm.stopGeneration()
        advanceUntilIdle()
        assertEquals(quoted, vm.state.value.quotingMessage)
        assertEquals("下一条", vm.state.value.inputText)
        vm.sendMessage()
        runCurrent()
        coVerify(exactly = 1) { messageDao.insert(match { it.content == "> 旁白：码头见\n\n下一条" }) }
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
    fun mediaResultsAreAcceptedOnlyForTheCurrentBranch() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.appendVoiceText("同分支语音", expectedBranchId = "main"))
        assertFalse(vm.appendVoiceText("迟到语音", expectedBranchId = "branch-late"))
        assertTrue(vm.queueLocalImageAttachment("F:/pending/current.png", expectedBranchId = "main"))
        assertFalse(vm.queueLocalImageAttachment("F:/pending/late.png", expectedBranchId = "branch-late"))
        assertEquals("同分支语音", vm.state.value.inputText)
        assertEquals(listOf("F:/pending/current.png"), vm.state.value.pendingLocalImagePaths)
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
    fun quoteDraftRestoresFromSourceAndClearPersists() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        every { draftStore.load(42L) } returns ChatDraftSnapshot(inputText = "继续", quotedMessageId = 71L)
        val dao = mockk<MessageDao>(relaxed = true)
        val source = MessageEntity(id = 71L, sessionId = 42L, speakerType = "narrator", content = "最新原文")
        coEvery { dao.getByIdInSession(71L, 42L) } returns source
        val vm = createViewModel(messageDao = dao, chatDraftStore = draftStore)
        advanceUntilIdle()
        assertEquals(source, vm.state.value.quotingMessage)
        assertEquals("最新原文", vm.state.value.quotingSnippet)
        assertEquals("继续", vm.state.value.inputText)
        vm.setQuotingMessage(null)
        verify { draftStore.save(42L, ChatDraftSnapshot(inputText = "继续")) }
        vm.setQuotingMessage(source)
        verify { draftStore.save(42L, ChatDraftSnapshot(inputText = "继续", quotedMessageId = 71L)) }
    }

    @Test
    fun longQuoteWaitsForOnePreparedSnippetWithoutLosingDraft() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        val messages = mockk<MessageDao>(relaxed = true)
        val speech = "雨夜继续。".repeat(2000)
        val source = MessageEntity(
            id = 71L, sessionId = 42L, speakerType = "character",
            content = "<SPEECH>$speech</SPEECH>",
        )
        val vm = createViewModel(messageDao = messages, chatDraftStore = draftStore)
        advanceUntilIdle()

        vm.setQuotingMessage(source)
        vm.updateInput("我继续说")
        vm.sendMessage()
        assertEquals("引用正文正在准备，请稍候再发送", vm.state.value.error)
        assertEquals("我继续说", vm.state.value.inputText)
        coVerify(exactly = 0) { messages.insert(any()) }

        advanceUntilIdle()
        val prepared = withTimeout(10_000) { vm.state.first { it.quotingSnippet != null } }
        assertEquals(speech.take(120), prepared.quotingSnippet)
        assertEquals(source, prepared.quotingMessage)
        assertEquals(null, prepared.error)
    }

    @Test
    fun replacingOrCancellingLongQuoteKeepsLatePreparationFromRestoringIt() = runTest(testDispatcher) {
        val source = MessageEntity(
            id = 71L, sessionId = 42L, speakerType = "narrator",
            content = "长篇正文。".repeat(3000),
        )
        val replacement = MessageEntity(id = 72L, sessionId = 42L, speakerType = "user", content = "新的引用")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setQuotingMessage(source)
        vm.setQuotingMessage(replacement)
        advanceUntilIdle()
        assertEquals(replacement, vm.state.value.quotingMessage)
        assertEquals("新的引用", vm.state.value.quotingSnippet)

        vm.setQuotingMessage(null)
        advanceUntilIdle()

        assertEquals(null, vm.state.value.quotingMessage)
        assertEquals(null, vm.state.value.quotingSnippet)
    }

    @Test
    fun unquotableLongMessageClearsReferenceButKeepsInput() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateInput("保留这段输入")
        vm.setQuotingMessage(MessageEntity(
            id = 71L, sessionId = 42L, speakerType = "character",
            content = " ".repeat(9000),
        ))

        advanceUntilIdle()
        val cleared = withTimeout(10_000) {
            vm.state.first { it.quotingMessage == null && it.error == UserFacingStrings.messageHasNoQuotableText() }
        }
        assertEquals(null, cleared.quotingSnippet)
        assertEquals("保留这段输入", cleared.inputText)
    }

    @Test
    fun delayedQuoteRestoreDoesNotUndoUserCancellation() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        every { draftStore.load(42L) } returns ChatDraftSnapshot(quotedMessageId = 71L)
        val dao = mockk<MessageDao>(relaxed = true)
        val gate = CompletableDeferred<MessageEntity?>()
        coEvery { dao.getByIdInSession(71L, 42L) } coAnswers { gate.await() }
        val vm = createViewModel(messageDao = dao, chatDraftStore = draftStore)
        runCurrent()
        vm.setQuotingMessage(null)
        gate.complete(null)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.quotingMessage)
        verify { draftStore.save(42L, ChatDraftSnapshot()) }
    }

    @Test
    fun unavailableQuotePreservesTextAndRemovesStaleReference() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        every { draftStore.load(42L) } returns ChatDraftSnapshot(inputText = "继续", quotedMessageId = 71L)
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getByIdInSession(71L, 42L) } returns null
        val vm = createViewModel(messageDao = dao, chatDraftStore = draftStore)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.quotingMessage)
        assertEquals("继续", vm.state.value.inputText)
        assertTrue(vm.state.value.error.orEmpty().contains("引用原文已不可用"))
        verify { draftStore.save(42L, ChatDraftSnapshot(inputText = "继续")) }
    }

    @Test
    fun committedSubmissionDoesNotRestoreAlreadySentQuote() = runTest(testDispatcher) {
        val draftStore = emptyDraftStore()
        every { draftStore.load(42L) } returns ChatDraftSnapshot(inputText = "已发送", quotedMessageId = 71L, pendingSubmissionId = "sent")
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.countDraftSubmission(any(), any()) } returns 1
        val vm = createViewModel(messageDao = dao, chatDraftStore = draftStore)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.quotingMessage)
        assertEquals("", vm.state.value.inputText)
        verify { draftStore.save(42L, ChatDraftSnapshot()) }
        coVerify(exactly = 0) { dao.getByIdInSession(71L, 42L) }
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
            verify(exactly = 0) { chatEngine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
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
            messageDao.getEffectiveSwipeSelectionsForGroups(42L, "main", listOf("reply-export"))
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
    fun longStreamsCompleteAndInterruptedStreamsKeepLatestBodyForBothSpeakers() = runTest(testDispatcher) {
        for (narrator in listOf(false, true)) for (interrupted in listOf(false, true)) {
            val messages = mockk<MessageDao>(relaxed = true)
            val stored = mutableListOf<MessageEntity>()
            coEvery { messages.insert(any()) } answers {
                val message = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
                stored.add(message)
                message.id
            }
            coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L)
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val storage = validSecureStorage()
            every { storage.speakerTurnMode } returns "manual"
            val engine = mockk<ChatEngine>(relaxed = true)
            val stream = kotlinx.coroutines.flow.flow<StreamState> {
                emit(StreamState.Generating("第一段"))
                kotlinx.coroutines.delay(70_000)
                emit(StreamState.Generating("第一段第二段"))
                kotlinx.coroutines.delay(70_000)
                // Immediate final update must survive UI throttling too.
                emit(StreamState.Generating("第一段第二段尾字"))
                emit(if (interrupted) StreamState.Error("connection reset") else StreamState.Done("第一段第二段尾字"))
            }
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stream
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stream
            val vm = createViewModel(messageDao = messages, sessionWorldDao = world, characterDao = characters,
                participantDao = participants, secureStorage = storage, chatEngine = engine, llmApiService = validLlmApiService())
            advanceUntilIdle()
            if (narrator) assertTrue(vm.requestNarrator()) else {
                vm.setManualReplyCharacterId(3L)
                vm.updateInput("继续")
                vm.sendMessage()
            }
            advanceUntilIdle()
            val replies = stored.filter { it.speakerType == if (narrator) "narrator" else "character" }
            assertEquals("narrator=$narrator interrupted=$interrupted", 1, replies.size)
            assertEquals("第一段第二段尾字", replies.single().content)
            assertEquals("main", replies.single().branchId)
            val metadata = com.google.gson.JsonParser.parseString(replies.single().structuredContentJson).asJsonObject
            assertEquals(!interrupted, metadata.has("generation_duration_ms"))
            if (!interrupted) assertTrue(metadata.get("generation_duration_ms").asLong > 0)
            assertFalse(vm.state.value.isGenerating)
            if (interrupted) assertTrue(vm.state.value.error.orEmpty().contains("已保留"))
            else assertEquals(null, vm.state.value.error)
        }
    }

    @Test
    fun stoppingAfterPartialBodyRetainsLatestReceivedTextForBothSpeakers() = runTest(testDispatcher) {
        for (narrator in listOf(false, true)) {
            val messages = mockk<MessageDao>(relaxed = true)
            val stored = mutableListOf<MessageEntity>()
            coEvery { messages.insert(any()) } answers {
                val message = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
                stored.add(message)
                message.id
            }
            coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L)
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val storage = validSecureStorage()
            every { storage.speakerTurnMode } returns "manual"
            val engine = mockk<ChatEngine>(relaxed = true)
            val stream = kotlinx.coroutines.flow.flow<StreamState> {
                emit(StreamState.Generating("最新正文尾字"))
                kotlinx.coroutines.awaitCancellation()
            }
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stream
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stream
            val vm = createViewModel(messageDao = messages, sessionWorldDao = world, characterDao = characters,
                participantDao = participants, secureStorage = storage, chatEngine = engine, llmApiService = validLlmApiService())
            advanceUntilIdle()

            if (narrator) assertTrue(vm.requestNarrator()) else {
                vm.setManualReplyCharacterId(3L)
                vm.updateInput("继续")
                vm.sendMessage()
            }
            runCurrent()
            assertTrue(vm.state.value.isGenerating)

            vm.stopGeneration()
            advanceUntilIdle()

            val reply = stored.last { it.speakerType == if (narrator) "narrator" else "character" }
            assertEquals("最新正文尾字", reply.content)
            assertEquals("main", reply.branchId)
            assertFalse(vm.state.value.isGenerating)
        }
    }

    @Test
    fun stoppingNovelChapterSavesAndRefreshesLatestDraft() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val stored = mutableListOf<MessageEntity>()
        coEvery { messages.insert(any()) } answers {
            val message = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
            stored.add(message)
            message.id
        }
        coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            gameplayMode = "小说创作",
        )
        val engine = mockk<ChatEngine>(relaxed = true)
        every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            kotlinx.coroutines.flow.flow {
                emit(StreamState.Generating("章节最新正文"))
                kotlinx.coroutines.awaitCancellation()
            }
        val vm = createViewModel(
            messageDao = messages,
            sessionWorldDao = world,
            secureStorage = validSecureStorage(),
            chatEngine = engine,
            llmApiService = validLlmApiService(),
        )
        advanceUntilIdle()

        assertTrue(vm.requestNarrator(nextChapter = true, chapterTitle = "第一章"))
        runCurrent()
        vm.stopGeneration()
        advanceUntilIdle()

        val draft = stored.single { it.speakerType == "narrator" }
        assertEquals("章节最新正文", draft.content)
        assertTrue(NovelChapter.incomplete(draft.structuredContentJson))
        assertEquals("章节最新正文", vm.state.value.messages.single { it.speakerType == "narrator" }.content)
        coVerify(exactly = 1) { messages.insert(match { it.speakerType == "narrator" && it.content == "章节最新正文" }) }
    }

    @Test
    fun narratorAndNovelReadBoundedCurrentBranchSummariesIntoPrompt() = runTest(testDispatcher) {
        for (mode in listOf("自由对话", "小说创作")) {
            val worlds = mockk<SessionWorldDao>(relaxed = true)
            val segments = mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed = true)
            val engine = mockk<ChatEngine>(relaxed = true)
            coEvery { worlds.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L,
                narratorName = "讲述者", gameplayMode = mode)
            coEvery { segments.getRecentForBranch(42L, "main", 6) } returns listOf(
                com.mojing.app.data.local.entity.SessionMemorySegmentEntity(sessionId = 42L, summary = "北塔约定已保存"))
            val contexts = mockk<ContextBuilder>(relaxed = true)
            val documents = mutableListOf<com.mojing.app.domain.engine.PromptDocument>()
            val prompts = mutableListOf<String>()
            every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                prompts += arg<CharacterEntity>(1).personaPrompt
                documents += arg<com.mojing.app.domain.engine.PromptDocument>(12)
                flowOf(StreamState.Done("新的正文"))
            }
            val vm = createViewModel(sessionWorldDao = worlds, memorySegmentDao = segments,
                secureStorage = validSecureStorage(), llmApiService = validLlmApiService(), chatEngine = engine,
                promptBuilder = com.mojing.app.domain.engine.PromptBuilder(), contextBuilder = contexts)
            advanceUntilIdle()
            assertTrue(vm.requestNarrator(nextChapter = mode == "小说创作"))
            advanceUntilIdle()
            assertEquals(1, prompts.size)
            assertEquals(prompts.single(), documents.single().render())
            assertTrue(documents.single().blocks.all { it.kind == com.mojing.app.domain.engine.PromptBlock.Kind.PROTECTED })
            coVerify(exactly = 0) { contexts.areAutomaticSummaries(any()) }
            assertTrue(prompts.single().contains("- 北塔约定已保存"))
            if (mode == "小说创作") {
                assertTrue(prompts.single().contains("不生成选项或大纲"))
                assertFalse(prompts.single().contains("<CHOICES>"))
            } else {
                assertTrue(prompts.single().contains("<CHOICES>"))
            }
            coVerify { segments.getRecentForBranch(42L, "main", 6) }
        }
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
            chatEngine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
    fun manualImageFailureRetriesOriginalPromptWithCurrentKeyAndPreservesNewDraft() = runTest(testDispatcher) {
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        val storage = validSecureStorage()
        val prompts = mutableListOf<String>()
        val keys = mutableListOf<String>()
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            prompts += firstArg<String>(); keys += arg<String>(1)
            if (prompts.size == 1) Result.failure(IllegalStateException("offline")) else Result.success("mock-image")
        }
        coEvery { images.saveGeneratedImageForSession(any(), 42L) } returns "/mock/retry-image.png"
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.insert(any()) } returns 91L
        val attachments = mockk<AttachmentDao>(relaxed = true)
        val vm = createViewModel(messageDao = messages, attachmentDao = attachments, imageRepository = images, secureStorage = storage)
        advanceUntilIdle()
        vm.updateInput("独立消息草稿")
        vm.updateImagePrompt("原始雨夜画面")
        assertTrue(vm.generateAndAttachUserMessage("原始雨夜画面"))
        advanceUntilIdle()
        val notice = requireNotNull(vm.state.value.imageRetryNotice)
        assertEquals(null, vm.state.value.error)
        vm.updateImagePrompt("后来编辑的画面")
        every { storage.publicApiKey } returns "changed-test-key"
        assertTrue(vm.retryFailedImage(notice.token))
        assertFalse(vm.retryFailedImage(notice.token))
        advanceUntilIdle()
        assertEquals(listOf("原始雨夜画面", "原始雨夜画面"), prompts)
        assertEquals(listOf("sk-test", "changed-test-key"), keys)
        assertEquals("后来编辑的画面", vm.state.value.imagePrompt)
        assertEquals("独立消息草稿", vm.state.value.inputText)
        assertEquals(null, vm.state.value.imageRetryNotice)
        coVerify(exactly = 1) { messages.insert(match { it.content.contains("原始雨夜画面") }) }
        coVerify(exactly = 1) { attachments.insert(match { it.messageId == 91L && it.generationPrompt == "原始雨夜画面" }) }
    }

    @Test
    fun manualImageDiskFailureRetriesAndOldDismissCannotClearFreshFailure() = runTest(testDispatcher) {
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success("mock-image")
        coEvery { images.saveGeneratedImageForSession(any(), 42L) } returns null
        val messages = mockk<MessageDao>(relaxed = true)
        val vm = createViewModel(messageDao = messages, imageRepository = images, secureStorage = validSecureStorage())
        advanceUntilIdle()
        vm.updateImagePrompt("失败的画面")
        vm.generateAndAttachUserMessage("失败的画面"); advanceUntilIdle()
        val old = requireNotNull(vm.state.value.imageRetryNotice)
        assertTrue(vm.retryFailedImage(old.token)); advanceUntilIdle()
        val fresh = requireNotNull(vm.state.value.imageRetryNotice)
        assertFalse(old.token == fresh.token)
        vm.dismissImageRetry(old.token)
        assertEquals(fresh, vm.state.value.imageRetryNotice)
        assertFalse(vm.retryFailedImage(old.token))
        coEvery { images.saveGeneratedImageForSession(any(), 42L) } returns "/mock/image.png"
        assertTrue(vm.retryFailedImage(fresh.token)); advanceUntilIdle()
        assertEquals(null, vm.state.value.imageRetryNotice)
        assertEquals("", vm.state.value.imagePrompt)
        coVerify(exactly = 1) { messages.insert(any()) }
    }

    @Test
    fun manualImageStopAndDismissInvalidateRetryWithoutChangingDraft() = runTest(testDispatcher) {
        for (stop in listOf(true, false)) {
            val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
            coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("offline"))
            val vm = createViewModel(imageRepository = images, secureStorage = validSecureStorage())
            advanceUntilIdle()
            vm.updateImagePrompt("待保留描述")
            vm.generateAndAttachUserMessage("待保留描述"); advanceUntilIdle()
            val notice = requireNotNull(vm.state.value.imageRetryNotice)
            if (stop) vm.stopGeneration() else vm.dismissImageRetry(notice.token)
            assertFalse(vm.retryFailedImage(notice.token))
            assertEquals(null, vm.state.value.imageRetryNotice)
            assertEquals("待保留描述", vm.state.value.imagePrompt)
            coVerify(exactly = 1) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun manualImageCancellationAndNewRequestRejectOldFailure() = runTest(testDispatcher) {
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        val gate = CompletableDeferred<Result<String>>()
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("offline"))
        val vm = createViewModel(imageRepository = images, secureStorage = validSecureStorage())
        advanceUntilIdle()
        vm.updateImagePrompt("旧描述")
        vm.generateAndAttachUserMessage("旧描述"); advanceUntilIdle()
        val old = requireNotNull(vm.state.value.imageRetryNotice)
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { withContext(NonCancellable) { gate.await() } }
        vm.updateImagePrompt("新描述")
        assertTrue(vm.generateAndAttachUserMessage("新描述")); runCurrent()
        assertEquals(null, vm.state.value.imageRetryNotice)
        vm.dismissImageRetry(old.token)
        assertFalse(vm.retryFailedImage(old.token))
        vm.stopGeneration()
        gate.complete(Result.failure(IllegalStateException("late failure"))); advanceUntilIdle()
        assertEquals(null, vm.state.value.imageRetryNotice)
        assertEquals("新描述", vm.state.value.imagePrompt)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun manualImageRetryRejectsChangedCharacterAndWorldTargetsBeforeProvider() = runTest(testDispatcher) {
        for (change in listOf("character", "world", "encyclopedia")) {
            val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
            coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("offline"))
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns emptyList()
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(99L) } returns CharacterEntity(id = 99L, name = "新角色")
            val worlds = mockk<SessionWorldDao>(relaxed = true)
            val originalWorld = SessionWorldEntity(id = 8L, sessionId = 42L, encyclopediaId = 12L)
            coEvery { worlds.getBySession(42L) } returns originalWorld
            val vm = createViewModel(imageRepository = images, participantDao = participants, characterDao = characters, sessionWorldDao = worlds, secureStorage = validSecureStorage())
            advanceUntilIdle()
            vm.generateAndAttachUserMessage("原始画面"); advanceUntilIdle()
            val notice = requireNotNull(vm.state.value.imageRetryNotice)
            when (change) {
                "character" -> coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 99L))
                "world" -> coEvery { worlds.getBySession(42L) } returns originalWorld.copy(id = 9L)
                else -> coEvery { worlds.getBySession(42L) } returns originalWorld.copy(encyclopediaId = 13L)
            }
            assertTrue(vm.retryFailedImage(notice.token)); advanceUntilIdle()
            assertEquals("配图对象已改变，请重新生成", vm.state.value.error)
            assertEquals(null, vm.state.value.imageRetryNotice)
            coVerify(exactly = 1) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun manualImageBranchTransitionInvalidatesOldRetry() = runTest(testDispatcher) {
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("offline"))
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42L) } returns listOf(SessionBranchEntity(sessionId = 42L, branchId = "new-branch", sourceMessageId = 1L))
        val vm = createViewModel(imageRepository = images, sessionBranchDao = branches, secureStorage = validSecureStorage())
        advanceUntilIdle()
        vm.generateAndAttachUserMessage("原画面"); advanceUntilIdle()
        val notice = requireNotNull(vm.state.value.imageRetryNotice)
        vm.switchBranch("new-branch"); advanceUntilIdle()
        assertEquals(null, vm.state.value.imageRetryNotice)
        assertFalse(vm.retryFailedImage(notice.token))
        coVerify(exactly = 1) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun savedImageReadFailureOffersLocalRecoveryWithoutGeneratingAgain() = runTest(testDispatcher) {
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success("mock-image")
        coEvery { images.saveGeneratedImageForSession(any(), 42L) } returns "/mock/image.png"
        val messages = mockk<MessageDao>(relaxed = true)
        var saved: MessageEntity? = null
        coEvery { messages.insert(any()) } answers { saved = firstArg<MessageEntity>().copy(id = 99); 99L }
        coEvery { messages.getMainMessagesTail(42L, 81) } answers {
            if (saved != null) throw IllegalStateException("read failed after commit")
            emptyList()
        }
        val vm = createViewModel(messageDao = messages, imageRepository = images, secureStorage = validSecureStorage())
        advanceUntilIdle()
        vm.updateImagePrompt("雨夜街景")
        vm.generateAndAttachUserMessage("雨夜街景")
        advanceUntilIdle()
        assertEquals("", vm.state.value.imagePrompt)
        assertEquals(null, vm.state.value.error)
        assertEquals(99L, vm.state.value.savedImageNotice?.messageId)
        assertFalse(vm.state.value.isGenerating)
        val gate = CompletableDeferred<MessageEntity?>()
        coEvery { messages.getMainMessageById(42L, 99L) } coAnswers { gate.await() }
        assertTrue(vm.showSavedImage())
        runCurrent()
        assertFalse(vm.showSavedImage())
        gate.completeExceptionally(IllegalStateException("still unavailable"))
        advanceUntilIdle()
        assertEquals(99L, vm.state.value.savedImageNotice?.messageId)
        coEvery { messages.getMainMessageById(42L, 99L) } answers { saved }
        assertTrue(vm.showSavedImage())
        advanceUntilIdle()
        assertEquals(null, vm.state.value.savedImageNotice)
        assertEquals(null, vm.state.value.error)
        assertEquals(99L, vm.state.value.focusedMessageId)
        assertEquals(listOf(saved), vm.state.value.messages)
        coVerify(exactly = 1) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { messages.insert(any()) }
    }

    @Test
    fun initialAutoImageCancellationClosesInsertedLeaseAndActiveRefreshDoesNotRecoverIt() = runTest(testDispatcher) {
        for (stopDuringInsert in listOf(true, false)) {
            val messages = mockk<MessageDao>(relaxed = true)
            val stored = mutableListOf<MessageEntity>()
            val imageInserted = CompletableDeferred<MessageEntity>()
            val insertRelease = CompletableDeferred<Unit>()
            val refreshEntered = CompletableDeferred<Unit>()
            val refreshRelease = CompletableDeferred<Unit>()
            var pausedRefresh = false
            coEvery { messages.insert(any()) } coAnswers {
                val message = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
                stored.add(message)
                if (AutoImageMetadata.parse(message.structuredContentJson)?.state == AutoImageMetadata.STATE_RUNNING) {
                    imageInserted.complete(message)
                    if (stopDuringInsert) withContext(NonCancellable) { insertRelease.await() }
                }
                message.id
            }
            coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
            val branches = mockk<SessionBranchDao>(relaxed = true)
            coEvery { branches.getBySession(42L) } coAnswers {
                if (!stopDuringInsert && imageInserted.isCompleted && !pausedRefresh) {
                    pausedRefresh = true
                    refreshEntered.complete(Unit)
                    refreshRelease.await()
                }
                emptyList()
            }
            val failures = mutableListOf<Pair<Long, String>>()
            coEvery { messages.failAutoImageGeneration(any(), 42L, "main", any(), interrupted = true) } answers {
                val id = firstArg<Long>()
                val token = args[3] as String
                val row = stored.first { it.id == id }
                assertEquals(AutoImageMetadata.STATE_RUNNING, AutoImageMetadata.parse(row.structuredContentJson)?.state)
                assertEquals(token, AutoImageMetadata.parse(row.structuredContentJson)?.attemptToken)
                failures.add(id to token)
                stored[stored.indexOf(row)] = row.copy(
                    content = "🖼 配图生成中断，可重试",
                    structuredContentJson = AutoImageMetadata.update(row.structuredContentJson, AutoImageMetadata.STATE_INTERRUPTED),
                )
                true
            }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, autoCharacterImageGen = true, sessionImageApiKey = "local-test-key")
            val characters = mockk<CharacterDao>(relaxed = true)
            coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
            val participants = mockk<ParticipantDao>(relaxed = true)
            coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
            val storage = validSecureStorage()
            every { storage.speakerTurnMode } returns "manual"
            val engine = mockk<ChatEngine>(relaxed = true)
            every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                flowOf(StreamState.Done("潮声还在窗外。\n<GEN_IMAGE>原始画面提示</GEN_IMAGE>"))
            val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
            val vm = createViewModel(messageDao = messages, sessionBranchDao = branches, sessionWorldDao = world,
                characterDao = characters, participantDao = participants, secureStorage = storage,
                chatEngine = engine, imageRepository = images, llmApiService = validLlmApiService())
            advanceUntilIdle()
            vm.setManualReplyCharacterId(3L)
            vm.updateInput("继续")
            vm.sendMessage()
            runCurrent()
            assertTrue("auto image reached insert: ${vm.state.value.error}", imageInserted.isCompleted)
            if (!stopDuringInsert) assertTrue("auto image reached refresh", refreshEntered.isCompleted)
            val inserted = imageInserted.await()
            val token = requireNotNull(AutoImageMetadata.parse(inserted.structuredContentJson)?.attemptToken)
            assertEquals("原始画面提示", AutoImageMetadata.parse(inserted.structuredContentJson)?.prompt)
            assertEquals("潮声还在窗外。", stored.first { it.id == inserted.parentMessageId }.content)

            vm.retryInitialization()
            runCurrent()
            coVerify(exactly = 0) { messages.markAutoImageRunningInterrupted(any(), any(), any()) }
            assertEquals(AutoImageMetadata.STATE_RUNNING, AutoImageMetadata.parse(stored.first { it.id == inserted.id }.structuredContentJson)?.state)
            vm.stopGeneration()
            insertRelease.complete(Unit)
            refreshRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(inserted.id to token), failures)
            assertEquals(AutoImageMetadata.STATE_INTERRUPTED, AutoImageMetadata.parse(stored.first { it.id == inserted.id }.structuredContentJson)?.state)
            coVerify(exactly = 0) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { messages.completeAutoImageGeneration(any(), any(), any(), any(), any()) }
            assertFalse(vm.state.value.isGenerating)
        }
    }

    @Test
    fun autoImageRetryClaimsOnceAndRetainsFailurePrompt() = runTest(testDispatcher) {
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IllegalStateException("offline"))
        val messages = mockk<MessageDao>(relaxed = true)
        val failed = MessageEntity(
            id = 77L,
            sessionId = 42L,
            speakerType = "character",
            characterId = 3L,
            branchId = "main",
            parentMessageId = 11L,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create(
                "保留原始配图提示", AutoImageMetadata.STATE_FAILED, "old-attempt",
            ),
            includeInContext = false,
        )
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(failed)
        coEvery { messages.getById(77L) } returns failed
        coEvery { messages.claimAutoImageGeneration(77L, 42L, "main", "old-attempt", any()) } returns true
        coEvery { messages.failAutoImageGeneration(77L, 42L, "main", any(), interrupted = false) } returns true
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(
            sessionId = 42L,
            sessionImageApiKey = "test-key",
            sessionImageBaseUrl = "http://127.0.0.1",
            sessionImageModel = "test-image",
        )
        val vm = createViewModel(
            messageDao = messages,
            characterDao = characters,
            sessionWorldDao = world,
            imageRepository = images,
            secureStorage = validSecureStorage(apiKey = ""),
        )
        advanceUntilIdle()

        assertTrue(vm.retryAutoCharacterImage(77L))
        assertFalse(vm.retryAutoCharacterImage(77L))
        advanceUntilIdle()

        coVerify(exactly = 1) { messages.claimAutoImageGeneration(77L, 42L, "main", "old-attempt", any()) }
        coVerify(exactly = 1) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { messages.failAutoImageGeneration(77L, 42L, "main", any(), interrupted = false) }
        assertEquals("保留原始配图提示", AutoImageMetadata.parse(failed.structuredContentJson)?.prompt)
    }

    @Test
    fun coldStartRunningAutoImageIsInterruptedWithoutRequestingVendor() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val running = MessageEntity(
            id = 78L,
            sessionId = 42L,
            speakerType = "character",
            characterId = 3L,
            branchId = "main",
            parentMessageId = 11L,
            content = "🖼 配图生成中…",
            structuredContentJson = AutoImageMetadata.create(
                "冷启动恢复提示", AutoImageMetadata.STATE_RUNNING, "running-token",
            ),
            includeInContext = false,
        )
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(running)
        coEvery { messages.markAutoImageRunningInterrupted(78L, 42L, "main") } returns true
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        val vm = createViewModel(messageDao = messages, imageRepository = images)
        advanceUntilIdle()

        assertEquals("🖼 配图生成中断，可重试", vm.state.value.messages.single().content)
        coVerify(exactly = 1) { messages.markAutoImageRunningInterrupted(78L, 42L, "main") }
        coVerify(exactly = 0) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun claimedAutoImageRefreshFailureClosesTheSameAttempt() = runTest(testDispatcher) {
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        val messages = mockk<MessageDao>(relaxed = true)
        val failed = MessageEntity(
            id = 79L,
            sessionId = 42L,
            speakerType = "character",
            characterId = 3L,
            branchId = "main",
            parentMessageId = 11L,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create(
                "refresh失败仍保留提示", AutoImageMetadata.STATE_FAILED, "old-refresh-token",
            ),
            includeInContext = false,
        )
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(failed)
        coEvery { messages.getById(79L) } returns failed
        coEvery { messages.claimAutoImageGeneration(79L, 42L, "main", "old-refresh-token", any()) } returns true
        coEvery { messages.failAutoImageGeneration(79L, 42L, "main", any(), interrupted = false) } returns true
        val world = mockk<SessionWorldDao>(relaxed = true)
        val configuredWorld = SessionWorldEntity(sessionId = 42L, sessionImageApiKey = "test-key")
        var worldReads = 0
        coEvery { world.getBySession(42L) } coAnswers {
            worldReads++
            if (worldReads == 1) configuredWorld else error("refresh stopped")
        }
        val vm = createViewModel(
            messageDao = messages,
            sessionWorldDao = world,
            imageRepository = images,
            secureStorage = validSecureStorage(),
        )
        advanceUntilIdle()

        assertTrue(vm.retryAutoCharacterImage(79L))
        advanceUntilIdle()

        coVerify(exactly = 1) { messages.claimAutoImageGeneration(79L, 42L, "main", "old-refresh-token", any()) }
        coVerify(exactly = 1) { messages.failAutoImageGeneration(79L, 42L, "main", any(), interrupted = false) }
        coVerify(exactly = 0) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun autoImageClaimThenRefreshCancellationClosesClaimedLease() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val failed = MessageEntity(
            id = 80L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create("取消窗口", AutoImageMetadata.STATE_FAILED, "old-token"),
            includeInContext = false,
        )
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(failed)
        coEvery { messages.getById(80L) } returns failed
        coEvery { messages.claimAutoImageGeneration(80L, 42L, "main", "old-token", any()) } returns true
        coEvery { messages.failAutoImageGeneration(80L, 42L, "main", any(), interrupted = true) } returns true
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val refreshGate = CompletableDeferred<List<SessionBranchEntity>>()
        var branchReads = 0
        coEvery { branches.getBySession(42L) } coAnswers {
            branchReads++
            if (branchReads == 1) emptyList() else refreshGate.await()
        }
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, sessionImageApiKey = "test-key")
        val vm = createViewModel(messageDao = messages, sessionBranchDao = branches, sessionWorldDao = world)
        advanceUntilIdle()

        assertTrue(vm.retryAutoCharacterImage(80L))
        runCurrent()
        vm.stopGeneration()
        refreshGate.complete(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 1) { messages.claimAutoImageGeneration(80L, 42L, "main", "old-token", any()) }
        coVerify(exactly = 1) { messages.failAutoImageGeneration(80L, 42L, "main", any(), interrupted = true) }
        coVerify(exactly = 0) { messages.completeAutoImageGeneration(any(), any(), any(), any(), any()) }
    }

    @Test
    fun autoImageSaveCancellationPreventsCompleteAndDeletesUncommittedPath() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val failed = MessageEntity(
            id = 81L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create("保存取消", AutoImageMetadata.STATE_FAILED, "old-token"),
            includeInContext = false,
        )
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(failed)
        coEvery { messages.getById(81L) } returns failed
        coEvery { messages.claimAutoImageGeneration(81L, 42L, "main", "old-token", any()) } returns true
        coEvery { messages.failAutoImageGeneration(81L, 42L, "main", any(), interrupted = true) } returns true
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success("vendor-image")
        val path = java.io.File.createTempFile("mojing-auto-image", ".png")
        path.delete()
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CompletableDeferred<Unit>()
        coEvery { images.saveGeneratedImageForSession(any(), 42L) } coAnswers {
            saveEntered.complete(Unit)
            withContext(NonCancellable) {
                saveRelease.await()
                path.writeText("uncommitted")
                check(path.exists())
            }
            path.absolutePath
        }
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, sessionImageApiKey = "test-key")
        val vm = createViewModel(messageDao = messages, characterDao = characters, sessionWorldDao = world, imageRepository = images, secureStorage = validSecureStorage())
        try {
            advanceUntilIdle()
            assertTrue(vm.retryAutoCharacterImage(81L))
            runCurrent()
            saveEntered.await()
            vm.stopGeneration()
            saveRelease.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 0) { messages.completeAutoImageGeneration(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { messages.failAutoImageGeneration(81L, 42L, "main", any(), interrupted = true) }
            assertFalse(path.exists())
        } finally {
            path.delete()
        }
    }

    @Test
    fun autoImageCompleteCancellationKeepsCommittedPathAndDoesNotRewriteIt() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val failed = MessageEntity(
            id = 82L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create("提交取消", AutoImageMetadata.STATE_FAILED, "old-token"),
            includeInContext = false,
        )
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(failed)
        coEvery { messages.getById(82L) } returns failed
        coEvery { messages.claimAutoImageGeneration(82L, 42L, "main", "old-token", any()) } returns true
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success("vendor-image")
        val path = java.io.File.createTempFile("mojing-auto-image-committed", ".png")
        path.writeText("owned")
        coEvery { images.saveGeneratedImageForSession(any(), 42L) } returns path.absolutePath
        val completeEntered = CompletableDeferred<Unit>()
        val completeRelease = CompletableDeferred<Unit>()
        var committedAttachment: MessageAttachmentEntity? = null
        var committedToken: String? = null
        coEvery { messages.completeAutoImageGeneration(82L, 42L, "main", any(), any()) } coAnswers {
            committedToken = args[3] as String
            committedAttachment = args[4] as MessageAttachmentEntity
            completeEntered.complete(Unit)
            withContext(NonCancellable) { completeRelease.await() }
            true
        }
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, sessionImageApiKey = "test-key")
        val vm = createViewModel(messageDao = messages, characterDao = characters, sessionWorldDao = world, imageRepository = images, secureStorage = validSecureStorage())
        try {
            advanceUntilIdle()
            assertTrue(vm.retryAutoCharacterImage(82L))
            runCurrent()
            completeEntered.await()
            vm.stopGeneration()
            completeRelease.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { messages.completeAutoImageGeneration(82L, 42L, "main", any(), any()) }
            assertEquals(path.absolutePath, committedAttachment?.storagePath)
            assertEquals("提交取消", committedAttachment?.generationPrompt)
            assertEquals(true, committedAttachment?.generationModel?.isNotBlank())
            assertTrue(committedToken?.isNotBlank() == true && committedToken != "old-token")
            assertTrue(path.exists())
        } finally {
            path.delete()
        }
    }

    @Test
    fun autoImageClaimCancellationAfterDurableSuccessClosesLeaseBeforeVendor() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val failed = MessageEntity(
            id = 83L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create("claim取消", AutoImageMetadata.STATE_FAILED, "old-token"),
            includeInContext = false,
        )
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(failed)
        coEvery { messages.getById(83L) } returns failed
        val claimEntered = CompletableDeferred<Unit>()
        val claimRelease = CompletableDeferred<Unit>()
        coEvery { messages.claimAutoImageGeneration(83L, 42L, "main", "old-token", any()) } coAnswers {
            claimEntered.complete(Unit)
            withContext(NonCancellable) { claimRelease.await() }
            true
        }
        coEvery { messages.failAutoImageGeneration(83L, 42L, "main", any(), interrupted = true) } returns true
        val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, sessionImageApiKey = "test-key")
        val vm = createViewModel(messageDao = messages, sessionWorldDao = world, imageRepository = images)
        advanceUntilIdle()

        assertTrue(vm.retryAutoCharacterImage(83L))
        runCurrent()
        claimEntered.await()
        vm.stopGeneration()
        claimRelease.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { messages.failAutoImageGeneration(83L, 42L, "main", any(), interrupted = true) }
        coVerify(exactly = 0) { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { messages.completeAutoImageGeneration(any(), any(), any(), any(), any()) }
    }

    @Test
    fun initialAutoVoiceProducerStripsTagKeepsFullTextAndCommitsMultipleFilesWithoutSpeak() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val stored = mutableListOf<MessageEntity>()
        var inserted: MessageEntity? = null
        var committed: List<MessageAttachmentEntity> = emptyList()
        coEvery { messages.insert(any()) } coAnswers {
            val row = firstArg<MessageEntity>().copy(id = stored.size.toLong() + 1)
            stored += row
            if (AutoVoiceMetadata.parse(row.structuredContentJson)?.state == AutoVoiceMetadata.STATE_RUNNING) inserted = row
            row.id
        }
        coEvery { messages.getMainMessagesTail(42L, any()) } answers { stored.reversed() }
        coEvery { messages.completeAutoVoiceGeneration(any(), 42L, "main", any(), any()) } coAnswers {
            committed = args[4] as List<MessageAttachmentEntity>
            true
        }
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42L) } returns emptyList()
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L, autoCharacterSpeech = true)
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色", voiceProvider = "system", voiceModel = "voice-a")
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 3L))
        val engine = mockk<ChatEngine>(relaxed = true)
        val text = "标题\n" + "完整语音正文".repeat(300)
        every { engine.streamGenerateWithMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf(StreamState.Done("角色回复\n<GEN_SPEECH>$text</GEN_SPEECH>"))
        val filesDir = createTempDirectory("mojing-auto-voice-producer").toFile()
        val context = speechTestContext()
        every { context.filesDir } returns filesDir
        mockkObject(AndroidTts)
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) } coAnswers {
                val directory = arg<File>(3)
                val first = directory.resolve("segment-0.wav").also { it.writeText("one") }
                val second = directory.resolve("segment-1.wav").also { it.writeText("two") }
                listOf(SynthesizedSpeechFile(first, "audio/wav"), SynthesizedSpeechFile(second, "audio/wav"))
            }
            val vm = createViewModel(
                messageDao = messages, sessionBranchDao = branches, sessionWorldDao = world,
                characterDao = characters, participantDao = participants, chatEngine = engine,
                appContext = context, secureStorage = validSecureStorage(),
                llmApiService = validLlmApiService(),
            )
            advanceUntilIdle()
            vm.setManualReplyCharacterId(3L)
            vm.updateInput("继续")
            vm.sendMessage()
            advanceUntilIdle()
            vm.state.first { !it.isGenerating }

            val row = requireNotNull(inserted)
            val metadata = requireNotNull(AutoVoiceMetadata.parse(row.structuredContentJson))
            assertEquals(text, metadata.text)
            assertEquals("main", row.branchId)
            assertEquals(2, committed.size)
            assertTrue(committed.all { it.assetType == "voice" && it.generationPrompt == text })
            coVerify(exactly = 1) { AndroidTts.synthesizeToFiles(any(), text, any(), any(), any()) }
            coVerify(exactly = 0) { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
            check(filesDir.canonicalFile.name.startsWith("mojing-auto-voice-producer"))
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun autoVoiceRetryClaimsOnceKeepsFullTextAndUsesCharacterVoice() = runTest(testDispatcher) {
        val text = "章节标题\n" + "完整旁白".repeat(2_000)
        val failed = MessageEntity(
            id = 177L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L, includeInContext = false,
            content = "🔊 配音生成失败，可重试",
            structuredContentJson = AutoVoiceMetadata.create(text, AutoVoiceMetadata.STATE_FAILED, "old-voice-token"),
        )
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(failed)
        coEvery { messages.getById(177L) } returns failed
        coEvery { messages.claimAutoVoiceGeneration(177L, 42L, "main", "old-voice-token", any()) } returns true
        var committed: List<MessageAttachmentEntity> = emptyList()
        var committedToken: String? = null
        coEvery { messages.completeAutoVoiceGeneration(177L, 42L, "main", any(), any()) } coAnswers {
            committedToken = args[3] as String
            committed = args[4] as List<MessageAttachmentEntity>
            true
        }
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(
            id = 3L, name = "角色", voiceProvider = "system", voiceModel = "character-voice",
        )
        val filesDir = createTempDirectory("mojing-auto-voice-test").toFile()
        val context = speechTestContext()
        every { context.filesDir } returns filesDir
        mockkObject(AndroidTts)
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) } coAnswers {
                assertEquals(text, arg<String>(1))
                assertEquals("system", arg<com.mojing.app.data.VoiceChoice>(2).engineId)
                assertEquals("character-voice", arg<com.mojing.app.data.VoiceChoice>(2).voiceId)
                val generated = arg<File>(3).resolve("gen_voice_result_0.wav").also { it.writeText("audio") }
                listOf(SynthesizedSpeechFile(generated, "audio/wav"))
            }
            val vm = createViewModel(messageDao = messages, characterDao = characters, appContext = context)
            advanceUntilIdle()

            assertTrue(vm.retryAutoCharacterVoice(177L))
            assertFalse(vm.retryAutoCharacterVoice(177L))
            advanceUntilIdle()
            vm.state.first { !it.isGenerating }

            coVerify(exactly = 1) { messages.claimAutoVoiceGeneration(177L, 42L, "main", "old-voice-token", any()) }
            coVerify(exactly = 1) { AndroidTts.synthesizeToFiles(any(), text, any(), any(), any()) }
            coVerify(exactly = 1) { messages.completeAutoVoiceGeneration(177L, 42L, "main", any(), any()) }
            assertTrue(committedToken!!.isNotBlank() && committedToken != "old-voice-token")
            assertEquals(text, committed.single().generationPrompt)
            assertEquals("voice", committed.single().assetType)
        } finally {
            unmockkObject(AndroidTts)
            check(filesDir.canonicalFile.name.startsWith("mojing-auto-voice-test"))
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun autoVoiceProviderCancellationFailsClaimAndNeverCommits() = runTest(testDispatcher) {
        val failed = MessageEntity(
            id = 178L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L, includeInContext = false,
            content = "🔊 配音生成失败，可重试",
            structuredContentJson = AutoVoiceMetadata.create("取消时仍保留全文", AutoVoiceMetadata.STATE_FAILED, "old-token"),
        )
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(failed)
        coEvery { messages.getById(178L) } returns failed
        coEvery { messages.claimAutoVoiceGeneration(178L, 42L, "main", "old-token", any()) } returns true
        coEvery { messages.failAutoVoiceGeneration(178L, 42L, "main", any(), interrupted = true) } returns true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
        val filesDir = createTempDirectory("mojing-auto-voice-provider-cancel").toFile()
        val context = speechTestContext()
        every { context.filesDir } returns filesDir
        var generated: File? = null
        mockkObject(AndroidTts)
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) } coAnswers {
                generated = arg<File>(3).resolve("provider-cancel.wav").also { it.writeText("audio") }
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                listOf(SynthesizedSpeechFile(requireNotNull(generated), "audio/wav"))
            }
            val vm = createViewModel(messageDao = messages, characterDao = characters, appContext = context)
            advanceUntilIdle()
            assertTrue(vm.retryAutoCharacterVoice(178L))
            runCurrent()
            entered.await()
            vm.stopGeneration()
            release.complete(Unit)
            advanceUntilIdle()
            vm.state.first { !it.isGenerating }
            assertFalse(requireNotNull(generated).exists())

            coVerify(exactly = 1) { messages.failAutoVoiceGeneration(178L, 42L, "main", any(), interrupted = true) }
            coVerify(exactly = 0) { messages.completeAutoVoiceGeneration(any(), any(), any(), any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
            check(filesDir.canonicalFile.name.startsWith("mojing-auto-voice-provider-cancel"))
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun autoVoiceAcquireCancellationClosesClaimedLeaseBeforeSupplier() = runTest(testDispatcher) {
        val failed = MessageEntity(
            id = 180L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L, includeInContext = false,
            content = "🔊 配音生成失败，可重试",
            structuredContentJson = AutoVoiceMetadata.create("acquire取消", AutoVoiceMetadata.STATE_FAILED, "old-token"),
        )
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(failed)
        coEvery { messages.getById(180L) } returns failed
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { messages.claimAutoVoiceGeneration(180L, 42L, "main", "old-token", any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            true
        }
        coEvery { messages.failAutoVoiceGeneration(180L, 42L, "main", any(), interrupted = true) } returns true
        mockkObject(AndroidTts)
        try {
            every { AndroidTts.stop() } returns Unit
            val vm = createViewModel(messageDao = messages, appContext = speechTestContext())
            advanceUntilIdle()
            assertTrue(vm.retryAutoCharacterVoice(180L))
            runCurrent()
            entered.await()
            vm.stopGeneration()
            release.complete(Unit)
            advanceUntilIdle()
            vm.state.first { !it.isGenerating }

            coVerify(exactly = 1) { messages.failAutoVoiceGeneration(180L, 42L, "main", any(), interrupted = true) }
            coVerify(exactly = 0) { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun autoVoiceCompleteCancellationKeepsCommittedTempFile() = runTest(testDispatcher) {
        val failed = MessageEntity(
            id = 181L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L, includeInContext = false,
            content = "🔊 配音生成失败，可重试",
            structuredContentJson = AutoVoiceMetadata.create("提交取消", AutoVoiceMetadata.STATE_FAILED, "old-token"),
        )
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(failed)
        coEvery { messages.getById(181L) } returns failed
        coEvery { messages.claimAutoVoiceGeneration(181L, 42L, "main", "old-token", any()) } returns true
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
        val filesDir = createTempDirectory("mojing-auto-voice-committed").toFile()
        val path = filesDir.resolve("attachments/42/committed.wav")
        path.parentFile!!.mkdirs()
        path.writeText("owned")
        val completeEntered = CompletableDeferred<Unit>()
        val completeRelease = CompletableDeferred<Unit>()
        var committed: List<MessageAttachmentEntity> = emptyList()
        coEvery { messages.completeAutoVoiceGeneration(181L, 42L, "main", any(), any()) } coAnswers {
            committed = args[4] as List<MessageAttachmentEntity>
            completeEntered.complete(Unit)
            withContext(NonCancellable) { completeRelease.await() }
            true
        }
        mockkObject(AndroidTts)
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) } returns listOf(
                SynthesizedSpeechFile(path, "audio/wav"),
            )
            val context = speechTestContext()
            every { context.filesDir } returns filesDir
            val vm = createViewModel(messageDao = messages, characterDao = characters, appContext = context)
            advanceUntilIdle()
            assertTrue(vm.retryAutoCharacterVoice(181L))
            runCurrent()
            completeEntered.await()
            vm.stopGeneration()
            completeRelease.complete(Unit)
            advanceUntilIdle()
            vm.state.first { !it.isGenerating }

            coVerify(exactly = 1) { messages.completeAutoVoiceGeneration(181L, 42L, "main", any(), any()) }
            assertEquals(path.absolutePath, committed.single().storagePath)
            assertTrue(path.exists())
        } finally {
            unmockkObject(AndroidTts)
            check(filesDir.canonicalFile.name.startsWith("mojing-auto-voice-committed"))
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun activeAutoVoiceSurvivesDetachAndRefreshWithoutStoppingEngine() = runTest(testDispatcher) {
        val failed = MessageEntity(
            id = 183L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L, includeInContext = false,
            content = "🔊 配音生成失败，可重试",
            structuredContentJson = AutoVoiceMetadata.create("后台配音", AutoVoiceMetadata.STATE_FAILED, "old-token"),
        )
        val messages = mockk<MessageDao>(relaxed = true)
        var current = failed
        coEvery { messages.getMainMessagesTail(42L, any()) } answers { listOf(current) }
        coEvery { messages.getById(183L) } returns failed
        coEvery { messages.claimAutoVoiceGeneration(183L, 42L, "main", "old-token", any()) } coAnswers {
            val token = args[4] as String
            current = failed.copy(
                content = "配音生成中…",
                structuredContentJson = AutoVoiceMetadata.update(failed.structuredContentJson, AutoVoiceMetadata.STATE_RUNNING, token),
            )
            true
        }
        coEvery { messages.completeAutoVoiceGeneration(183L, 42L, "main", any(), any()) } returns true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val filesDir = createTempDirectory("mojing-auto-voice-detach").toFile()
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getById(3L) } returns CharacterEntity(id = 3L, name = "角色")
        mockkObject(AndroidTts)
        try {
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) } coAnswers {
                val generated = arg<File>(3).resolve("detach.wav").also { it.writeText("audio") }
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                listOf(SynthesizedSpeechFile(generated, "audio/wav"))
            }
            val context = speechTestContext()
            every { context.filesDir } returns filesDir
            val vm = createViewModel(messageDao = messages, characterDao = characters, appContext = context)
            advanceUntilIdle()
            assertTrue(vm.retryAutoCharacterVoice(183L))
            runCurrent()
            entered.await()
            vm.detachSpeechScreen()
            release.complete(Unit)
            advanceUntilIdle()
            vm.state.first { !it.isGenerating }

            coVerify(exactly = 1) { messages.completeAutoVoiceGeneration(183L, 42L, "main", any(), any()) }
            coVerify(exactly = 0) { messages.markAutoVoiceRunningInterrupted(any(), any(), any(), any()) }
            verify(exactly = 0) { AndroidTts.stop() }
        } finally {
            unmockkObject(AndroidTts)
            check(filesDir.canonicalFile.name.startsWith("mojing-auto-voice-detach"))
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun coldVoiceRecoveryDeletesOnlyUnreferencedFilesForExactUuidToken() = runTest(testDispatcher) {
        val token = "123e4567-e89b-12d3-a456-426614174180"
        val otherToken = "123e4567-e89b-12d3-a456-426614174181"
        val filesDir = createTempDirectory("mojing-voice-files").toFile()
        val voiceDir = filesDir.resolve("attachments/42").also { it.mkdirs() }
        val orphanWav = voiceDir.resolve("gen_voice_${token}_0.wav").also { it.writeText("orphan") }
        val orphanMp3 = voiceDir.resolve("gen_voice_${token}_1.mp3").also { it.writeText("orphan") }
        val referenced = voiceDir.resolve("gen_voice_${token}_2.wav").also { it.writeText("referenced") }
        val other = voiceDir.resolve("gen_voice_${otherToken}_0.wav").also { it.writeText("other") }
        val running = MessageEntity(
            id = 182L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L, includeInContext = false,
            content = "配音生成中…",
            structuredContentJson = AutoVoiceMetadata.create("恢复原文", AutoVoiceMetadata.STATE_RUNNING, token),
        )
        val messages = mockk<MessageDao>(relaxed = true)
        val attachments = mockk<AttachmentDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(running)
        coEvery { messages.markAutoVoiceRunningInterrupted(182L, 42L, "main", token) } returns true
        coEvery { attachments.countByStoragePath(any()) } returns 0
        coEvery { attachments.countByStoragePath(referenced.absolutePath) } returns 1
        val context = speechTestContext()
        every { context.filesDir } returns filesDir
        mockkObject(AndroidTts)
        try {
            val vm = createViewModel(messageDao = messages, attachmentDao = attachments, appContext = context)
            advanceUntilIdle()
            vm.state.first { it.isReady && it.messages.singleOrNull()?.content == "配音生成中断，可重试" }
            assertFalse(orphanWav.exists())
            assertFalse(orphanMp3.exists())
            assertTrue(referenced.exists())
            assertTrue(other.exists())
            coVerify(exactly = 1) { attachments.countByStoragePath(referenced.absolutePath) }
            coVerify(exactly = 0) { AndroidTts.stop() }
            assertEquals("配音生成中断，可重试", vm.state.value.messages.single().content)
        } finally {
            unmockkObject(AndroidTts)
            check(filesDir.canonicalFile.name.startsWith("mojing-voice-files"))
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun coldStartRunningAutoVoiceIsInterruptedWithoutSupplierRequest() = runTest(testDispatcher) {
        val running = MessageEntity(
            id = 179L, sessionId = 42L, speakerType = "character", characterId = 3L,
            branchId = "main", parentMessageId = 11L, includeInContext = false,
            content = "配音生成中…",
            structuredContentJson = AutoVoiceMetadata.create("冷启动原文", AutoVoiceMetadata.STATE_RUNNING, "running-token"),
        )
        val messages = mockk<MessageDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(running)
        coEvery { messages.markAutoVoiceRunningInterrupted(179L, 42L, "main", "running-token") } returns true
        mockkObject(AndroidTts)
        try {
            coEvery { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) } returns emptyList()
            val vm = createViewModel(messageDao = messages, appContext = speechTestContext())
            advanceUntilIdle()
            coVerify(exactly = 1) { messages.markAutoVoiceRunningInterrupted(179L, 42L, "main", "running-token") }
            coVerify(exactly = 0) { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) }
        } finally {
            unmockkObject(AndroidTts)
        }
    }

    @Test
    fun storedVoicePlaybackUsesOrderedAttachmentsAndKeepsFilesOwned() = runTest(testDispatcher) {
        val message = MessageEntity(id = 190L, sessionId = 42L, speakerType = "character", branchId = "main", content = "语音")
        val first = java.io.File.createTempFile("mojing-stored-voice-1", ".wav").also { it.writeText("one") }
        val second = java.io.File.createTempFile("mojing-stored-voice-2", ".wav").also { it.writeText("two") }
        val messages = mockk<MessageDao>(relaxed = true)
        val attachments = mockk<AttachmentDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(message)
        coEvery { messages.getMainMessageById(42L, 190L) } returns message
        coEvery { attachments.getByMessage(190L) } returns listOf(
            MessageAttachmentEntity(id = 1L, messageId = 190L, assetType = "voice", mimeType = "audio/wav", storagePath = first.absolutePath),
            MessageAttachmentEntity(id = 2L, messageId = 190L, assetType = "voice", mimeType = "audio/wav", storagePath = second.absolutePath),
        )
        mockkObject(TtsPlayer)
        val firstHandle = mockk<TtsPlayer.PlaybackHandle>(relaxed = true)
        val secondHandle = mockk<TtsPlayer.PlaybackHandle>(relaxed = true)
        try {
            every { TtsPlayer.playOwned(any(), deleteWhenFinished = false, initialPaused = any(), onPhaseChanged = any()) } returnsMany listOf(firstHandle, secondHandle)
            coEvery { TtsPlayer.awaitCompletion(firstHandle) } returns true
            coEvery { TtsPlayer.awaitCompletion(secondHandle) } returns true
            every { TtsPlayer.stop(any<TtsPlayer.PlaybackHandle>()) } returns Unit
            val vm = createViewModel(messageDao = messages, attachmentDao = attachments, appContext = speechTestContext())
            advanceUntilIdle()
            vm.playVoiceAttachments(190L)
            runCurrent()
            advanceUntilIdle()
            vm.speechActive.first { !it }

            verify(exactly = 1) { TtsPlayer.playOwned(first, deleteWhenFinished = false, initialPaused = any(), onPhaseChanged = any()) }
            verify(exactly = 1) { TtsPlayer.playOwned(second, deleteWhenFinished = false, initialPaused = any(), onPhaseChanged = any()) }
            coVerify(exactly = 1) { TtsPlayer.awaitCompletion(firstHandle) }
            coVerify(exactly = 1) { TtsPlayer.awaitCompletion(secondHandle) }
            assertTrue(first.exists() && second.exists())
        } finally {
            unmockkObject(TtsPlayer)
            first.delete(); second.delete()
        }
    }

    @Test
    fun storedVoicePlaybackPauseResumeAndStopUseCurrentHandleOnly() = runTest(testDispatcher) {
        val message = MessageEntity(id = 191L, sessionId = 42L, speakerType = "character", branchId = "main", content = "语音")
        val file = File.createTempFile("mojing-stored-voice-control", ".wav").also { it.writeText("audio") }
        val messages = mockk<MessageDao>(relaxed = true)
        val attachments = mockk<AttachmentDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(message)
        coEvery { messages.getMainMessageById(42L, 191L) } returns message
        coEvery { attachments.getByMessage(191L) } returns listOf(
            MessageAttachmentEntity(id = 1L, messageId = 191L, assetType = "voice", mimeType = "audio/wav", storagePath = file.absolutePath),
        )
        val handle = mockk<TtsPlayer.PlaybackHandle>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        mockkObject(TtsPlayer)
        try {
            every { TtsPlayer.playOwned(any(), deleteWhenFinished = false, initialPaused = any(), onPhaseChanged = any()) } returns handle
            coEvery { TtsPlayer.awaitCompletion(handle) } coAnswers { entered.complete(Unit); awaitCancellation() }
            every { TtsPlayer.pause(handle) } returns Unit
            every { TtsPlayer.resume(handle) } returns Unit
            every { TtsPlayer.stop(handle) } returns Unit
            val vm = createViewModel(messageDao = messages, attachmentDao = attachments, appContext = speechTestContext())
            advanceUntilIdle()
            vm.playVoiceAttachments(191L)
            runCurrent()
            entered.await()
            vm.pauseSpeaking(); runCurrent()
            vm.resumeSpeaking(); runCurrent()
            vm.stopSpeaking(); advanceUntilIdle()

            verify(exactly = 1) { TtsPlayer.pause(handle) }
            verify(exactly = 1) { TtsPlayer.resume(handle) }
            verify(exactly = 2) { TtsPlayer.stop(handle) }
            assertTrue(file.exists())
        } finally {
            unmockkObject(TtsPlayer)
            file.delete()
        }
    }

    @Test
    fun storedVoicePlaybackFailureRetriesOriginalAttachmentsWithoutSynthesis() = runTest(testDispatcher) {
        val message = MessageEntity(id = 192L, sessionId = 42L, speakerType = "character", branchId = "main", content = "语音")
        val file = File.createTempFile("mojing-stored-voice-retry", ".wav").also { it.writeText("audio") }
        val messages = mockk<MessageDao>(relaxed = true)
        val attachments = mockk<AttachmentDao>(relaxed = true)
        coEvery { messages.getMainMessagesTail(42L, any()) } returns listOf(message)
        coEvery { messages.getMainMessageById(42L, 192L) } returns message
        coEvery { attachments.getByMessage(192L) } returns listOf(
            MessageAttachmentEntity(id = 1L, messageId = 192L, assetType = "voice", mimeType = "audio/wav", storagePath = file.absolutePath),
        )
        val firstHandle = mockk<TtsPlayer.PlaybackHandle>(relaxed = true)
        val secondHandle = mockk<TtsPlayer.PlaybackHandle>(relaxed = true)
        var vm: ChatViewModel? = null
        mockkObject(TtsPlayer)
        mockkObject(AndroidTts)
        mockkObject(AzureSpeech)
        try {
            every { TtsPlayer.playOwned(file, deleteWhenFinished = false, initialPaused = any(), onPhaseChanged = any()) } returnsMany listOf(firstHandle, secondHandle)
            coEvery { TtsPlayer.awaitCompletion(firstHandle) } returns false
            coEvery { TtsPlayer.awaitCompletion(secondHandle) } returns true
            every { TtsPlayer.stop(any<TtsPlayer.PlaybackHandle>()) } returns Unit
            every { AndroidTts.stop() } returns Unit
            coEvery { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) } returns false
            coEvery { AzureSpeech.speak(any(), any(), any(), any(), any(), any()) } returns false
            vm = createViewModel(messageDao = messages, attachmentDao = attachments, appContext = speechTestContext())
            advanceUntilIdle()

            val owner = requireNotNull(vm)
            owner.playVoiceAttachments(192L)
            runCurrent()
            advanceUntilIdle()
            owner.speechActive.first { !it }
            val firstNotice = owner.state.first { it.speechRetryNotice != null }.speechRetryNotice!!
            owner.retryFailedSpeech(firstNotice.token)
            runCurrent()
            advanceUntilIdle()
            owner.speechActive.first { !it }

            verify(exactly = 2) { TtsPlayer.playOwned(file, deleteWhenFinished = false, initialPaused = any(), onPhaseChanged = any()) }
            coVerify(exactly = 1) { TtsPlayer.awaitCompletion(firstHandle) }
            coVerify(exactly = 1) { TtsPlayer.awaitCompletion(secondHandle) }
            coVerify(exactly = 2) { attachments.getByMessage(192L) }
            coVerify(exactly = 0) { AndroidTts.speakAwaitCompletion(any(), any(), any(), any()) }
            coVerify(exactly = 0) { AndroidTts.synthesizeToFiles(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { AzureSpeech.speak(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { AzureSpeech.synthesizeToFiles(any(), any(), any(), any(), any(), any()) }
            assertEquals(null, owner.state.value.speechRetryNotice)
            assertTrue(file.exists())
        } finally {
            vm?.let {
                it.stopSpeaking()
                advanceUntilIdle()
            }
            unmockkObject(AzureSpeech)
            unmockkObject(AndroidTts)
            unmockkObject(TtsPlayer)
            file.delete()
        }
    }

    @Test
    fun imagePromptRestoresAndRemainsInSharedDraftUpdates() = runTest(testDispatcher) {
        val store = emptyDraftStore()
        every { store.load(42L) } returns ChatDraftSnapshot(imagePrompt = "雨夜街景")
        val vm = createViewModel(chatDraftStore = store)
        advanceUntilIdle()
        assertEquals("雨夜街景", vm.state.value.imagePrompt)
        vm.updateInput("独立消息")
        vm.updateNarratorGuidance("另一段方向")
        verify { store.save(42L, match { it.imagePrompt == "雨夜街景" && it.narratorGuidance == "另一段方向" && it.inputText == "独立消息" }) }
        vm.generateAndAttachUserMessage("雨夜街景")
        advanceUntilIdle()
        assertEquals("雨夜街景", vm.state.value.imagePrompt)
    }

    @Test
    fun imagePromptClearsAfterAttachmentCommitButSurvivesFailureCancellationAndNewEdits() = runTest(testDispatcher) {
        for (outcome in listOf("success", "edited", "network", "disk", "cancel")) {
            val gate = CompletableDeferred<Result<String>>()
            val images = mockk<com.mojing.app.data.repository.ImageRepository>(relaxed = true)
            coEvery { images.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { gate.await() }
            coEvery { images.saveGeneratedImageForSession(any(), 42L) } returns if (outcome == "disk") null else "/mock/image.png"
            val vm = createViewModel(imageRepository = images, secureStorage = validSecureStorage())
            advanceUntilIdle()
            vm.updateImagePrompt("雨夜街景")
            assertTrue(vm.generateAndAttachUserMessage("雨夜街景"))
            runCurrent()
            assertFalse(vm.generateAndAttachUserMessage("重复请求"))
            assertEquals("雨夜街景", vm.state.value.imagePrompt)
            if (outcome == "edited") {
                vm.updateImagePrompt("新的描述")
                vm.updateImagePrompt("雨夜街景")
            }
            if (outcome == "cancel") vm.stopGeneration()
            gate.complete(if (outcome == "network") Result.failure(IllegalStateException("offline")) else Result.success("mock-image"))
            advanceUntilIdle()
            assertEquals(if (outcome == "success") "" else "雨夜街景", vm.state.value.imagePrompt)
        }
    }

    @Test
    fun narratorDirectionRestoresAndSurvivesOtherDraftUpdates() = runTest(testDispatcher) {
        val store = emptyDraftStore()
        every { store.load(42L) } returns ChatDraftSnapshot(narratorGuidance = "恢复方向")
        val vm = createViewModel(chatDraftStore = store)
        advanceUntilIdle()
        assertEquals("恢复方向", vm.state.value.narratorGuidance)
        vm.updateInput("独立消息")
        verify { store.save(42L, match { it.inputText == "独立消息" && it.narratorGuidance == "恢复方向" }) }
        vm.updateNarratorGuidance("新方向")
        verify { store.save(42L, match { it.inputText == "独立消息" && it.narratorGuidance == "新方向" }) }
    }

    @Test
    fun narratorDirectionClearsOnlyAfterCommitAndKeepsNewerEdits() = runTest(testDispatcher) {
        for (editDuringSave in listOf(false, true)) {
            val gate = kotlinx.coroutines.CompletableDeferred<Long>()
            val messages = mockk<MessageDao>(relaxed = true)
            coEvery { messages.insert(match { it.speakerType == "user" }) } coAnswers { gate.await() }
            val world = mockk<SessionWorldDao>(relaxed = true)
            coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42)
            val vm = createViewModel(messageDao = messages, sessionWorldDao = world,
                secureStorage = validSecureStorage(), llmApiService = validLlmApiService())
            advanceUntilIdle()
            vm.updateNarratorGuidance("  当前方向  ")
            assertTrue(vm.submitNarratorGuidance("当前方向"))
            runCurrent()
            assertEquals("  当前方向  ", vm.state.value.narratorGuidance)
            if (editDuringSave) {
                vm.updateNarratorGuidance("改写后又恢复")
                vm.updateNarratorGuidance("  当前方向  ")
            }
            gate.complete(99)
            advanceUntilIdle()
            assertEquals(if (editDuringSave) "  当前方向  " else "", vm.state.value.narratorGuidance)
        }
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
            vm.updateNarratorGuidance("预检失败的剧情走向")
            vm.submitNarratorGuidance("预检失败的剧情走向")
            advanceUntilIdle()

            assertEquals(case.error, vm.state.value.error)
            assertEquals("预检失败仍需保留", vm.state.value.inputText)
            assertEquals("预检失败的剧情走向", vm.state.value.narratorGuidance)
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
    fun recallClearsDeletedQuoteIncludingAttachedMessageAndPreservesInputDraft() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val drafts = emptyDraftStore()
        coEvery { dao.recallInSession(42L, 8L) } returns MessageRecallResult(
            deleted = true, deletedMessageIds = listOf(8L, 9L),
        )
        val vm = createViewModel(messageDao = dao, chatDraftStore = drafts)
        advanceUntilIdle()
        vm.updateInput("保留输入")
        vm.setQuotingMessage(MessageEntity(id = 9, sessionId = 42, content = "被一并撤回的引用"))
        val result = CompletableDeferred<Boolean>()
        vm.deleteMessage(8L) { result.complete(it) }
        assertTrue(result.await())
        assertEquals(null, vm.state.value.quotingMessage)
        assertEquals(null, vm.state.value.quotingSnippet)
        assertEquals("保留输入", vm.state.value.inputText)
        verify { drafts.save(42L, ChatDraftSnapshot(inputText = "保留输入")) }
    }

    @Test
    fun lateRecallCompletionKeepsNewQuoteNotInDeletedSet() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val gate = CompletableDeferred<MessageRecallResult>()
        coEvery { dao.recallInSession(42L, 8L) } coAnswers { gate.await() }
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()
        vm.updateInput("保留输入")
        vm.setQuotingMessage(MessageEntity(id = 8, sessionId = 42, content = "旧引用"))
        val result = CompletableDeferred<Boolean>()
        vm.deleteMessage(8L) { result.complete(it) }
        runCurrent()
        val replacement = MessageEntity(id = 10, sessionId = 42, content = "新引用")
        vm.setQuotingMessage(replacement)
        gate.complete(MessageRecallResult(deleted = true, deletedMessageIds = listOf(8L, 9L)))
        assertTrue(result.await())
        assertEquals(replacement, vm.state.value.quotingMessage)
        assertEquals("新引用", vm.state.value.quotingSnippet)
        assertEquals("保留输入", vm.state.value.inputText)
    }

    @Test
    fun rejectedRecallKeepsQuoteAndInputDraft() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.recallInSession(42L, 8L) } throws com.mojing.app.data.local.dao.MessageRecallBlockedException("需要保留分叉来源")
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()
        val source = MessageEntity(id = 8, sessionId = 42, content = "有效引用")
        vm.updateInput("保留输入")
        vm.setQuotingMessage(source)
        val result = CompletableDeferred<Boolean>()
        vm.deleteMessage(8L) { result.complete(it) }
        assertFalse(result.await())
        assertEquals(source, vm.state.value.quotingMessage)
        assertEquals("有效引用", vm.state.value.quotingSnippet)
        assertEquals("保留输入", vm.state.value.inputText)
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
            val bookmarks = mockk<BookmarkDao>(relaxed = true)
            coEvery { bookmarks.getFirstPage(42L, 41) } returns listOf(8L, 9L, 10L).map { id ->
                MessageBookmarkEntity(id = id, sessionId = 42L, messageId = id)
            }
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
                bookmarkDao = bookmarks,
                appContext = context,
            )
            advanceUntilIdle()
            vm.loadBookmarksIfNeeded()
            advanceUntilIdle()
            assertEquals(listOf(8L, 9L, 10L), vm.state.value.bookmarks.map { it.messageId })

            val recalled = CompletableDeferred<Boolean>()
            vm.deleteMessage(8L) { recalled.complete(it) }
            assertTrue(recalled.await())

            coVerify(exactly = 1) { messageDao.recallInSession(42L, 8L) }
            coVerify(exactly = 0) { messageDao.delete(8L) }
            assertEquals(listOf(10L), vm.state.value.bookmarks.map { it.messageId })
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

    @Test fun eventWritesRetainOriginalOwnerAndBusyThroughReadback() = runTest(testDispatcher) {
        for (delete in listOf(false, true)) {
            val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
            val events = mockk<SessionEventNodeDao>(relaxed = true)
            val original = SessionEventNodeEntity(id = 1, sessionId = 42, title = "事件")
            val write = CompletableDeferred<Unit>()
            val readback = CompletableDeferred<List<SessionEventNodeEntity>>()
            var written = false
            coEvery { events.getPageForBranch(42, "main", null, null, any()) } coAnswers {
                if (written) readback.await() else listOf(original)
            }
            coEvery { events.setResolved(1, true) } coAnswers { write.await(); written = true }
            coEvery { events.deleteById(1) } coAnswers { write.await(); written = true }
            val vm = registry.acquire(42L) { store -> createViewModel(eventNodeDao = events).also { store.put("vm", it) } }
            try {
                advanceUntilIdle(); vm.loadEventNodesIfNeeded(); advanceUntilIdle()
                if (delete) vm.deleteEventNode(1) else vm.toggleEventNodeResolved(1)
                runCurrent(); registry.release(42L)
                assertTrue(registry.contains(42L)); assertFalse(42L in registry.running.value)
                write.complete(Unit); runCurrent()
                assertTrue(written); assertTrue(1L in vm.state.value.eventBusyIds)
                assertTrue(registry.contains(42L))
                val reopened = registry.acquire<ChatViewModel>(42L) { error("Lost event readback owner") }
                assertTrue(reopened === vm)
                vm.deleteEventNode(1); vm.toggleEventNodeResolved(1); runCurrent()
                coVerify(exactly = if (delete) 1 else 0) { events.deleteById(1) }
                coVerify(exactly = if (delete) 0 else 1) { events.setResolved(1, true) }
                registry.release(42L)
                val result = if (delete) emptyList() else listOf(original.copy(resolved = true))
                readback.complete(result); advanceUntilIdle()
                assertEquals(result, vm.state.value.eventNodes)
                assertTrue(vm.state.value.eventBusyIds.isEmpty())
                assertFalse(registry.contains(42L))
            } finally {
                write.complete(Unit); readback.complete(emptyList()); registry.stop(42L); registry.release(42L); advanceUntilIdle()
            }
        }
    }

    @Test fun eventStatusFailureReleasesBusyAndRetriesOnSameReopenedOwner() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val original = SessionEventNodeEntity(id = 1, sessionId = 42, title = "事件")
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns listOf(original)
        val gate = CompletableDeferred<Unit>()
        coEvery { events.setResolved(1, true) } coAnswers { gate.await(); throw IllegalStateException("write failed") }
        val vm = registry.acquire(42L) { store -> createViewModel(eventNodeDao = events).also { store.put("vm", it) } }
        try {
            advanceUntilIdle(); vm.loadEventNodesIfNeeded(); advanceUntilIdle()
            vm.toggleEventNodeResolved(1); runCurrent(); registry.release(42L)
            val reopened = registry.acquire<ChatViewModel>(42L) { error("Lost pending event owner") }
            assertTrue(reopened === vm); assertFalse(42L in registry.running.value)
            gate.complete(Unit); advanceUntilIdle()
            assertTrue(vm.state.value.eventBusyIds.isEmpty()); assertNotNull(vm.state.value.eventActionErrors[1])
            assertEquals(listOf(original), vm.state.value.eventNodes)
            coEvery { events.setResolved(1, true) } returns Unit
            coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns listOf(original.copy(resolved = true))
            vm.toggleEventNodeResolved(1); advanceUntilIdle()
            assertTrue(vm.state.value.eventActionErrors.isEmpty()); assertTrue(vm.state.value.eventNodes.single().resolved)
        } finally { gate.complete(Unit); registry.stop(42L); registry.release(42L); advanceUntilIdle() }
    }

    @Test fun eventWriteBlocksDuplicateActionsAndRetainsFailureForRetry() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, title = "事件")
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns listOf(source)
        val gate = CompletableDeferred<Unit>()
        coEvery { events.deleteById(1) } coAnswers { gate.await(); throw IllegalStateException("write failed") }
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        vm.deleteEventNode(1)
        runCurrent()
        vm.deleteEventNode(1)
        vm.toggleEventNodeResolved(1)
        assertTrue(1L in vm.state.value.eventBusyIds)
        coVerify(exactly = 1) { events.deleteById(1) }
        coVerify(exactly = 0) { events.setResolved(any(), any()) }
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.eventBusyIds.isEmpty())
        assertNotNull(vm.state.value.eventActionErrors[1])
        assertEquals(listOf(source), vm.state.value.eventNodes)
        coEvery { events.deleteById(1) } returns Unit
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns emptyList()
        vm.deleteEventNode(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.eventActionErrors.isEmpty())
        assertTrue(vm.state.value.eventNodes.isEmpty())
    }

    @Test fun eventTimelineLoadsBoundedPagesWithStableCursor() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val first = (100L downTo 76L).map { id ->
            SessionEventNodeEntity(id = id, sessionId = 42L, title = "事件$id", createdAt = id)
        }
        val older = (76L downTo 52L).map { id ->
            SessionEventNodeEntity(id = id, sessionId = 42L, title = "事件$id", createdAt = id)
        }
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns first
        coEvery { events.getPageForBranch(42L, "main", 77L, 77L, 25) } returns older

        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()

        assertFalse(vm.state.value.eventNodesLoaded)
        coVerify(exactly = 0) { events.getPageForBranch(42L, "main", null, null, any()) }
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()

        assertEquals((100L downTo 77L).toList(), vm.state.value.eventNodes.map { it.id })
        assertTrue(vm.state.value.eventNodesHasMore)
        vm.loadMoreEventNodes()
        advanceUntilIdle()

        assertEquals((100L downTo 53L).toList(), vm.state.value.eventNodes.map { it.id })
        assertTrue(vm.state.value.eventNodesHasMore)
        assertFalse(vm.state.value.eventNodesLoadingMore)
        assertEquals(null, vm.state.value.eventNodesLoadError)
    }

    @Test fun eventTimelineFirstReadFailureKeepsChatReadyAndCanRetry() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1L, sessionId = 42L, title = "新事件")
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } throws IllegalStateException("read failed")
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)

        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        assertFalse(vm.state.value.eventNodesLoaded)
        assertEquals("事件读取失败，请重试", vm.state.value.eventNodesLoadError)

        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns listOf(source)
        vm.loadMoreEventNodes()
        advanceUntilIdle()
        assertEquals(listOf(source), vm.state.value.eventNodes)
        assertTrue(vm.state.value.eventNodesLoaded)
        assertEquals(null, vm.state.value.eventNodesLoadError)
    }

    @Test fun eventTimelineKeepsLoadedWindowOnRefreshAndResetsAfterBranchSwitch() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val first = (100L downTo 76L).map { id ->
            SessionEventNodeEntity(id = id, sessionId = 42L, title = "事件$id", createdAt = id)
        }
        val older = (76L downTo 52L).map { id ->
            SessionEventNodeEntity(id = id, sessionId = 42L, title = "事件$id", createdAt = id)
        }
        val branchEvent = SessionEventNodeEntity(id = 200L, sessionId = 42L, branchId = "B", title = "分支事件")
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns first
        coEvery { events.getPageForBranch(42L, "main", 77L, 77L, 25) } returns older
        coEvery { events.getPageForBranch(42L, "main", null, null, 49) } returns
            (100L downTo 52L).map { id ->
                SessionEventNodeEntity(id = id, sessionId = 42L, title = "事件$id", createdAt = id)
            }
        coEvery { events.getPageForBranch(42L, "B", null, null, 25) } returns listOf(branchEvent)
        coEvery { branches.getBySession(42L) } returns listOf(
            SessionBranchEntity(sessionId = 42L, branchId = "B", sourceMessageId = 8L),
        )

        val vm = createViewModel(eventNodeDao = events, sessionBranchDao = branches)
        advanceUntilIdle()
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        vm.loadMoreEventNodes()
        advanceUntilIdle()
        assertEquals(48, vm.state.value.eventNodes.size)

        vm.switchBranch("main")
        advanceUntilIdle()
        assertEquals((100L downTo 53L).toList(), vm.state.value.eventNodes.map { it.id })
        coVerify(exactly = 1) { events.getPageForBranch(42L, "main", null, null, 49) }

        vm.switchBranch("B")
        advanceUntilIdle()
        assertEquals(listOf(branchEvent), vm.state.value.eventNodes)
        vm.switchBranch("main")
        advanceUntilIdle()
        assertEquals((100L downTo 77L).toList(), vm.state.value.eventNodes.map { it.id })
    }

    @Test fun deletingAnExpandedEventRefillsTheLoadedWindow() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns
            (100L downTo 76L).map { id -> SessionEventNodeEntity(id = id, sessionId = 42L, createdAt = id) }
        coEvery { events.getPageForBranch(42L, "main", 77L, 77L, 25) } returns
            (76L downTo 52L).map { id -> SessionEventNodeEntity(id = id, sessionId = 42L, createdAt = id) }
        coEvery { events.getPageForBranch(42L, "main", null, null, 49) } returns
            (99L downTo 51L).map { id -> SessionEventNodeEntity(id = id, sessionId = 42L, createdAt = id) }
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()

        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()

        vm.loadMoreEventNodes()
        advanceUntilIdle()
        assertEquals(48, vm.state.value.eventNodes.size)
        assertEquals(48, vm.state.value.eventNodesWindowSize)

        vm.deleteEventNode(100L)
        advanceUntilIdle()
        assertEquals((99L downTo 52L).toList(), vm.state.value.eventNodes.map { it.id })
        assertEquals(48, vm.state.value.eventNodesWindowSize)
        assertTrue(vm.state.value.eventNodesHasMore)
        coVerify(exactly = 1) { events.getPageForBranch(42L, "main", null, null, 49) }
    }

    @Test fun delayedEventRefreshCannotCollapseAnExpandedPage() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val first = (100L downTo 76L).map { id ->
            SessionEventNodeEntity(id = id, sessionId = 42L, createdAt = id)
        }
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns first
        coEvery { events.getPageForBranch(42L, "main", 77L, 77L, 25) } returns
            (76L downTo 52L).map { id -> SessionEventNodeEntity(id = id, sessionId = 42L, createdAt = id) }
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()

        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()

        val delayedRefresh = CompletableDeferred<List<SessionEventNodeEntity>>()
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } coAnswers { delayedRefresh.await() }
        vm.switchBranch("main")
        runCurrent()
        vm.loadMoreEventNodes()
        advanceUntilIdle()
        assertEquals(48, vm.state.value.eventNodes.size)
        assertEquals(48, vm.state.value.eventNodesWindowSize)

        delayedRefresh.complete(first)
        advanceUntilIdle()
        assertEquals((100L downTo 53L).toList(), vm.state.value.eventNodes.map { it.id })
        assertEquals(48, vm.state.value.eventNodesWindowSize)
    }

    @Test fun eventTimelineKeepsLoadedPageWhenOlderReadFailsAndCanRetry() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val first = (30L downTo 6L).map { id ->
            SessionEventNodeEntity(id = id, sessionId = 42L, title = "事件$id", createdAt = id)
        }
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns first
        coEvery { events.getPageForBranch(42L, "main", 7L, 7L, 25) } throws
            IllegalStateException("read failed") andThen listOf(
                SessionEventNodeEntity(id = 6L, sessionId = 42L, title = "事件6", createdAt = 6L),
            )
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        val loaded = vm.state.value.eventNodes

        vm.loadMoreEventNodes()
        advanceUntilIdle()
        assertEquals(loaded, vm.state.value.eventNodes)
        assertTrue(vm.state.value.eventNodesHasMore)
        assertEquals("较早事件读取失败，请重试", vm.state.value.eventNodesLoadError)

        vm.loadMoreEventNodes()
        advanceUntilIdle()
        assertEquals((30L downTo 6L).toList(), vm.state.value.eventNodes.map { it.id })
        assertFalse(vm.state.value.eventNodesHasMore)
        assertEquals(null, vm.state.value.eventNodesLoadError)
    }

    @Test fun inheritedEventStatusStaysOnCurrentStorylineAndDeletionStaysOnSource() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, branchId = "main", title = "继承事件")
        coEvery { events.getPageForBranch(42, "B", null, null, any()) } returns listOf(source.copy(resolved = true))
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "B", sourceMessageId = 8))
        val vm = createViewModel(eventNodeDao = events, sessionBranchDao = branches)
        advanceUntilIdle()
        vm.switchBranch("B")
        advanceUntilIdle()
        assertTrue(vm.state.value.eventNodes.single().resolved)
        coEvery { events.getPageForBranch(42, "B", null, null, any()) } returns listOf(source)
        vm.toggleEventNodeResolved(1)
        advanceUntilIdle()
        assertFalse(vm.state.value.eventNodes.single().resolved)
        coVerify(exactly = 1) { events.upsertStatusOverride(match { it.sessionId == 42L && it.branchId == "B" && it.eventId == 1L && !it.resolved }) }
        coVerify(exactly = 0) { events.setResolved(any(), any()) }
        vm.deleteEventNode(1)
        advanceUntilIdle()
        coVerify(exactly = 0) { events.deleteById(any()) }
        assertTrue(vm.state.value.eventActionErrors[1].orEmpty().contains("来源故事线"))
    }

    @Test fun eventStatusUsesCurrentStorylineAndKeepsSavedStateIfReadbackFails() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, title = "当前线事件")
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns listOf(source)
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } throws IllegalStateException("read failed")
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
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns listOf(source)
        coEvery { events.getPageForBranch(42, "B", null, null, any()) } returns listOf(other)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "B", sourceMessageId = 8))
        val vm = createViewModel(eventNodeDao = events, sessionBranchDao = branches)
        advanceUntilIdle()
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        val reply = CompletableDeferred<List<SessionEventNodeEntity>>()
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } coAnswers { reply.await() }
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
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns listOf(source)
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns emptyList()
        vm.deleteEventNode(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.eventNodes.isEmpty())
        coVerify { events.deleteById(1) }
        coVerify(exactly = 0) { events.getBySession(any()) }
    }

    @Test fun lateEventRefreshCannotUndoANewerStatusChange() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 1, sessionId = 42, title = "事件")
        val other = source.copy(id = 2, title = "另一事件")
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } returns listOf(source, other)
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        val older = CompletableDeferred<List<SessionEventNodeEntity>>()
        var reads = 0
        val latest = listOf(source.copy(resolved = true), other.copy(resolved = true))
        coEvery { events.getPageForBranch(42, "main", null, null, any()) } coAnswers { if (++reads == 1) older.await() else latest }
        vm.toggleEventNodeResolved(1)
        runCurrent()
        assertTrue(vm.state.value.eventNodes.first { it.id == 1L }.resolved)
        // A different event may finish while the first event's readback is late.
        vm.toggleEventNodeResolved(2)
        advanceUntilIdle()
        assertEquals(latest, vm.state.value.eventNodes)
        older.complete(listOf(source.copy(resolved = true), other))
        advanceUntilIdle()
        assertEquals(latest, vm.state.value.eventNodes)
    }

    @Test
    fun bookmarkNavigationSwitchesToSourceBranchBeforeClosingPanel() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getMainMessageById(42, 500) } returns null
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "source", sourceMessageId = 1))
        val target = MessageEntity(id = 500, sessionId = 42, branchId = "source", content = "收藏的原文")
        coEvery { dao.getByIdInSession(500, 42) } returns target
        val release = CompletableDeferred<MessageEntity?>()
        coEvery { dao.getVisibleMessageById(42, "source", 500) } coAnswers { release.await() }
        val preferences = uiPreferences()
        val vm = createViewModel(messageDao = dao, sessionBranchDao = branches, uiPreferencesRepository = preferences)
        advanceUntilIdle()
        var opened = false
        vm.openBookmarkedMessage(500) { opened = true }
        runCurrent()
        assertFalse(opened)
        assertEquals(500L, vm.state.value.bookmarkLocatingId)
        assertEquals("main", vm.state.value.currentBranchId)
        release.complete(target)
        advanceUntilIdle()
        assertTrue(opened)
        assertEquals("source", vm.state.value.currentBranchId)
        assertEquals(500L, vm.state.value.focusedMessageId)
        assertEquals(null, vm.state.value.bookmarkLocatingId)
        coVerify { preferences.setLastChatBranch(42, "source") }
    }

    @Test
    fun bookmarkedOldReplyOpensReadOnlyWithoutChangingSelectedVersion() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val oldReply = MessageEntity(id = 500, sessionId = 42, swipeGroupId = "reply", content = "收藏的旧回复")
        coEvery { dao.getMainMessageById(42, 500) } returns oldReply
        coEvery { dao.getEffectiveSwipeSelectionsForGroups(42, "main", listOf("reply")) } returns
            listOf(BranchSwipeSelectionEntity(42, "main", "reply", 501))
        val preferences = uiPreferences()
        val vm = createViewModel(messageDao = dao, uiPreferencesRepository = preferences)
        advanceUntilIdle()

        var opened = false
        vm.openBookmarkedMessage(500) { opened = true }
        advanceUntilIdle()

        assertTrue(opened)
        assertEquals(oldReply, vm.state.value.bookmarkReadOnlyMessage)
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(null, vm.state.value.focusedMessageId)
        coVerify(exactly = 0) { dao.selectSwipeVariantForBranch(any(), any(), any(), any()) }
        coVerify(exactly = 0) { preferences.setLastChatBranch(any(), any()) }
        vm.closeBookmarkedReadOnlyMessage()
        assertEquals(null, vm.state.value.bookmarkReadOnlyMessage)
    }

    @Test
    fun bookmarkedSelectedReplyStillNavigatesToTheTimeline() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val selectedReply = MessageEntity(id = 500, sessionId = 42, swipeGroupId = "reply", content = "当前回复")
        coEvery { dao.getMainMessageById(42, 500) } returns selectedReply
        coEvery { dao.getEffectiveSwipeSelectionsForGroups(42, "main", listOf("reply")) } returns
            listOf(BranchSwipeSelectionEntity(42, "main", "reply", 500))
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()

        vm.openBookmarkedMessage(500)
        advanceUntilIdle()

        assertEquals(null, vm.state.value.bookmarkReadOnlyMessage)
        assertEquals(500L, vm.state.value.focusedMessageId)
    }

    @Test
    fun bookmarkedOldReplyOnAnotherBranchKeepsTheCurrentStoryline() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val oldReply = MessageEntity(id = 500, sessionId = 42, branchId = "source", swipeGroupId = "reply", content = "旧故事线回复")
        coEvery { dao.getMainMessageById(42, 500) } returns null
        coEvery { dao.getByIdInSession(500, 42) } returns oldReply
        coEvery { dao.getVisibleMessageById(42, "source", 500) } returns oldReply
        coEvery { dao.getEffectiveSwipeSelectionsForGroups(42, "source", listOf("reply")) } returns
            listOf(BranchSwipeSelectionEntity(42, "source", "reply", 501))
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "source", sourceMessageId = 1))
        val preferences = uiPreferences()
        val vm = createViewModel(messageDao = dao, sessionBranchDao = branches, uiPreferencesRepository = preferences)
        advanceUntilIdle()

        vm.openBookmarkedMessage(500)
        advanceUntilIdle()

        assertEquals(oldReply, vm.state.value.bookmarkReadOnlyMessage)
        assertEquals("main", vm.state.value.currentBranchId)
        coVerify(exactly = 0) { preferences.setLastChatBranch(any(), any()) }
    }

    @Test
    fun bookmarkNavigationFailureKeepsPanelAndCanRetry() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getMainMessageById(42, 500) } throws IllegalStateException("read failed")
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()
        var opened = false
        vm.openBookmarkedMessage(500) { opened = true }
        advanceUntilIdle()
        assertFalse(opened)
        assertEquals(null, vm.state.value.bookmarkLocatingId)
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("收藏原文定位失败，请重试", vm.state.value.error)
        coEvery { dao.getMainMessageById(42, 500) } returns MessageEntity(id = 500, sessionId = 42, content = "原文")
        vm.openBookmarkedMessage(500) { opened = true }
        advanceUntilIdle()
        assertTrue(opened)
        assertEquals(500L, vm.state.value.focusedMessageId)
    }

    @Test
    fun bookmarkNavigationDoesNotCloseForDeletedSource() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getMainMessageById(42, 500) } returns null
        coEvery { dao.getByIdInSession(500, 42) } returns null
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()
        var opened = false
        vm.openBookmarkedMessage(500) { opened = true }
        advanceUntilIdle()
        assertFalse(opened)
        assertEquals(null, vm.state.value.bookmarkLocatingId)
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("收藏原文已删除或所在故事线已不可用", vm.state.value.error)
    }

    @Test
    fun historyJumpReportsBusyAndAcceptsRetryAfterCurrentLoad() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val release = CompletableDeferred<MessageEntity?>()
        coEvery { dao.getMainMessageById(42L, 500L) } coAnswers { release.await() }
        coEvery { dao.getMainMessageById(42L, 600L) } returns MessageEntity(id = 600, sessionId = 42, content = "第二个来源")
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()
        assertTrue(vm.openMessageInHistory(500L))
        runCurrent()
        assertTrue(vm.state.value.isLoadingHistory)
        assertFalse(vm.openMessageInHistory(600L))
        coVerify(exactly = 0) { dao.getMainMessageById(42L, 600L) }
        release.complete(MessageEntity(id = 500, sessionId = 42, content = "第一个来源"))
        advanceUntilIdle()
        assertEquals(500L, vm.state.value.focusedMessageId)
        assertFalse(vm.state.value.isLoadingHistory)
        assertTrue(vm.openMessageInHistory(600L))
        advanceUntilIdle()
        assertEquals(600L, vm.state.value.focusedMessageId)
    }

    @Test
    fun searchHistoryJumpWaitsForLoadedTargetAndKeepsFailedHitOpen() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val release = CompletableDeferred<MessageEntity?>()
        coEvery { dao.getMainMessageById(42L, 500L) } coAnswers { release.await() }
        coEvery { dao.getMainMessageById(42L, 600L) } returns null
        val vm = createViewModel(messageDao = dao)
        advanceUntilIdle()

        val outcomes = mutableListOf<Boolean>()
        assertTrue(vm.openMessageInHistoryWithResult(500L, outcomes::add))
        runCurrent()
        assertTrue(vm.state.value.isLoadingHistory)
        assertTrue(outcomes.isEmpty())
        assertFalse(vm.openMessageInHistoryWithResult(600L, outcomes::add))
        release.complete(MessageEntity(id = 500L, sessionId = 42L, content = "搜索命中"))
        advanceUntilIdle()
        assertEquals(listOf(true), outcomes)
        assertEquals(500L, vm.state.value.focusedMessageId)

        assertTrue(vm.openMessageInHistoryWithResult(600L, outcomes::add))
        advanceUntilIdle()
        assertEquals(listOf(true, false), outcomes)
        assertEquals(500L, vm.state.value.focusedMessageId)

        coEvery { dao.getMainMessageById(42L, 600L) } throws IllegalStateException("read failed")
        assertTrue(vm.openMessageInHistoryWithResult(600L, outcomes::add))
        advanceUntilIdle()
        assertEquals(listOf(true, false, false), outcomes)
        assertEquals(500L, vm.state.value.focusedMessageId)
    }

    @Test fun memorySourceOpensOwningLineFromMain() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val branch = SessionBranchEntity(sessionId = 42, branchId = "source", sourceMessageId = 1)
        val target = MessageEntity(id = 500, sessionId = 42, branchId = "source", content = "子线独有原文")
        coEvery { dao.getMainMessageById(42, 500) } returns null
        coEvery { dao.getByIdInSession(500, 42) } returns target
        coEvery { branches.getBySession(42) } returns listOf(branch)
        coEvery { branches.getByBranch(42, "source") } returns branch
        coEvery { dao.getVisibleMessageById(42, "source", 500) } returns target
        val preferences = uiPreferences()
        val vm = createViewModel(messageDao = dao, sessionBranchDao = branches, uiPreferencesRepository = preferences)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); advanceUntilIdle()
        assertEquals(listOf(true), results)
        assertEquals("source", vm.state.value.currentBranchId)
        assertEquals(500L, vm.state.value.focusedMessageId)
        coVerify { preferences.setLastChatBranch(42, "source") }
    }

    @Test fun memorySourceKeepsInheritedOriginalInCurrentLine() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val branch = SessionBranchEntity(sessionId = 42, branchId = "child", sourceMessageId = 600)
        val target = MessageEntity(id = 500, sessionId = 42, content = "继承主线原文")
        coEvery { branches.getBySession(42) } returns listOf(branch)
        coEvery { branches.getByBranch(42, "child") } returns branch
        coEvery { dao.getVisibleMessageById(42, "child", 500) } returns target
        val vm = createViewModel(messageDao = dao, sessionBranchDao = branches, uiPreferencesRepository = uiPreferences("child"))
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); advanceUntilIdle()
        assertEquals(listOf(true), results)
        assertEquals("child", vm.state.value.currentBranchId)
        assertEquals(500L, vm.state.value.focusedMessageId)
        coVerify(exactly = 0) { dao.getByIdInSession(500, 42) }
    }

    @Test fun memorySourceDeletedLineOrInvisibleMessageKeepsMain() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val branch = SessionBranchEntity(sessionId = 42, branchId = "source", sourceMessageId = 1)
        coEvery { dao.getMainMessageById(42, 500) } returns null
        coEvery { dao.getByIdInSession(500, 42) } returns MessageEntity(id = 500, sessionId = 42, branchId = "source")
        coEvery { branches.getByBranch(42, "source") } returns null
        val vm = createViewModel(messageDao = dao, sessionBranchDao = branches)
        advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); advanceUntilIdle()
        coEvery { branches.getByBranch(42, "source") } returns branch
        coEvery { dao.getVisibleMessageById(42, "source", 500) } returns null
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); advanceUntilIdle()
        assertEquals(listOf(false, false), results)
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(null, vm.state.value.focusedMessageId)
    }

    @Test fun memorySourceReadFailureReturnsFalseAndCanRetry() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getMainMessageById(42, 500) } returns null
        coEvery { dao.getByIdInSession(500, 42) } throws IllegalStateException("read failed")
        val vm = createViewModel(messageDao = dao); advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); advanceUntilIdle()
        assertEquals(listOf(false), results)
        coEvery { dao.getMainMessageById(42, 500) } returns MessageEntity(id = 500, sessionId = 42)
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); advanceUntilIdle()
        assertEquals(listOf(false, true), results)
        assertEquals(500L, vm.state.value.focusedMessageId)
    }

    @Test fun memorySourceRejectsConcurrentOwnerWhileLookupIsPending() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val gate = CompletableDeferred<MessageEntity?>()
        coEvery { dao.getMainMessageById(42, 500) } returns null
        coEvery { dao.getByIdInSession(500, 42) } coAnswers { gate.await() }
        val vm = createViewModel(messageDao = dao); advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); runCurrent()
        assertFalse(vm.openMemorySourceInHistory(600, results::add))
        assertTrue(results.isEmpty())
        gate.complete(null); advanceUntilIdle()
        assertEquals(listOf(false), results)
        coVerify(exactly = 0) { dao.getByIdInSession(600, 42) }
        assertEquals("main", vm.state.value.currentBranchId)
    }

    @Test fun memorySourceDisappearingDuringRefreshDoesNotFallBackOrReportSuccess() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        val branch = SessionBranchEntity(sessionId = 42, branchId = "source", sourceMessageId = 1)
        var available = true
        coEvery { branches.getBySession(42) } answers { if (available) listOf(branch) else emptyList() }
        coEvery { branches.getByBranch(42, "source") } answers { available = false; branch }
        val target = MessageEntity(id = 500, sessionId = 42, branchId = "source")
        coEvery { dao.getMainMessageById(42, 500) } returns null
        coEvery { dao.getByIdInSession(500, 42) } returns target
        coEvery { dao.getVisibleMessageById(42, "source", 500) } returns target
        val vm = createViewModel(messageDao = dao, sessionBranchDao = branches); advanceUntilIdle()
        val results = mutableListOf<Boolean>()
        assertTrue(vm.openMemorySourceInHistory(500, results::add)); advanceUntilIdle()
        assertEquals(listOf(false), results)
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(null, vm.state.value.focusedMessageId)
    }

    @Test fun sourceNavigationLoadsRequestedBranchAndFocusesOriginalMessage() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "source", sourceMessageId = 1))
        val target = MessageEntity(id = 500, sessionId = 42, content = "继承的主线原文")
        coEvery { dao.getVisibleMessageById(42, "source", 500) } returns target
        val vm = createViewModel(messageDao = dao, sessionBranchDao = branches, sourceMessageId = 500, sourceBranchId = "source")
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)
        assertEquals("source", vm.state.value.currentBranchId)
        assertEquals(500L, vm.state.value.focusedMessageId)
        assertTrue(vm.state.value.messages.any { it.id == 500L })
        coVerify(exactly = 0) { dao.getMainMessageById(42, 500) }
    }

    @Test fun deletedSourceBranchDoesNotOpenMainLine() = runTest(testDispatcher) {
        val vm = createViewModel(sourceMessageId = 500, sourceBranchId = "deleted")
        advanceUntilIdle()
        assertFalse(vm.state.value.isReady)
        assertEquals("来源故事线已不存在，请返回百科查看保留的资料。", vm.state.value.initialLoadError)
    }

    @Test fun sourceLookupFailureKeepsPageUnreadyUntilRetryLocatesMessage() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        val gate = CompletableDeferred<MessageEntity?>()
        coEvery { dao.getMainMessageById(42, 500) } coAnswers { gate.await() }
        val vm = createViewModel(messageDao = dao, sourceMessageId = 500, sourceBranchId = "main")
        runCurrent()
        assertFalse(vm.state.value.isReady)
        vm.retryInitialization()
        coVerify(exactly = 1) { dao.getMainMessageById(42, 500) }
        gate.completeExceptionally(IllegalStateException("read failed"))
        advanceUntilIdle()
        assertFalse(vm.state.value.isReady)
        assertEquals("来源对话加载失败，请重试", vm.state.value.initialLoadError)
        coEvery { dao.getMainMessageById(42, 500) } returns MessageEntity(id = 500, sessionId = 42, content = "找到的原文")
        vm.retryInitialization()
        advanceUntilIdle()
        assertTrue(vm.state.value.isReady)
        assertEquals(null, vm.state.value.initialLoadError)
        assertEquals(500L, vm.state.value.focusedMessageId)
    }

    @Test fun missingSourceMessageDoesNotDisplayAnUnrelatedHistoryWindow() = runTest(testDispatcher) {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getMainMessageById(42, 500) } returns null
        val vm = createViewModel(messageDao = dao, sourceMessageId = 500, sourceBranchId = "main")
        advanceUntilIdle()
        assertFalse(vm.state.value.isReady)
        assertEquals("来源消息已删除或不在来源故事线，请返回百科。", vm.state.value.initialLoadError)
        assertEquals(null, vm.state.value.focusedMessageId)
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
        vm.loadMemorySummariesIfNeeded()
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
        val events = mockk<SessionEventNodeDao>(relaxed = true)
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
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns
            (25L downTo 1L).map { id -> SessionEventNodeEntity(id = id, sessionId = 42L, createdAt = id) }
        coEvery { events.getPageForBranch(42L, "main", 2L, 2L, 25) } returns listOf(
            SessionEventNodeEntity(id = 1L, sessionId = 42L, createdAt = 1L),
        )
        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            eventNodeDao = events,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        vm.loadMoreEventNodes()
        advanceUntilIdle()
        assertEquals(25, vm.state.value.eventNodes.size)

        vm.createBranch(7L)
        advanceUntilIdle()

        assertTrue(vm.state.value.currentBranchId.startsWith("branch_"))
        assertTrue(vm.state.value.eventNodes.isEmpty())
        assertEquals(null, vm.state.value.branchNavigationLabel)
        coVerify(exactly = 1) {
            events.getPageForBranch(42L, match { it.startsWith("branch_") }, null, null, 25)
        }
        coVerify(exactly = 1) {
            preferences.setLastChatBranch(42L, match { it.startsWith("branch_") })
        }
    }

    @Test
    fun creatingBranchReadFailureKeepsOriginalConversationUntilRetryOpensNewBranch() = runTest(testDispatcher) {
        val anchor = MessageEntity(id = 7L, sessionId = 42L, speakerType = "user", content = "分叉来源")
        val later = MessageEntity(id = 8L, sessionId = 42L, speakerType = "character", content = "后续剧情")
        val originalEvent = SessionEventNodeEntity(id = 10L, sessionId = 42L, title = "原线事件")
        val messageDao = mockk<MessageDao>(relaxed = true)
        val branchDao = mockk<SessionBranchDao>(relaxed = true)
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val preferences = uiPreferences()
        val branches = mutableListOf<SessionBranchEntity>()
        val branchRead = CompletableDeferred<List<SessionEventNodeEntity>>()
        coEvery { messageDao.getMainMessagesTail(42L, any()) } returns listOf(later, anchor)
        coEvery { messageDao.getMainMessageById(42L, 7L) } returns anchor
        coEvery { messageDao.getVisibleMessagesTail(42L, match { it.startsWith("branch_") }, any()) } returns listOf(anchor)
        coEvery { branchDao.getBySession(42L) } answers { branches.toList() }
        coEvery { branchDao.insert(any()) } answers {
            branches += args.first() as SessionBranchEntity
            1L
        }
        coEvery { events.getPageForBranch(42L, "main", null, null, 25) } returns listOf(originalEvent)
        coEvery { events.getPageForBranch(42L, match { it.startsWith("branch_") }, null, null, 25) } coAnswers {
            branchRead.await()
        }
        val vm = createViewModel(
            messageDao = messageDao,
            sessionBranchDao = branchDao,
            eventNodeDao = events,
            uiPreferencesRepository = preferences,
        )
        advanceUntilIdle()

        vm.loadEventNodesIfNeeded()
        advanceUntilIdle()
        vm.createBranch(7L)
        runCurrent()
        assertEquals("正在创建并打开故事线…", vm.state.value.branchNavigationLabel)
        assertEquals("main", vm.state.value.currentBranchId)
        branchRead.completeExceptionally(IllegalStateException("read failed"))
        advanceUntilIdle()

        val newBranchId = branches.single().branchId
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals(setOf(7L, 8L), vm.state.value.messages.map { it.id }.toSet())
        assertEquals(listOf(originalEvent), vm.state.value.eventNodes)
        assertTrue(vm.state.value.branches.any { it.branchId == newBranchId })
        assertEquals("故事线已创建，但打开失败，请从故事线列表重试", vm.state.value.error)
        assertEquals(null, vm.state.value.branchNavigationLabel)
        coVerify(exactly = 0) { preferences.setLastChatBranch(42L, newBranchId) }

        coEvery { events.getPageForBranch(42L, newBranchId, null, null, 25) } returns emptyList()
        vm.switchBranch(newBranchId)
        advanceUntilIdle()

        assertEquals(newBranchId, vm.state.value.currentBranchId)
        assertEquals(listOf(7L), vm.state.value.messages.map { it.id })
        assertTrue(vm.state.value.eventNodes.isEmpty())
        coVerify(exactly = 1) { preferences.setLastChatBranch(42L, newBranchId) }
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

        vm.loadMemorySummariesIfNeeded()
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
            var reportedFailure: Pair<String, Boolean>? = null
            val preferences = uiPreferences()
            val vm = createViewModel(
                messageDao = messageDao,
                sessionBranchDao = branchDao,
                uiPreferencesRepository = preferences,
            )
            advanceUntilIdle()

            vm.editMessage(7L, "改写后的回复", onFailure = { message, committed -> reportedFailure = message to committed }) { successCalled = true }
            advanceUntilIdle()

            assertFalse(successCalled)
            assertFalse(vm.state.value.isGenerating)
            assertEquals("main", vm.state.value.currentBranchId)
            assertEquals("消息已编辑，但后续状态更新失败，请重新进入对话", vm.state.value.error)
            assertEquals(vm.state.value.error to true, reportedFailure)
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
        var reportedFailure: Pair<String, Boolean>? = null
        val vm = createViewModel(messageDao = messageDao, sessionBranchDao = branchDao)
        advanceUntilIdle()

        vm.editMessage(7L, "改写后的回复", onFailure = { message, committed -> reportedFailure = message to committed }) { successCalled = true }
        advanceUntilIdle()

        assertFalse(successCalled)
        assertFalse(vm.state.value.isGenerating)
        assertEquals("main", vm.state.value.currentBranchId)
        assertEquals("消息编辑失败，请重试", vm.state.value.error)
        assertEquals(vm.state.value.error to false, reportedFailure)
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
    fun historyJumpOpensUnloadedResultWindow() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val target = MessageEntity(id = 500L, sessionId = 42L, content = "远页钟声")
        val before = (499L downTo 458L).map { id ->
            MessageEntity(id = id, sessionId = 42L, content = "旧消息$id")
        }
        val after = (501L..542L).map { id ->
            MessageEntity(id = id, sessionId = 42L, content = "新消息$id")
        }
        coEvery { messageDao.getMainMessageById(42L, 500L) } returns target
        coEvery { messageDao.getMainMessagesBefore(42L, 500L, 41) } returns before
        coEvery { messageDao.getMainMessagesAfter(42L, 500L, 41) } returns after

        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

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
    fun chapterRefreshKeepsHistoricalWindowUntilReaderReturnsToLatest() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val target = MessageEntity(id = 500L, sessionId = 42L, content = "旧剧情")
        coEvery { messageDao.getMainMessagesTail(42L, 81) } returns
            (601L downTo 521L).map { MessageEntity(id = it, sessionId = 42L, content = "最新消息") }
        coEvery { messageDao.getMainMessageById(42L, 500L) } returns target
        coEvery { messageDao.getMainMessagesBefore(42L, 500L, 41) } returns
            (499L downTo 458L).map { MessageEntity(id = it, sessionId = 42L, content = "旧消息") }
        coEvery { messageDao.getMainMessagesAfter(42L, 500L, 41) } returns
            (501L..542L).map { MessageEntity(id = it, sessionId = 42L, content = "后续消息") }
        coEvery { messageDao.getMainMessagesBefore(42L, 541L, 82) } returns
            (540L downTo 459L).map { MessageEntity(id = it, sessionId = 42L, content = "窗口消息") }
        coEvery { messageDao.getMainMessagesAfter(42L, 540L, 1) } returns
            listOf(MessageEntity(id = 541L, sessionId = 42L, content = "较新消息"))
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()

        assertTrue(vm.openMessageInHistory(500L))
        advanceUntilIdle()
        vm.renameChapter(500L, "更新标题") {}
        advanceUntilIdle()

        assertEquals(81, vm.state.value.messages.size)
        assertEquals(460L, vm.state.value.messages.first().id)
        assertEquals(540L, vm.state.value.messages.last().id)
        assertTrue(vm.state.value.hasNewerMessages)
        coVerify(exactly = 1) { messageDao.getMainMessagesTail(42L, 81) }

        assertTrue(vm.returnToLatestMessages())
        advanceUntilIdle()
        assertEquals(601L, vm.state.value.messages.last().id)
        assertFalse(vm.state.value.hasNewerMessages)
        coVerify(exactly = 2) { messageDao.getMainMessagesTail(42L, 81) }
    }

    @Test
    fun historicalWindowPreservesDraftsUntilReturningToLatest() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val target = MessageEntity(id = 500L, sessionId = 42L, content = "旧剧情")
        val release = CompletableDeferred<MessageEntity?>()
        coEvery { messageDao.getMainMessageById(42L, 500L) } coAnswers { release.await() }
        coEvery { messageDao.getMainMessagesAfter(42L, 500L, 41) } returns
            (501L..541L).map { MessageEntity(id = it, sessionId = 42L, content = "后续剧情") }
        val vm = createViewModel(messageDao = messageDao)
        advanceUntilIdle()
        vm.updateInput("待发送的正文")
        vm.updateNarratorGuidance("下一段走向")

        assertTrue(vm.openMessageInHistory(500L))
        runCurrent()
        vm.sendMessage()
        assertEquals("历史消息加载中，请稍后再续聊", vm.state.value.error)
        assertEquals("待发送的正文", vm.state.value.inputText)

        release.complete(target)
        advanceUntilIdle()
        assertTrue(vm.state.value.hasNewerMessages)
        vm.sendMessage()
        assertFalse(vm.submitNarratorGuidance("下一段走向"))
        assertFalse(vm.requestNarrator(nextChapter = true, chapterTitle = "下一章"))
        assertEquals("正在查看较早消息，请先回到最新再续聊", vm.state.value.error)
        assertEquals("待发送的正文", vm.state.value.inputText)
        assertEquals("下一段走向", vm.state.value.narratorGuidance)
        assertFalse(vm.state.value.isGenerating)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun tavernImportUsesOneStableAtomicBatchAndSkipsDuplicateRetry() = runTest(testDispatcher) {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val participantDao = mockk<ParticipantDao>(relaxed = true)
        coEvery { participantDao.getBySession(42L) } returns listOf(
            SessionParticipantEntity(sessionId = 42L, characterId = 3L),
        )
        coEvery {
            messageDao.insertImportStreamIfAbsent(42L, "main", any(), 2, 3L, any(), any())
        } returnsMany listOf(2, 0)
        val vm = createViewModel(messageDao = messageDao, participantDao = participantDao)
        advanceUntilIdle()
        val json = """
            {"messages":[
              {"speakerType":"user","content":"第一句"},
              {"speakerType":"character","content":"第二句"}
            ]}
        """.trimIndent()

        val first = vm.importTavernChatStream({ java.io.StringReader(json) })
        val retry = vm.importTavernChatStream({ java.io.StringReader(json) })

        assertEquals(2, first.importedCount)
        assertFalse(first.duplicate)
        assertEquals(0, retry.importedCount)
        assertTrue(retry.duplicate)
        coVerify(exactly = 2) {
            messageDao.insertImportStreamIfAbsent(
                42L, "main", any(), 2, 3L, any(), any(),
            )
        }
    }

    @Test
    fun cancelledGenerationBlocksRetryUntilCleanupCompletes() = runTest(testDispatcher) {
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
        assertFalse(secondStarted.isCompleted)
        assertTrue(vm.state.value.isGenerating)

        oldCleanupRelease.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.isGenerating)

        vm.sendMessage()
        runCurrent()
        assertTrue(secondStarted.isCompleted)
        assertTrue(vm.state.value.isGenerating)

        vm.stopGeneration()
        advanceUntilIdle()
    }
    @Test
    fun narratorReplyFinishesOnceAfterLeavingAndReopeningTheSession() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val messages = mockk<MessageDao>(relaxed = true)
        val world = mockk<SessionWorldDao>(relaxed = true)
        coEvery { world.getBySession(42L) } returns SessionWorldEntity(sessionId = 42L)
        val engine = mockk<ChatEngine>(relaxed = true)
        val finish = CompletableDeferred<Unit>()
        every { engine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            emit(StreamState.Generating("窗外的雨"))
            finish.await()
            emit(StreamState.Done("窗外的雨渐渐停了。"))
        }
        val vm = registry.acquire(42L) { store ->
            createViewModel(messageDao = messages, sessionWorldDao = world, chatEngine = engine,
                secureStorage = validSecureStorage(), llmApiService = validLlmApiService()).also { store.put("vm", it) }
        }
        try {
            advanceUntilIdle()
            assertTrue(vm.requestNarrator()); runCurrent()
            assertTrue(vm.state.value.isGenerating)
            registry.release(42L)
            assertTrue(42L in registry.running.value)
            val reopened = registry.acquire<ChatViewModel>(42L) { error("Duplicate generation owner") }
            assertTrue(reopened === vm)
            registry.release(42L)
            finish.complete(Unit); advanceUntilIdle()
            coVerify(exactly = 1) { messages.insert(match { it.speakerType == "narrator" && it.content == "窗外的雨渐渐停了。" }) }
            assertFalse(vm.state.value.isGenerating)
        } finally {
            finish.complete(Unit)
            vm.stopGeneration()
            registry.release(42L)
            advanceUntilIdle()
        }
    }

    @Test
    fun bookmarkNoteSaveKeepsIdentityAndCreatedAt() = runTest(testDispatcher) {
        val mark = MessageBookmarkEntity(id = 71L, sessionId = 42L, messageId = 501L, note = "旧", createdAt = 1234L)
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        coEvery { bookmarks.getFirstPage(42L, any()) } returns listOf(mark)
        coEvery { bookmarks.updateNote(42L, 71L, "新备注") } returns 1
        val vm = createViewModel(bookmarkDao = bookmarks)
        advanceUntilIdle()
        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        vm.updateBookmarkNoteDraft(71L, "新备注")
        vm.saveBookmarkNote(71L, "新备注")
        advanceUntilIdle()

        val saved = vm.state.value.bookmarks.single()
        assertEquals(71L, saved.id)
        assertEquals(1234L, saved.createdAt)
        assertEquals("新备注", saved.note)
        assertTrue(vm.state.value.bookmarkNoteDrafts[71L] == null)
        coVerify(exactly = 1) { bookmarks.updateNote(42L, 71L, "新备注") }
    }

    @Test
    fun bookmarkNoteFailureKeepsDraftAndCanRetry() = runTest(testDispatcher) {
        val mark = MessageBookmarkEntity(id = 72L, sessionId = 42L, messageId = 502L, createdAt = 2345L)
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        coEvery { bookmarks.getFirstPage(42L, any()) } returns listOf(mark)
        coEvery { bookmarks.updateNote(42L, 72L, "待重试") } throws IllegalStateException("offline") andThen 1
        val vm = createViewModel(bookmarkDao = bookmarks)
        advanceUntilIdle()
        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        vm.updateBookmarkNoteDraft(72L, "待重试")
        vm.saveBookmarkNote(72L, "待重试")
        advanceUntilIdle()
        assertEquals("待重试", vm.state.value.bookmarkNoteDrafts[72L])
        assertEquals("备注保存失败，请重试", vm.state.value.bookmarkNoteErrors[72L])
        vm.saveBookmarkNote(72L, "待重试")
        advanceUntilIdle()
        assertEquals("待重试", vm.state.value.bookmarks.single().note)
        assertTrue(vm.state.value.bookmarkNoteDrafts[72L] == null)
        coVerify(exactly = 2) { bookmarks.updateNote(42L, 72L, "待重试") }
    }

    @Test
    fun bookmarkNotePreventsDuplicateSaveAndRestoresDraftFromSavedState() = runTest(testDispatcher) {
        val mark = MessageBookmarkEntity(id = 73L, sessionId = 42L, messageId = 503L)
        val bookmarks = mockk<BookmarkDao>(relaxed = true)
        coEvery { bookmarks.getFirstPage(42L, any()) } returns listOf(mark)
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        coEvery { bookmarks.updateNote(42L, 73L, "并发") } coAnswers {
            saveStarted.complete(Unit)
            releaseSave.await()
            1
        }
        val handle = SavedStateHandle(mapOf("sessionId" to 42L))
        val vm = createViewModel(bookmarkDao = bookmarks, savedStateHandle = handle)
        advanceUntilIdle()
        vm.loadBookmarksIfNeeded()
        advanceUntilIdle()
        vm.updateBookmarkNoteDraft(73L, "并发")
        vm.saveBookmarkNote(73L, "并发")
        saveStarted.await()
        vm.updateBookmarkNoteDraft(73L, "保存期间新编辑")
        vm.saveBookmarkNote(73L, "并发")
        runCurrent()
        coVerify(exactly = 1) { bookmarks.updateNote(42L, 73L, "并发") }
        assertTrue(73L in vm.state.value.bookmarkNoteSavingIds)
        releaseSave.complete(Unit)
        advanceUntilIdle()
        assertEquals("保存期间新编辑", vm.state.value.bookmarkNoteDrafts[73L])
        val reopened = createViewModel(bookmarkDao = bookmarks, savedStateHandle = handle)
        assertEquals("保存期间新编辑", reopened.state.value.bookmarkNoteDrafts[73L])
    }

    @Test fun eventWindowRecreationRestoresBoundedCursorAndKeepsSameLineRefresh() = runTest(testDispatcher) {
        val handle = SavedStateHandle(mapOf("sessionId" to 42L))
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        coEvery { events.getPageForBranch(42, "main", any(), any(), any()) } coAnswers {
            val before = arg<Long?>(3) ?: 101L
            (before - 1 downTo (before - arg<Int>(4)).coerceAtLeast(1L)).map { id ->
                SessionEventNodeEntity(id=id,sessionId=42,createdAt=id)
            }
        }
        val vm=createViewModel(eventNodeDao=events,savedStateHandle=handle)
        advanceUntilIdle();vm.loadEventNodesIfNeeded();advanceUntilIdle()
        repeat(3) { vm.loadMoreEventNodes();advanceUntilIdle() }
        assertEquals((76L downTo 5L).toList(),vm.state.value.eventNodes.map { it.id })
        assertEquals(72,handle.get<Int>("event_window_size_42"))
        assertEquals(77L,handle.get<Long>("event_window_before_id_42"))
        vm.switchBranch("main");advanceUntilIdle()
        assertEquals(72,handle.get<Int>("event_window_size_42"))
        val reopened=createViewModel(eventNodeDao=events,savedStateHandle=handle)
        advanceUntilIdle();reopened.loadEventNodesIfNeeded();advanceUntilIdle()
        assertEquals(vm.state.value.eventNodes,reopened.state.value.eventNodes)
        assertEquals(77L,reopened.state.value.eventNodesBeforeId)
        coVerify(atLeast=1) { events.getPageForBranch(42,"main",77L,77L,73) }
        reopened.resetEventWindow();advanceUntilIdle()
        assertEquals((100L downTo 77L).toList(),reopened.state.value.eventNodes.map { it.id })
        assertNull(handle.get<Long>("event_window_before_id_42"))
        val reset=createViewModel(eventNodeDao=events,savedStateHandle=handle)
        advanceUntilIdle();reset.loadEventNodesIfNeeded();advanceUntilIdle()
        assertEquals(24,reset.state.value.eventNodesWindowSize)
        assertNull(reset.state.value.eventNodesBeforeId)
    }

    @Test fun eventWindowChildRestorationWaitsForReadyAndRetriesSameCursor() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L,"event_criteria_branch_42" to "B", "event_query_42" to "港口", "event_resolved_42" to false,"event_window_size_42" to 72,"event_window_before_at_42" to 77L,"event_window_before_id_42" to 77L))
        val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=7))
        val ready=CompletableDeferred<Unit>()
        val visibility=mockk<BranchVisibilityIndexManager>(relaxed=true)
        coEvery { visibility.ensureReady() } coAnswers { ready.await() }
        val events=mockk<SessionEventNodeDao>(relaxed=true)
        val source=SessionEventNodeEntity(id=10,sessionId=42,branchId="B",title="港口")
        coEvery { events.getFilteredPageForBranch(42,"B","港口",false,77L,77L,73) } throws IllegalStateException("read failed") andThen listOf(source)
        val vm=createViewModel(eventNodeDao=events,savedStateHandle=handle,sessionBranchDao=branches,uiPreferencesRepository=uiPreferences("B"),branchVisibilityIndexManager=visibility)
        runCurrent();vm.loadEventNodesIfNeeded();runCurrent()
        coVerify(exactly=0) { events.getPageForBranch(any(),any(),any(),any(),any()) }
        coVerify(exactly=0) { events.getFilteredPageForBranch(any(),any(),any(),any(),any(),any(),any()) }
        ready.complete(Unit);advanceUntilIdle();vm.loadEventNodesIfNeeded();advanceUntilIdle()
        assertFalse(vm.state.value.eventNodesLoaded)
        assertEquals(77L,vm.state.value.eventNodesBeforeId)
        vm.loadMoreEventNodes();advanceUntilIdle()
        assertEquals(listOf(source),vm.state.value.eventNodes)
        assertEquals(72,vm.state.value.eventNodesWindowSize)
        coVerify(exactly=2) { events.getFilteredPageForBranch(42,"B","港口",false,77L,77L,73) }
    }

    @Test fun eventWindowRejectsOtherScopeOversizedAndUnpairedCursor() = runTest(testDispatcher) {
        for (values in listOf(
            mapOf("event_criteria_branch_42" to "B","event_window_size_42" to 72),
            mapOf("event_criteria_branch_43" to "main","event_window_size_43" to 72),
            mapOf("event_criteria_branch_42" to "main","event_window_size_42" to 999),
            mapOf("event_criteria_branch_42" to "main","event_window_size_42" to 72,"event_window_before_id_42" to 77L),
            mapOf("event_criteria_branch_42" to "main","event_window_size_42" to 72,"event_window_before_at_42" to 77L,"event_window_before_id_42" to -1L),
        )) {
            val vm=createViewModel(savedStateHandle=SavedStateHandle(values+mapOf("sessionId" to 42L)))
            advanceUntilIdle()
            assertEquals(24,vm.state.value.eventNodesWindowSize)
            assertNull(vm.state.value.eventNodesBeforeCreatedAt);assertNull(vm.state.value.eventNodesBeforeId)
        }
    }

    @Test fun eventWindowNewCriteriaAndSuccessfulBranchChangeClearDescriptor() = runTest(testDispatcher) {
        for (action in listOf("query","filter","branch")) {
            val handle=SavedStateHandle(mapOf("sessionId" to 42L,"event_criteria_branch_42" to "main","event_window_size_42" to 72,"event_window_before_at_42" to 77L,"event_window_before_id_42" to 77L))
            val branches=mockk<SessionBranchDao>(relaxed=true)
            coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=7))
            val vm=createViewModel(savedStateHandle=handle,sessionBranchDao=branches)
            advanceUntilIdle()
            when(action) { "query" -> vm.updateEventQuery("港口");"filter" -> vm.updateEventResolvedFilter(true);else -> vm.switchBranch("B") }
            advanceUntilIdle()
            assertNull(handle.get<Int>("event_window_size_42"));assertNull(handle.get<Long>("event_window_before_id_42"))
            assertEquals(24,vm.state.value.eventNodesWindowSize)
            assertNull(vm.state.value.eventNodesBeforeId)
        }
    }

    @Test fun eventWindowFailedAndLateLoadsDoNotPublishOldDescriptor() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val events=mockk<SessionEventNodeDao>(relaxed=true)
        val first=(100L downTo 76L).map { SessionEventNodeEntity(id=it,sessionId=42,createdAt=it) }
        coEvery { events.getPageForBranch(42,"main",null,null,25) } returns first
        coEvery { events.getPageForBranch(42,"main",77L,77L,25) } throws IllegalStateException("older failed")
        val vm=createViewModel(eventNodeDao=events,savedStateHandle=handle)
        advanceUntilIdle();vm.loadEventNodesIfNeeded();advanceUntilIdle();vm.loadMoreEventNodes();advanceUntilIdle()
        assertNull(handle.get<Int>("event_window_size_42"))
        val delayed=CompletableDeferred<List<SessionEventNodeEntity>>()
        coEvery { events.getPageForBranch(42,"main",77L,77L,25) } coAnswers { delayed.await() }
        vm.loadMoreEventNodes();runCurrent()
        vm.updateEventQuery("new");advanceUntilIdle()
        delayed.complete((76L downTo 52L).map { SessionEventNodeEntity(id=it,sessionId=42,createdAt=it) });advanceUntilIdle()
        assertEquals("new",handle.get<String>("event_query_42"))
        assertNull(handle.get<Int>("event_window_size_42"));assertNull(vm.state.value.eventNodesBeforeId)
    }

    @Test fun eventCriteriaRestoresMixedQueryAndBothFiltersThroughExistingHandle() = runTest(testDispatcher) {
        val handle = SavedStateHandle(mapOf("sessionId" to 42L))
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 901, sessionId = 42, title = "港口Alpha")
        coEvery { events.getFilteredPageForBranch(42, "main", "港口Alpha", false, null, null, 25) } returns listOf(source)
        coEvery { events.getFilteredPageForBranch(42, "main", "港口Alpha", true, null, null, 25) } returns listOf(source.copy(resolved = true))
        val first = createViewModel(eventNodeDao = events, savedStateHandle = handle)
        advanceUntilIdle();first.updateEventQuery("港口Alpha");first.updateEventResolvedFilter(false);advanceUntilIdle()
        val reopened = createViewModel(eventNodeDao = events, savedStateHandle = handle)
        advanceUntilIdle();reopened.loadEventNodesIfNeeded();advanceUntilIdle()
        assertEquals("港口Alpha", reopened.state.value.eventQuery)
        assertEquals(false, reopened.state.value.eventResolvedFilter)
        assertEquals(listOf(source), reopened.state.value.eventNodes)
        reopened.updateEventResolvedFilter(true);advanceUntilIdle()
        val resolved = createViewModel(eventNodeDao = events, savedStateHandle = handle)
        advanceUntilIdle();resolved.loadEventNodesIfNeeded();advanceUntilIdle()
        assertEquals(true, resolved.state.value.eventResolvedFilter)
        assertTrue(resolved.state.value.eventNodes.single().resolved)
        resolved.updateEventQuery( "港".repeat(240));advanceUntilIdle()
        assertEquals(200, handle.get<String>("event_query_42")!!.length)
        resolved.updateEventQuery("");resolved.updateEventResolvedFilter(null);advanceUntilIdle()
        val cleared = createViewModel(savedStateHandle = handle)
        advanceUntilIdle()
        assertEquals("", cleared.state.value.eventQuery)
        assertNull(cleared.state.value.eventResolvedFilter)
    }

    @Test fun eventCriteriaWaitsForChildReadyAndDoesNotReadTransientMain() = runTest(testDispatcher) {
        val handle = SavedStateHandle(mapOf("sessionId" to 42L, "event_criteria_branch_42" to "B", "event_query_42" to "港口Alpha", "event_resolved_42" to false))
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "B", sourceMessageId = 7))
        val repair = CompletableDeferred<Unit>()
        val visibility = mockk<BranchVisibilityIndexManager>(relaxed = true)
        coEvery { visibility.ensureReady() } coAnswers { repair.await() }
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 901, sessionId = 42, branchId = "B", title = "港口Alpha")
        coEvery { events.getFilteredPageForBranch(42, "B", "港口Alpha", false, null, null, 25) } returns listOf(source)
        val vm = createViewModel(savedStateHandle = handle, sessionBranchDao = branches, uiPreferencesRepository = uiPreferences("B"), branchVisibilityIndexManager = visibility, eventNodeDao = events)
        runCurrent();vm.loadEventNodesIfNeeded();runCurrent()
        assertFalse(vm.state.value.isReady)
        coVerify(exactly = 0) { events.getPageForBranch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { events.getFilteredPageForBranch(any(), any(), any(), any(), any(), any(), any()) }
        repair.complete(Unit);advanceUntilIdle();vm.loadEventNodesIfNeeded();advanceUntilIdle()
        assertEquals("B", vm.state.value.currentBranchId)
        assertEquals("港口Alpha", vm.state.value.eventQuery)
        assertEquals(listOf(source), vm.state.value.eventNodes)
    }

    @Test fun eventCriteriaDoesNotRestoreOtherBranchOrOtherSession() = runTest(testDispatcher) {
        for (scope in listOf("B", "main")) {
            val suffix = if (scope == "B") "42" else "43"
            val handle = SavedStateHandle(mapOf("sessionId" to 42L, "event_criteria_branch_$suffix" to scope, "event_query_$suffix" to "港口Alpha", "event_resolved_$suffix" to true))
            val vm = createViewModel(savedStateHandle = handle)
            advanceUntilIdle()
            assertEquals("", vm.state.value.eventQuery)
            assertNull(vm.state.value.eventResolvedFilter)
        }
    }

    @Test fun eventCriteriaSuccessfulSwitchSavesResetBeforeRecreation() = runTest(testDispatcher) {
        val handle = SavedStateHandle(mapOf("sessionId" to 42L))
        val branches = mockk<SessionBranchDao>(relaxed = true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "B", sourceMessageId = 7))
        val vm = createViewModel(savedStateHandle = handle, sessionBranchDao = branches)
        advanceUntilIdle();vm.updateEventQuery("港口Alpha");vm.updateEventResolvedFilter(false);advanceUntilIdle()
        vm.switchBranch("B");advanceUntilIdle()
        assertEquals("B", vm.state.value.currentBranchId)
        assertEquals("B", handle.get<String>("event_criteria_branch_42"))
        assertEquals("", handle.get<String>("event_query_42"))
        assertNull(handle.get<Boolean>("event_resolved_42"))
        val reopened = createViewModel(savedStateHandle = handle, sessionBranchDao = branches, uiPreferencesRepository = uiPreferences("B"))
        advanceUntilIdle()
        assertEquals("B", reopened.state.value.currentBranchId)
        assertEquals("", reopened.state.value.eventQuery)
        assertNull(reopened.state.value.eventResolvedFilter)
    }

    @Test fun eventFilterReadsWholeLineAndRejectsLateQueryResults() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val old = CompletableDeferred<List<SessionEventNodeEntity>>()
        val found = SessionEventNodeEntity(id = 901, sessionId = 42, title = "灯塔旧线索")
        coEvery { events.getFilteredPageForBranch(42, "main", "old", null, null, null, 25) } coAnswers { old.await() }
        coEvery { events.getFilteredPageForBranch(42, "main", "灯塔", null, null, null, 25) } returns listOf(found)
        coEvery { events.getFilteredPageForBranch(42, "main", "灯塔", true, null, null, 25) } returns listOf(found.copy(resolved = true))
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        vm.updateEventQuery("old"); runCurrent()
        vm.updateEventQuery("灯塔"); advanceUntilIdle()
        assertEquals(listOf(901L), vm.state.value.eventNodes.map { it.id })
        old.complete(listOf(found.copy(id = 902))); advanceUntilIdle()
        assertEquals("灯塔", vm.state.value.eventQuery)
        assertEquals(listOf(901L), vm.state.value.eventNodes.map { it.id })
        vm.updateEventResolvedFilter(true); advanceUntilIdle()
        assertTrue(vm.state.value.eventNodes.single().resolved)
        coVerify(exactly = 1) { events.getFilteredPageForBranch(42, "main", "灯塔", true, null, null, 25) }
    }

    @Test fun eventStatusChangeRefillsTheSameFilteredWindow() = runTest(testDispatcher) {
        val events = mockk<SessionEventNodeDao>(relaxed = true)
        val source = SessionEventNodeEntity(id = 901, sessionId = 42, title = "灯塔", resolved = false)
        coEvery { events.getFilteredPageForBranch(42, "main", "", false, null, null, 25) } returns listOf(source) andThen emptyList()
        val vm = createViewModel(eventNodeDao = events)
        advanceUntilIdle()
        vm.updateEventResolvedFilter(false); advanceUntilIdle()
        vm.toggleEventNodeResolved(901); advanceUntilIdle()
        assertEquals(false, vm.state.value.eventResolvedFilter)
        assertTrue(vm.state.value.eventNodes.isEmpty())
        coVerify(exactly = 1) { events.setResolved(901, true) }
    }

    @Test fun bookmarkSearchRejectsLateReadsAndPreservesSavedQuery() = runTest(testDispatcher) {
        val marks = mockk<BookmarkDao>(relaxed = true)
        val old = CompletableDeferred<List<MessageBookmarkEntity>>()
        val found = MessageBookmarkEntity(id = 71, sessionId = 42, messageId = 501, note = "灯塔")
        coEvery { marks.searchPage(42, "old", null, null, 41) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { old.await() }
        }
        coEvery { marks.searchPage(42, "灯塔", null, null, 41) } returns listOf(found)
        val handle = SavedStateHandle(mapOf("sessionId" to 42L))
        val vm = createViewModel(bookmarkDao = marks, savedStateHandle = handle)
        advanceUntilIdle()
        vm.updateBookmarkQuery("old"); runCurrent()
        vm.updateBookmarkQuery("灯塔"); runCurrent()
        old.complete(listOf(found.copy(id = 99))); advanceUntilIdle()
        assertEquals("灯塔", vm.state.value.bookmarkQuery)
        assertEquals(listOf(71L), vm.state.value.bookmarks.map { it.id })
        val reopened = createViewModel(bookmarkDao = marks, savedStateHandle = handle)
        advanceUntilIdle()
        assertEquals("灯塔", reopened.state.value.bookmarkQuery)
    }

    @Test fun editingSearchedBookmarkRemovesItFromResultsAfterCommit() = runTest(testDispatcher) {
        val marks = mockk<BookmarkDao>(relaxed = true)
        val found = MessageBookmarkEntity(id = 71, sessionId = 42, messageId = 501, note = "灯塔")
        coEvery { marks.searchPage(42, "灯塔", null, null, 41) } returns listOf(found) andThen emptyList()
        coEvery { marks.updateNote(42, 71, "新线索") } returns 1
        val vm = createViewModel(bookmarkDao = marks)
        advanceUntilIdle()
        vm.updateBookmarkQuery("灯塔"); advanceUntilIdle()
        vm.updateBookmarkNoteDraft(71, "新线索")
        var saved = false
        vm.saveBookmarkNote(71, "新线索") { saved = it }; advanceUntilIdle()
        assertTrue(saved)
        assertTrue(vm.state.value.bookmarks.isEmpty())
        assertEquals("灯塔", vm.state.value.bookmarkQuery)
        assertFalse(71L in vm.state.value.bookmarkNoteDrafts)
    }

    @Test
    fun characterStateReadFailureKeepsPanelAndRetryLoadsIt() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 9L))
        val states = mockk<CharacterStateDao>(relaxed = true)
        val saved = SessionCharacterStateEntity(
            sessionId = 42L, characterId = 9L, branchId = "main",
            dynamicStateJson = """{"mood":"平静"}""",
        )
        coEvery { states.getBySessionAndCharacter(42L, 9L, "main") } throws IllegalStateException() andThen saved
        val vm = createViewModel(participantDao = participants, characterStateDao = states)
        advanceUntilIdle()

        vm.openCharacterState(9L)
        advanceUntilIdle()
        assertEquals("读取角色状态失败，内容已保留，请重试", vm.state.value.characterStatePanel?.error)
        vm.openCharacterState(9L)
        advanceUntilIdle()
        assertEquals("平静", vm.state.value.characterStatePanel?.state?.mood)
    }

    @Test
    fun sameCharacterReopenRejectsLateReadFromEarlierRevision() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 9L))
        val states = mockk<CharacterStateDao>(relaxed = true)
        val old = CompletableDeferred<SessionCharacterStateEntity?>()
        val new = SessionCharacterStateEntity(sessionId = 42L, characterId = 9L, dynamicStateJson = """{"mood":"新"}""")
        var reads = 0
        coEvery { states.getBySessionAndCharacter(42L, 9L, "main") } coAnswers {
            reads++
            if (reads == 1) withContext(NonCancellable) { old.await() } else new
        }
        val vm = createViewModel(participantDao = participants, characterStateDao = states)
        advanceUntilIdle()
        vm.openCharacterState(9L); runCurrent()
        vm.openCharacterState(9L); advanceUntilIdle()
        assertEquals("新", vm.state.value.characterStatePanel?.state?.mood)
        old.complete(SessionCharacterStateEntity(sessionId = 42L, characterId = 9L, dynamicStateJson = """{"mood":"旧"}"""))
        advanceUntilIdle()
        assertEquals("新", vm.state.value.characterStatePanel?.state?.mood)
    }

    @Test
    fun clearFailureKeepsStateAndCanRetry() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 9L))
        val states = mockk<CharacterStateDao>(relaxed = true)
        val saved = SessionCharacterStateEntity(sessionId = 42L, characterId = 9L, dynamicStateJson = """{"mood":"保留"}""")
        coEvery { states.getBySessionAndCharacter(42L, 9L, "main") } returns saved andThen null
        coEvery { states.deleteBySessionCharacterBranch(42L, 9L, "main") } throws IllegalStateException() andThen 1
        val vm = createViewModel(participantDao = participants, characterStateDao = states)
        advanceUntilIdle(); vm.openCharacterState(9L); advanceUntilIdle()
        vm.clearCharacterState(); advanceUntilIdle()
        assertEquals("清除失败，内容已保留，请重试", vm.state.value.characterStatePanel?.error)
        assertEquals("保留", vm.state.value.characterStatePanel?.state?.mood)
        vm.clearCharacterState(); advanceUntilIdle()
        coVerify(exactly = 2) { states.deleteBySessionCharacterBranch(42L, 9L, "main") }
        assertEquals(null, vm.state.value.characterStatePanel?.state)
    }

    @Test
    fun clearBusyRejectsSendBeforeDraftSubmissionBegins() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 9L))
        val states = mockk<CharacterStateDao>(relaxed = true)
        coEvery { states.getBySessionAndCharacter(42L, 9L, "main") } returns SessionCharacterStateEntity(
            sessionId = 42L, characterId = 9L, dynamicStateJson = """{"mood":"清除中"}""",
        )
        val gate = CompletableDeferred<Int>()
        coEvery { states.deleteBySessionCharacterBranch(42L, 9L, "main") } coAnswers { gate.await() }
        val drafts = emptyDraftStore()
        val vm = createViewModel(participantDao = participants, characterStateDao = states, chatDraftStore = drafts)
        advanceUntilIdle(); vm.openCharacterState(9L); advanceUntilIdle()
        vm.clearCharacterState(); runCurrent()
        vm.updateInput("清除期间不应发送")
        vm.sendMessage()
        assertEquals("角色状态正在清除，请稍后再发送", vm.state.value.error)
        verify(exactly = 0) { drafts.saveBeforeSubmission(any(), any()) }
        gate.complete(1); advanceUntilIdle()
    }

    @Test
    fun characterStateReloadWaitsForClearAndReadsActualResult() = runTest(testDispatcher) {
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 9L))
        val states = mockk<CharacterStateDao>(relaxed = true)
        var stored: SessionCharacterStateEntity? = SessionCharacterStateEntity(sessionId = 42L, characterId = 9L, dynamicStateJson = """{"mood":"待清除"}""")
        coEvery { states.getBySessionAndCharacter(42L, 9L, "main") } coAnswers { stored }
        val gate = CompletableDeferred<Unit>()
        coEvery { states.deleteBySessionCharacterBranch(42L, 9L, "main") } coAnswers { gate.await(); stored = null; 1 }
        val vm = createViewModel(participantDao = participants, characterStateDao = states)
        advanceUntilIdle(); vm.openCharacterState(9L); advanceUntilIdle()
        vm.clearCharacterState(); runCurrent()
        vm.closeCharacterState(); vm.openCharacterState(9L); runCurrent()
        try {
            assertTrue(vm.state.value.characterStatePanel!!.loading)
            coVerify(exactly = 1) { states.getBySessionAndCharacter(42L, 9L, "main") }
        } finally {
            gate.complete(Unit)
        }
        advanceUntilIdle()
        assertFalse(vm.state.value.characterStatePanel!!.loading)
        assertEquals(null, vm.state.value.characterStatePanel!!.state)
        coVerify(exactly = 1) { states.deleteBySessionCharacterBranch(42L, 9L, "main") }
    }

    @Test
    fun characterStateClearRetainsOwnerThroughActualReadbackWithoutGeneration() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 9L))
        val states = mockk<CharacterStateDao>(relaxed = true)
        val original = SessionCharacterStateEntity(sessionId = 42L, characterId = 9L, dynamicStateJson = """{"mood":"待清除"}""")
        val deletion = CompletableDeferred<Unit>()
        val readback = CompletableDeferred<SessionCharacterStateEntity?>()
        var deleted = false
        coEvery { states.getBySessionAndCharacter(42L, 9L, "main") } coAnswers { if (deleted) readback.await() else original }
        coEvery { states.deleteBySessionCharacterBranch(42L, 9L, "main") } coAnswers { deletion.await(); deleted = true; 1 }
        val vm = registry.acquire(42L) { store -> createViewModel(participantDao = participants, characterStateDao = states).also { store.put("vm", it) } }
        try {
            advanceUntilIdle(); vm.openCharacterState(9L); advanceUntilIdle()
            vm.clearCharacterState(); runCurrent(); vm.clearCharacterState()
            registry.release(42L)
            assertTrue(registry.contains(42L)); assertFalse(42L in registry.running.value)
            deletion.complete(Unit); runCurrent()
            // The delete has finished and no screen is reading; the DAO readback
            // must still own the model instead of being cancelled by eviction.
            assertTrue(deleted); assertTrue(registry.contains(42L))
            assertTrue(vm.state.value.characterStatePanel!!.loading)
            val reopened = registry.acquire<ChatViewModel>(42L) { error("Lost clear readback owner") }
            assertTrue(reopened === vm); registry.release(42L)
            readback.complete(null); advanceUntilIdle()
            assertFalse(vm.state.value.characterStatePanel!!.loading)
            assertFalse(vm.state.value.characterStatePanel!!.clearing)
            assertEquals(null, vm.state.value.characterStatePanel!!.state)
            coVerify(exactly = 1) { states.deleteBySessionCharacterBranch(42L, 9L, "main") }
            assertFalse(registry.contains(42L))
        } finally {
            deletion.complete(Unit); readback.complete(null); registry.stop(42L); registry.release(42L); advanceUntilIdle()
        }
    }

    @Test
    fun characterStateClearFailureReturnsToSameOwnerAfterReopen() = runTest(testDispatcher) {
        val registry = com.mojing.app.ui.chat.RetainedChatSessions.stores
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(SessionParticipantEntity(sessionId = 42L, characterId = 9L))
        val states = mockk<CharacterStateDao>(relaxed = true)
        val original = SessionCharacterStateEntity(sessionId = 42L, characterId = 9L, dynamicStateJson = """{"mood":"保留"}""")
        coEvery { states.getBySessionAndCharacter(42L, 9L, "main") } returns original
        val deletion = CompletableDeferred<Unit>()
        coEvery { states.deleteBySessionCharacterBranch(42L, 9L, "main") } coAnswers { deletion.await(); throw IllegalStateException("disk failure") }
        val vm = registry.acquire(42L) { store -> createViewModel(participantDao = participants, characterStateDao = states).also { store.put("vm", it) } }
        try {
            advanceUntilIdle(); vm.openCharacterState(9L); advanceUntilIdle()
            vm.clearCharacterState(); runCurrent(); registry.release(42L)
            val reopened = registry.acquire<ChatViewModel>(42L) { error("Lost pending clear owner") }
            assertTrue(reopened === vm); assertFalse(42L in registry.running.value)
            deletion.complete(Unit); advanceUntilIdle()
            assertFalse(vm.state.value.characterStatePanel!!.clearing)
            assertEquals("保留", vm.state.value.characterStatePanel!!.state?.mood)
            assertEquals("清除失败，内容已保留，请重试", vm.state.value.characterStatePanel!!.error)
        } finally { deletion.complete(Unit); registry.stop(42L); registry.release(42L); advanceUntilIdle() }
    }

    @Test
    fun historyAuthorsLoadWithParticipantsWithoutJoiningFutureRounds() = runTest(testDispatcher) {
        val active = SessionParticipantEntity(id = 1L, sessionId = 42L, characterId = 7L)
        val participants = mockk<ParticipantDao>(relaxed = true)
        coEvery { participants.getBySession(42L) } returns listOf(active)
        val messages = mockk<MessageDao>(relaxed = true)
        val historical = MessageEntity(id = 2L, sessionId = 42L, speakerType = "character", characterId = 8L, content = "旧作者")
        val deleted = historical.copy(id = 3L, characterId = 9L, content = "已删除角色")
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(deleted, historical)
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getChatPresentationByIds(listOf(7L, 8L, 9L)) } returns listOf(
            ChatCharacterPresentationRow(7L, "现参与者", "active-color", "active-avatar", "", false),
            ChatCharacterPresentationRow(8L, "旧作者", "old-color", "old-avatar", "old-card", true),
        )
        val vm = createViewModel(messageDao = messages, characterDao = characters, participantDao = participants)
        advanceUntilIdle()
        assertEquals(listOf(active), vm.state.value.participants)
        assertEquals(mapOf(7L to "现参与者", 8L to "旧作者"), vm.state.value.characterNames)
        assertEquals("old-avatar", vm.state.value.characterAvatars[8L])
        assertEquals("old-card", vm.state.value.characterCardImages[8L])
        assertEquals(null, vm.state.value.characterNames[9L])
        assertFalse(vm.state.value.characterForcesThinkMax)
        coVerify(exactly = 0) { characters.getById(any()) }
        coVerify(exactly = 0) { participants.upsert(any()) }
    }

    @Test
    fun removingParticipantKeepsLoadedHistoryIdentity() = runTest(testDispatcher) {
        val participant = SessionParticipantEntity(id = 9L, sessionId = 42L, characterId = 7L)
        val participants = mockk<ParticipantDao>(relaxed = true)
        var removed = false
        coEvery { participants.getBySession(42L) } answers { if (removed) emptyList() else listOf(participant) }
        coEvery { participants.getById(9L) } returns participant
        coEvery { participants.delete(9L) } answers { removed = true }
        val messages = mockk<MessageDao>(relaxed = true)
        val original = MessageEntity(id = 1L, sessionId = 42L, speakerType = "character", characterId = 7L, content = "旧发言")
        coEvery { messages.getMainMessagesTail(42L, 81) } returns listOf(original)
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getChatPresentationByIds(listOf(7L)) } returns listOf(
            ChatCharacterPresentationRow(7L, "青鸾", "color", "avatar", "card", false),
        )
        val vm = createViewModel(messageDao = messages, characterDao = characters, participantDao = participants)
        advanceUntilIdle()
        vm.setManualReplyCharacterId(7L)
        vm.removeParticipant(9L)
        advanceUntilIdle()
        assertTrue(vm.state.value.participants.isEmpty())
        assertEquals(null, vm.state.value.manualReplyCharacterId)
        assertEquals("青鸾", vm.state.value.characterNames[7L])
        assertEquals("avatar", vm.state.value.characterAvatars[7L])
        assertEquals(listOf(original), vm.state.value.messages)
        coVerify(exactly = 0) { characters.delete(any()) }
        coVerify(exactly = 0) { messages.delete(any()) }
    }

    @Test
    fun olderHistoryLoadsItsAuthorsAndLatestWindowPrunesThem() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val initial = (200L downTo 120L).map { MessageEntity(id = it, sessionId = 42L, content = "本页$it") }
        val old = MessageEntity(id = 119L, sessionId = 42L, speakerType = "character", characterId = 8L, content = "旧作者发言")
        coEvery { messages.getMainMessagesTail(42L, 81) } returns initial
        coEvery { messages.getMainMessagesBefore(42L, 121L, 41) } returns listOf(old)
        val characters = mockk<CharacterDao>(relaxed = true)
        coEvery { characters.getChatPresentationByIds(listOf(8L)) } returns listOf(
            ChatCharacterPresentationRow(8L, "旧作者", "color", "avatar", "card", false),
        )
        val vm = createViewModel(messageDao = messages, characterDao = characters)
        advanceUntilIdle()
        assertTrue(vm.state.value.characterNames.isEmpty())
        vm.loadOlderMessages()
        advanceUntilIdle()
        assertEquals("旧作者", vm.state.value.characterNames[8L])
        assertTrue(vm.state.value.messages.contains(old))
        assertTrue(vm.returnToLatestMessages())
        advanceUntilIdle()
        assertFalse(vm.state.value.messages.contains(old))
        assertTrue(vm.state.value.characterNames.isEmpty())
        coVerify(exactly = 0) { messages.getMainBranchMessages(any()) }
        coVerify(exactly = 0) { characters.getById(any()) }
    }

    @Test
    fun delayedCharacterMetaCannotDropAuthorsFromNewHistoryWindow() = runTest(testDispatcher) {
        val messages = mockk<MessageDao>(relaxed = true)
        val first = MessageEntity(id = 200L, sessionId = 42L, speakerType = "character", characterId = 7L, content = "近页作者")
        val initial = listOf(first) + (199L downTo 120L).map { MessageEntity(id = it, sessionId = 42L, content = "本页$it") }
        val old = first.copy(id = 119L, characterId = 8L, content = "更早作者")
        coEvery { messages.getMainMessagesTail(42L, 81) } returns initial
        coEvery { messages.getMainMessagesBefore(42L, 121L, 41) } returns listOf(old)
        val characters = mockk<CharacterDao>(relaxed = true)
        val late = CompletableDeferred<Unit>()
        var calls = 0
        val firstRow = ChatCharacterPresentationRow(7L, "近页作者", "color", "avatar", "", false)
        val oldRow = firstRow.copy(id = 8L, name = "更早作者")
        coEvery { characters.getChatPresentationByIds(listOf(7L)) } coAnswers {
            calls++
            if (calls == 2) late.await()
            listOf(firstRow)
        }
        coEvery { characters.getChatPresentationByIds(listOf(8L, 7L)) } returns listOf(firstRow, oldRow)
        val vm = createViewModel(messageDao = messages, characterDao = characters)
        advanceUntilIdle()
        vm.refreshParticipantCharacterMeta()
        runCurrent()
        assertEquals(2, calls)
        vm.loadOlderMessages()
        advanceUntilIdle()
        assertEquals("更早作者", vm.state.value.characterNames[8L])
        late.complete(Unit)
        advanceUntilIdle()
        assertEquals("更早作者", vm.state.value.characterNames[8L])
        assertTrue(vm.state.value.messages.contains(old))
    }

    @Test fun summaryEditReceiptWaitsForWriteAndRejectsDoubleSave() = runTest(testDispatcher) {
        val maintenance = mockk<SummaryMaintenanceUseCase>()
        val release = CompletableDeferred<Unit>()
        coEvery { maintenance.edit(42L, "main", 8L, "旧摘要", "新摘要") } coAnswers {
            release.await()
            com.mojing.app.domain.engine.SummaryMaintenanceResult.Updated
        }
        val vm = createViewModel(summaryMaintenance = maintenance)
        advanceUntilIdle()
        val segment = com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=8,sessionId=42,summary="旧摘要")
        var done = 0
        vm.editMemorySummary(segment,"新摘要") { ok, _ -> assertTrue(ok); done++ }
        runCurrent()
        assertTrue(vm.state.value.memoryOperationRunning)
        assertNull(vm.state.value.memorySummaryEditSavedId)
        vm.editMemorySummary(segment,"新摘要") { _, _ -> error("duplicate callback") }
        release.complete(Unit); advanceUntilIdle()
        assertEquals(1,done)
        assertFalse(vm.state.value.memoryOperationRunning)
        assertEquals(8L,vm.state.value.memorySummaryEditSavedId)
        assertEquals("新摘要",vm.state.value.memorySummaryEditSavedText)
        coVerify(exactly=1) { maintenance.edit(any(),any(),any(),any(),any()) }
    }

    @Test fun summaryEditConflictKeepsDraftRetryWithoutSuccessReceipt() = runTest(testDispatcher) {
        val maintenance=mockk<SummaryMaintenanceUseCase>()
        coEvery { maintenance.edit(any(),any(),any(),any(),any()) } returns
            com.mojing.app.domain.engine.SummaryMaintenanceResult.Conflict
        val vm=createViewModel(summaryMaintenance=maintenance);advanceUntilIdle()
        val segment=com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=8,sessionId=42,summary="旧摘要")
        var result:Boolean?=null
        vm.editMemorySummary(segment,"新摘要") { ok,_->result=ok };advanceUntilIdle()
        assertEquals(false,result);assertFalse(vm.state.value.memoryOperationRunning)
        assertNull(vm.state.value.memorySummaryEditSavedId)
        coEvery { maintenance.edit(any(),any(),any(),any(),any()) } returns
            com.mojing.app.domain.engine.SummaryMaintenanceResult.Updated
        vm.editMemorySummary(segment,"新摘要") { ok,_->result=ok };advanceUntilIdle()
        assertEquals(true,result);assertEquals(8L,vm.state.value.memorySummaryEditSavedId)
    }

    @Test fun summaryEditorResolvesOneVisibleOwnedIdWithoutScanningPages() = runTest(testDispatcher) {
        val dao=mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed=true)
        val original=com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=8,sessionId=42,summary="较早摘要")
        coEvery { dao.getById(8L) } returns original
        val vm=createViewModel(memorySegmentDao=dao);advanceUntilIdle()
        assertEquals(original,vm.resolveMemorySummaryEditor(8L,"main"))
        assertNull(vm.resolveMemorySummaryEditor(8L,"other"))
        coEvery { dao.getById(8L) } returns original.copy(branchId="parent")
        assertNull(vm.resolveMemorySummaryEditor(8L,"main"))
        coVerify(exactly=2) { dao.getById(8L) }
        coVerify(exactly=0) { dao.getVisibleById(any(),any(),any()) }
        coVerify(exactly=0) { dao.getBySessionAndBranch(any(),any()) }
        coVerify(exactly=0) { dao.getOlderForBranch(any(),any(),any(),any(),any()) }
    }

    @Test fun summaryEditCommittedWriteSurvivesListRefreshFailure() = runTest(testDispatcher) {
        val dao=mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed=true)
        val maintenance=mockk<SummaryMaintenanceUseCase>()
        val segment=com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=8,sessionId=42,summary="旧摘要")
        coEvery { dao.getRecentForBranch(42L,"main",any()) } returns listOf(segment)
        coEvery { maintenance.edit(any(),any(),any(),any(),any()) } returns
            com.mojing.app.domain.engine.SummaryMaintenanceResult.Updated
        val vm=createViewModel(memorySegmentDao=dao,summaryMaintenance=maintenance);advanceUntilIdle()
        vm.loadMemorySummariesIfNeeded();advanceUntilIdle();assertTrue(vm.state.value.memorySegmentsLoaded)
        coEvery { dao.getRecentForBranch(42L,"main",any()) } throws IllegalStateException("synthetic read failure")
        var success:Boolean?=null
        var message=""
        vm.editMemorySummary(segment,"新摘要") { ok,text->success=ok;message=text };advanceUntilIdle()
        assertEquals(true,success);assertTrue(message.contains("已保存"))
        assertEquals(8L,vm.state.value.memorySummaryEditSavedId)
        assertNotNull(vm.state.value.memorySegmentsLoadError)
        assertFalse(vm.state.value.memoryOperationRunning)
        coVerify(exactly=1) { maintenance.edit(any(),any(),any(),any(),any()) }
    }

    @Test fun summaryWindowRecreationRestoresBoundedCursorAndSameLineRefresh() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val segments=mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed=true)
        fun rows(before:Long,limit:Int)=(before-1 downTo (before-limit).coerceAtLeast(1L)).map {
            com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=it,sessionId=42,endMessageId=it,summary="摘要$it",createdAt=it)
        }
        coEvery { segments.getRecentForBranch(42,"main",any()) } coAnswers { rows(101L,arg(2)) }
        coEvery { segments.getOlderForBranch(42,"main",any(),any(),any()) } coAnswers { rows(arg(2),arg(4)) }
        val vm=createViewModel(memorySegmentDao=segments,savedStateHandle=handle)
        advanceUntilIdle();vm.loadMemorySummariesIfNeeded();advanceUntilIdle()
        repeat(5) { vm.loadMoreMemorySummaries();advanceUntilIdle() }
        assertEquals((84L downTo 5L).toList(),vm.state.value.memorySegments.map { it.id })
        assertEquals(80,handle.get<Int>("summary_window_size_42"));assertEquals(85L,handle.get<Long>("summary_window_before_id_42"))
        vm.switchBranch("main");advanceUntilIdle()
        assertEquals(80,vm.state.value.memorySegmentsWindowSize)
        val reopened=createViewModel(memorySegmentDao=segments,savedStateHandle=handle)
        advanceUntilIdle();reopened.loadMemorySummariesIfNeeded();advanceUntilIdle()
        assertEquals(vm.state.value.memorySegments,reopened.state.value.memorySegments)
        coVerify(atLeast=1) { segments.getOlderForBranch(42,"main",85L,85L,81) }
        reopened.resetMemorySummaryWindow();advanceUntilIdle()
        assertEquals((100L downTo 85L).toList(),reopened.state.value.memorySegments.map { it.id })
        assertNull(handle.get<Long>("summary_window_before_id_42"))
        val reset=createViewModel(memorySegmentDao=segments,savedStateHandle=handle)
        advanceUntilIdle();reset.loadMemorySummariesIfNeeded();advanceUntilIdle()
        assertEquals(16,reset.state.value.memorySegmentsWindowSize)
    }

    @Test fun summaryWindowChildWaitsForReadyAndRetriesSameCursor() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L,"summary_window_branch_42" to "B","summary_window_size_42" to 80,"summary_window_before_end_42" to 85L,"summary_window_before_id_42" to 85L))
        val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=7))
        val ready=CompletableDeferred<Unit>();val visibility=mockk<BranchVisibilityIndexManager>(relaxed=true)
        coEvery { visibility.ensureReady() } coAnswers { ready.await() }
        val segments=mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed=true)
        val row=com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=10,sessionId=42,branchId="B",endMessageId=10)
        coEvery { segments.getOlderForBranch(42,"B",85L,85L,81) } throws IllegalStateException("read failed") andThen listOf(row)
        val vm=createViewModel(memorySegmentDao=segments,savedStateHandle=handle,sessionBranchDao=branches,uiPreferencesRepository=uiPreferences("B"),branchVisibilityIndexManager=visibility)
        runCurrent();vm.loadMemorySummariesIfNeeded();runCurrent()
        coVerify(exactly=0) { segments.getRecentForBranch(any(),any(),any()) }
        coVerify(exactly=0) { segments.getOlderForBranch(any(),any(),any(),any(),any()) }
        ready.complete(Unit);advanceUntilIdle();vm.loadMemorySummariesIfNeeded();advanceUntilIdle()
        assertFalse(vm.state.value.memorySegmentsLoaded);assertEquals(85L,vm.state.value.memorySegmentsBeforeId)
        vm.loadMoreMemorySummaries();advanceUntilIdle()
        assertEquals(listOf(row),vm.state.value.memorySegments)
        coVerify(exactly=2) { segments.getOlderForBranch(42,"B",85L,85L,81) }
    }

    @Test fun summaryWindowRejectsOtherScopeOversizedAndUnpairedCursor() = runTest(testDispatcher) {
        for(values in listOf(
            mapOf("summary_window_branch_42" to "B","summary_window_size_42" to 80),
            mapOf("summary_window_branch_43" to "main","summary_window_size_43" to 80),
            mapOf("summary_window_branch_42" to "main","summary_window_size_42" to 999),
            mapOf("summary_window_branch_42" to "main","summary_window_size_42" to 80,"summary_window_before_id_42" to 85L),
            mapOf("summary_window_branch_42" to "main","summary_window_size_42" to 80,"summary_window_before_end_42" to 85L,"summary_window_before_id_42" to -1L)
        )) {
            val vm=createViewModel(savedStateHandle=SavedStateHandle(values+mapOf("sessionId" to 42L)))
            advanceUntilIdle();assertEquals(16,vm.state.value.memorySegmentsWindowSize)
            assertNull(vm.state.value.memorySegmentsBeforeId);assertNull(vm.state.value.memorySegmentsBeforeEndId)
        }
    }

    @Test fun summaryWindowFailedAndLateLoadsDoNotPublishOldDescriptor() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val segments=mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed=true)
        val first=(100L downTo 84L).map { com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=it,sessionId=42,endMessageId=it) }
        coEvery { segments.getRecentForBranch(42,"main",17) } returns first
        coEvery { segments.getOlderForBranch(42,"main",85L,85L,17) } throws IllegalStateException("older failed")
        val vm=createViewModel(memorySegmentDao=segments,savedStateHandle=handle)
        advanceUntilIdle();vm.loadMemorySummariesIfNeeded();advanceUntilIdle();vm.loadMoreMemorySummaries();advanceUntilIdle()
        assertEquals(first.take(16),vm.state.value.memorySegments);assertNull(handle.get<Int>("summary_window_size_42"))
        val delayed=CompletableDeferred<List<com.mojing.app.data.local.entity.SessionMemorySegmentEntity>>()
        coEvery { segments.getOlderForBranch(42,"main",85L,85L,17) } coAnswers { delayed.await() }
        vm.loadMoreMemorySummaries();runCurrent();vm.resetMemorySummaryWindow();runCurrent()
        delayed.complete((84L downTo 68L).map { com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=it,sessionId=42,endMessageId=it) });advanceUntilIdle()
        assertEquals(first.take(16),vm.state.value.memorySegments)
        assertEquals(16,handle.get<Int>("summary_window_size_42"));assertNull(handle.get<Long>("summary_window_before_id_42"))
    }

    @Test fun summaryWindowSuccessfulBranchChangeResetsDescriptor() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L,"summary_window_branch_42" to "main","summary_window_size_42" to 80,"summary_window_before_end_42" to 85L,"summary_window_before_id_42" to 85L))
        val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=7))
        val vm=createViewModel(savedStateHandle=handle,sessionBranchDao=branches)
        advanceUntilIdle();vm.switchBranch("B");advanceUntilIdle()
        assertEquals("B",handle.get<String>("summary_window_branch_42"));assertEquals(16,handle.get<Int>("summary_window_size_42"))
        assertNull(handle.get<Long>("summary_window_before_id_42"));assertFalse(vm.state.value.memorySegmentsLoaded)
    }

    @Test fun summaryWindowPageEdgesStayBoundedAndUseLookahead() = runTest(testDispatcher) {
        for(size in listOf(0,1,15,16,17)) {
            val segments=mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed=true)
            val rows=(size downTo 1).map { com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=it.toLong(),sessionId=42,endMessageId=it.toLong()) }
            coEvery { segments.getRecentForBranch(42,"main",17) } returns rows
            val vm=createViewModel(memorySegmentDao=segments);advanceUntilIdle();vm.loadMemorySummariesIfNeeded();advanceUntilIdle()
            assertEquals(rows.take(16),vm.state.value.memorySegments);assertEquals(size>16,vm.state.value.memorySegmentsHasMore)
        }
    }

    @Test fun summaryWindowEditFailureRetryAndDeleteRefreshKeepSameCursor() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L,"summary_window_branch_42" to "main","summary_window_size_42" to 80,"summary_window_before_end_42" to 85L,"summary_window_before_id_42" to 85L))
        val segments=mockk<com.mojing.app.data.local.dao.SessionMemorySegmentDao>(relaxed=true)
        val maintenance=mockk<SummaryMaintenanceUseCase>()
        val original=com.mojing.app.data.local.entity.SessionMemorySegmentEntity(id=10,sessionId=42,endMessageId=10,summary="原摘要",createdAt=1L)
        val earlier=original.copy(id=9,endMessageId=9)
        coEvery { segments.getOlderForBranch(42,"main",85L,85L,81) } returns listOf(original,earlier)
        coEvery { maintenance.edit(any(),any(),any(),any(),any()) } returns com.mojing.app.domain.engine.SummaryMaintenanceResult.Updated
        coEvery { maintenance.delete(any(),any(),any(),any()) } returns com.mojing.app.domain.engine.SummaryMaintenanceResult.Deleted
        val vm=createViewModel(memorySegmentDao=segments,summaryMaintenance=maintenance,savedStateHandle=handle)
        advanceUntilIdle();vm.loadMemorySummariesIfNeeded();advanceUntilIdle()
        coEvery { segments.getOlderForBranch(42,"main",85L,85L,81) } throws IllegalStateException("refresh failed")
        vm.editMemorySummary(original,"已编辑");advanceUntilIdle()
        assertEquals(listOf(original,earlier),vm.state.value.memorySegments)
        assertNotNull(vm.state.value.memorySegmentsLoadError)
        coEvery { segments.getOlderForBranch(42,"main",85L,85L,81) } returns listOf(original.copy(summary="已编辑"),earlier)
        vm.loadMoreMemorySummaries();advanceUntilIdle()
        assertEquals("已编辑",vm.state.value.memorySegments.first().summary);assertNull(vm.state.value.memorySegmentsLoadError)
        coEvery { segments.getOlderForBranch(42,"main",85L,85L,81) } returns listOf(earlier)
        vm.deleteMemorySummary(original.copy(summary="已编辑"));advanceUntilIdle()
        assertEquals(listOf(earlier),vm.state.value.memorySegments)
        assertEquals(80,vm.state.value.memorySegmentsWindowSize);assertEquals(85L,vm.state.value.memorySegmentsBeforeId)
        assertEquals(85L,handle.get<Long>("summary_window_before_id_42"))
        coVerify(exactly=4) { segments.getOlderForBranch(42,"main",85L,85L,81) }
        coVerify(exactly=0) { segments.getRecentForBranch(any(),any(),any()) }
    }

    private fun bookmarkWindowDao(): BookmarkDao = mockk<BookmarkDao>(relaxed=true).also { dao ->
        fun rows(before:Long,limit:Int)=(before-1 downTo (before-limit).coerceAtLeast(1L)).map {
            MessageBookmarkEntity(id=it,sessionId=42,messageId=it,note="线索$it",createdAt=it)
        }
        coEvery { dao.getFirstPage(42,any()) } coAnswers { rows(221L,arg(1)) }
        coEvery { dao.getBefore(42,any(),any(),any()) } coAnswers { rows(arg(1),arg(3)) }
        coEvery { dao.searchPage(42,any(),any(),any(),any()) } coAnswers { rows(arg<Long?>(2) ?: 221L,arg(4)) }
    }

    @Test fun bookmarkDeepWindowRestoresAndResets() = runTest(testDispatcher) {
        val dao=bookmarkWindowDao();val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val vm=createViewModel(bookmarkDao=dao,savedStateHandle=handle)
        advanceUntilIdle();vm.loadBookmarksIfNeeded();advanceUntilIdle()
        repeat(4) { vm.loadMoreBookmarks();advanceUntilIdle() }
        assertEquals((140L downTo 21L).toList(),vm.state.value.bookmarks.map { it.id })
        assertEquals(120,handle.get<Int>("bookmark_window_size_42"));assertEquals(141L,handle.get<Long>("bookmark_window_before_id_42"))
        vm.switchBranch("main");advanceUntilIdle()
        assertEquals(120,vm.state.value.bookmarksWindowSize)
        val reopened=createViewModel(bookmarkDao=dao,savedStateHandle=handle)
        advanceUntilIdle();reopened.loadBookmarksIfNeeded();advanceUntilIdle()
        assertEquals(vm.state.value.bookmarks,reopened.state.value.bookmarks)
        coVerify(atLeast=1) { dao.getBefore(42,141L,141L,121) }
        reopened.resetBookmarkWindow();advanceUntilIdle()
        assertEquals((220L downTo 181L).toList(),reopened.state.value.bookmarks.map { it.id })
        assertNull(handle.get<Long>("bookmark_window_before_id_42"))
        val reset=createViewModel(bookmarkDao=dao,savedStateHandle=handle)
        advanceUntilIdle();reset.loadBookmarksIfNeeded();advanceUntilIdle()
        assertEquals(40,reset.state.value.bookmarksWindowSize)
    }

    @Test fun bookmarkWindowQueryRestoresAndNewQueryResets() = runTest(testDispatcher) {
        val dao=bookmarkWindowDao();val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val vm=createViewModel(bookmarkDao=dao,savedStateHandle=handle);advanceUntilIdle()
        vm.updateBookmarkQuery("线索");advanceUntilIdle()
        repeat(4) { vm.loadMoreBookmarks();advanceUntilIdle() }
        val reopened=createViewModel(bookmarkDao=dao,savedStateHandle=handle)
        advanceUntilIdle();reopened.loadBookmarksIfNeeded();advanceUntilIdle()
        assertEquals("线索",reopened.state.value.bookmarkQuery)
        assertEquals(vm.state.value.bookmarks,reopened.state.value.bookmarks)
        coVerify(atLeast=1) { dao.searchPage(42,"线索",141L,141L,121) }
        reopened.updateBookmarkQuery("别的");advanceUntilIdle()
        assertEquals(40,reopened.state.value.bookmarksWindowSize);assertNull(reopened.state.value.bookmarksBeforeId)
        assertEquals("别的",handle.get<String>("bookmark_window_query_42"))
    }

    @Test fun bookmarkWindowRejectsInvalidDescriptorsAndOtherSession() = runTest(testDispatcher) {
        for(values in listOf(
            mapOf("bookmark_window_query_42" to "other","bookmark_window_size_42" to 120),
            mapOf("bookmark_window_query_42" to "","bookmark_window_size_42" to 121),
            mapOf("bookmark_window_query_42" to "","bookmark_window_size_42" to 120,"bookmark_window_before_id_42" to 9L),
            mapOf("bookmark_window_query_42" to "","bookmark_window_size_42" to 120,"bookmark_window_before_id_42" to 0L,"bookmark_window_before_at_42" to 8L),
            mapOf("bookmark_window_query_43" to "","bookmark_window_size_43" to 120)
        )) {
            val vm=createViewModel(savedStateHandle=SavedStateHandle(values+mapOf("sessionId" to 42L)));advanceUntilIdle()
            assertEquals(40,vm.state.value.bookmarksWindowSize);assertNull(vm.state.value.bookmarksBeforeId)
        }
    }

    @Test fun bookmarkWindowFailedRestoreRetriesSameCursorAndCapacity() = runTest(testDispatcher) {
        val handle=SavedStateHandle(mapOf("sessionId" to 42L,"bookmark_window_query_42" to "","bookmark_window_size_42" to 120,"bookmark_window_before_at_42" to 141L,"bookmark_window_before_id_42" to 141L))
        val dao=bookmarkWindowDao()
        coEvery { dao.getBefore(42,141L,141L,121) } throws IllegalStateException("disk")
        val vm=createViewModel(bookmarkDao=dao,savedStateHandle=handle);advanceUntilIdle();vm.loadBookmarksIfNeeded();advanceUntilIdle()
        assertFalse(vm.state.value.bookmarksLoaded);assertNotNull(vm.state.value.bookmarksLoadError)
        assertEquals(141L,handle.get<Long>("bookmark_window_before_id_42"))
        coEvery { dao.getBefore(42,141L,141L,121) } returns (140L downTo 20L).map { MessageBookmarkEntity(id=it,sessionId=42,messageId=it,createdAt=it) }
        vm.loadMoreBookmarks();advanceUntilIdle()
        assertEquals(120,vm.state.value.bookmarks.size);assertNull(vm.state.value.bookmarksLoadError)
        coVerify(exactly=2) { dao.getBefore(42,141L,141L,121) }
    }

    @Test fun bookmarkWindowLateAppendCannotOverwriteResetDescriptor() = runTest(testDispatcher) {
        val dao=bookmarkWindowDao();val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val vm=createViewModel(bookmarkDao=dao,savedStateHandle=handle);advanceUntilIdle();vm.loadBookmarksIfNeeded();advanceUntilIdle()
        repeat(3) { vm.loadMoreBookmarks();advanceUntilIdle() }
        val gate=CompletableDeferred<List<MessageBookmarkEntity>>()
        coEvery { dao.getBefore(42,61L,61L,41) } coAnswers { gate.await() }
        vm.loadMoreBookmarks();runCurrent();vm.resetBookmarkWindow();runCurrent()
        gate.complete((60L downTo 20L).map { MessageBookmarkEntity(id=it,sessionId=42,messageId=it,createdAt=it) });advanceUntilIdle()
        assertEquals(40,handle.get<Int>("bookmark_window_size_42"));assertNull(handle.get<Long>("bookmark_window_before_id_42"))
        assertEquals((220L downTo 181L).toList(),vm.state.value.bookmarks.map { it.id })
    }

    @Test fun bookmarkWindowNoteRefreshFailureRetainsWindowAndDeleteRefills() = runTest(testDispatcher) {
        val dao=bookmarkWindowDao();val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val vm=createViewModel(bookmarkDao=dao,savedStateHandle=handle);advanceUntilIdle();vm.loadBookmarksIfNeeded();advanceUntilIdle()
        repeat(4) { vm.loadMoreBookmarks();advanceUntilIdle() }
        coEvery { dao.updateNote(42,30L,"新线索") } returns 1
        coEvery { dao.getBefore(42,141L,141L,121) } throws IllegalStateException("read")
        var saved=false;vm.saveBookmarkNote(30L,"新线索") { saved=it };advanceUntilIdle()
        assertTrue(saved);assertTrue(vm.state.value.bookmarksRefreshFailed)
        assertEquals(120,vm.state.value.bookmarksWindowSize);assertEquals("新线索",vm.state.value.bookmarks.single { it.id==30L }.note)
        coEvery { dao.getBefore(42,141L,141L,121) } returns (140L downTo 20L).map { MessageBookmarkEntity(id=it,sessionId=42,messageId=it,note=if(it==30L) "新线索" else "线索$it",createdAt=it) }
        vm.loadMoreBookmarks();advanceUntilIdle();assertFalse(vm.state.value.bookmarksRefreshFailed)
        coEvery { dao.getByMessageId(30L) } returns vm.state.value.bookmarks.single { it.id==30L }
        coEvery { dao.getBefore(42,141L,141L,121) } returns (140L downTo 19L).filter { it!=30L }.map { MessageBookmarkEntity(id=it,sessionId=42,messageId=it,note="线索$it",createdAt=it) }
        vm.removeBookmark(30L);advanceUntilIdle()
        assertEquals(120,vm.state.value.bookmarks.size);assertFalse(vm.state.value.bookmarks.any { it.id==30L })
        assertEquals(141L,vm.state.value.bookmarksBeforeId)
    }
    @Test fun bookmarkWindowChildReadyUsesSessionScopeAcrossActualSwitch() = runTest(testDispatcher) {
        val dao=bookmarkWindowDao();val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=7))
        val ready=CompletableDeferred<Unit>();val visibility=mockk<BranchVisibilityIndexManager>(relaxed=true)
        coEvery { visibility.ensureReady() } coAnswers { ready.await() }
        val handle=SavedStateHandle(mapOf("sessionId" to 42L,"bookmark_window_query_42" to "","bookmark_window_size_42" to 120,"bookmark_window_before_at_42" to 141L,"bookmark_window_before_id_42" to 141L))
        val vm=createViewModel(bookmarkDao=dao,savedStateHandle=handle,sessionBranchDao=branches,uiPreferencesRepository=uiPreferences("B"),branchVisibilityIndexManager=visibility)
        runCurrent();vm.loadBookmarksIfNeeded();runCurrent()
        coVerify(exactly=0) { dao.getBefore(any(),any(),any(),any()) }
        ready.complete(Unit);advanceUntilIdle();vm.loadBookmarksIfNeeded();advanceUntilIdle()
        assertEquals("B",vm.state.value.currentBranchId);assertEquals(120,vm.state.value.bookmarks.size)
        val window=vm.state.value.bookmarks;vm.switchBranch("main");advanceUntilIdle()
        assertEquals("main",vm.state.value.currentBranchId);assertEquals(window,vm.state.value.bookmarks)
        assertEquals(141L,handle.get<Long>("bookmark_window_before_id_42"))
    }

    @Test fun bookmarkWindowPageBoundariesAndSameTimestampCursor() = runTest(testDispatcher) {
        for(count in listOf(0,1,39,40,41)) {
            val dao=mockk<BookmarkDao>(relaxed=true)
            val rows=(count.toLong() downTo 1L).map { MessageBookmarkEntity(id=it,sessionId=42,messageId=it,createdAt=1000L) }
            coEvery { dao.getFirstPage(42,41) } returns rows
            if(count==41) coEvery { dao.getBefore(42,1000L,2L,41) } returns rows.takeLast(1)
            val vm=createViewModel(bookmarkDao=dao);advanceUntilIdle();vm.loadBookmarksIfNeeded();advanceUntilIdle()
            assertEquals(count.coerceAtMost(40),vm.state.value.bookmarks.size);assertEquals(count>40,vm.state.value.bookmarksHasMore)
            if(count==41) { vm.loadMoreBookmarks();advanceUntilIdle();assertEquals(41,vm.state.value.bookmarks.size);coVerify(exactly=1) { dao.getBefore(42,1000L,2L,41) } }
        }
    }

    private fun bookmarkReaderHandle(id: Long = 500, branch: String = "main") = SavedStateHandle(mapOf(
        "sessionId" to 42L, "bookmark_reader_id_42" to id, "bookmark_reader_branch_42" to branch))

    @Test fun bookmarkReaderRestoresOnlyIdsWithoutNavigationOrAdoption() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true)
        val row=MessageEntity(id=500,sessionId=42,branchId="source",content="长原文".repeat(2000))
        coEvery { dao.getByIdInSession(500,42) } returns row
        val handle=bookmarkReaderHandle();val prefs=uiPreferences()
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao,uiPreferencesRepository=prefs)
        advanceUntilIdle()
        assertEquals(row,vm.state.value.bookmarkReadOnlyMessage)
        assertEquals("main",vm.state.value.currentBranchId)
        assertNull(vm.state.value.focusedMessageId)
        assertTrue(handle.keys().filter { it.startsWith("bookmark_reader_") }.all { handle.get<Any>(it) is Long || handle.get<Any>(it) is String })
        coVerify(exactly=0) { dao.selectSwipeVariantForBranch(any(),any(),any(),any()) }
        coVerify(exactly=0) { prefs.setLastChatBranch(any(),any()) }
    }
    @Test fun bookmarkReaderWaitsForRealChildScopeAndDropsWrongScope() = runTest(testDispatcher) {
        val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=1))
        val prefs=uiPreferences();coEvery { prefs.getLastChatBranch(42) } returns "B"
        val dao=mockk<MessageDao>(relaxed=true);val row=MessageEntity(id=500,sessionId=42,content="来自主线")
        coEvery { dao.getByIdInSession(500,42) } returns row
        val vm=createViewModel(savedStateHandle=bookmarkReaderHandle(branch="B"),messageDao=dao,sessionBranchDao=branches,uiPreferencesRepository=prefs)
        advanceUntilIdle();assertEquals("B",vm.state.value.currentBranchId);assertEquals(row,vm.state.value.bookmarkReadOnlyMessage)
        val wrong=bookmarkReaderHandle(branch="main")
        val vm2=createViewModel(savedStateHandle=wrong,messageDao=dao,sessionBranchDao=branches,uiPreferencesRepository=prefs)
        advanceUntilIdle();assertNull(vm2.state.value.bookmarkReadOnlyId);assertFalse(wrong.contains("bookmark_reader_id_42"))
        coVerify(exactly=1) { dao.getByIdInSession(500,42) }
    }
    @Test fun bookmarkReaderDelayedReadCanCloseAndNeverReopen() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true);val gate=CompletableDeferred<MessageEntity?>()
        coEvery { dao.getByIdInSession(500,42) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val handle=bookmarkReaderHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao)
        runCurrent();assertTrue(vm.state.value.isReady);assertTrue(vm.state.value.bookmarkReadOnlyLoading)
        vm.closeBookmarkedReadOnlyMessage();assertNull(vm.state.value.bookmarkReadOnlyId);assertNull(vm.state.value.bookmarkLocatingId)
        gate.complete(MessageEntity(id=500,sessionId=42,content="晚到"));advanceUntilIdle()
        assertNull(vm.state.value.bookmarkReadOnlyMessage);assertFalse(handle.contains("bookmark_reader_id_42"))
        val next=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle();assertNull(next.state.value.bookmarkReadOnlyId)
    }
    @Test fun bookmarkReaderFailedReadRetainsTargetAndRetries() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true);coEvery { dao.getByIdInSession(500,42) } throws IllegalStateException("synthetic")
        val handle=bookmarkReaderHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao)
        advanceUntilIdle();assertEquals(500L,vm.state.value.bookmarkReadOnlyId);assertNotNull(vm.state.value.bookmarkReadOnlyError);assertFalse(vm.state.value.bookmarkReadOnlyLoading)
        val row=MessageEntity(id=500,sessionId=42,content="恢复原文");coEvery { dao.getByIdInSession(500,42) } returns row
        vm.retryBookmarkedReadOnlyMessage();advanceUntilIdle();assertEquals(row,vm.state.value.bookmarkReadOnlyMessage);assertNull(vm.state.value.bookmarkReadOnlyError)
    }
    @Test fun bookmarkReaderMissingOrWrongSessionClearsIntent() = runTest(testDispatcher) {
        listOf(null,MessageEntity(id=500,sessionId=43,content="其他会话")).forEach { row ->
            val dao=mockk<MessageDao>(relaxed=true);coEvery { dao.getByIdInSession(500,42) } returns row
            val handle=bookmarkReaderHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao)
            advanceUntilIdle();assertNull(vm.state.value.bookmarkReadOnlyId);assertNull(vm.state.value.bookmarkReadOnlyMessage);assertFalse(handle.contains("bookmark_reader_id_42"))
        }
    }
    @Test fun bookmarkReaderOtherSessionSavedKeysAreIgnored() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true);val handle=SavedStateHandle(mapOf("sessionId" to 42L,"bookmark_reader_id_43" to 500L,"bookmark_reader_branch_43" to "main"))
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle();assertNull(vm.state.value.bookmarkReadOnlyId)
        coVerify(exactly=0) { dao.getByIdInSession(any(),any()) }
    }
    @Test fun bookmarkReaderNewTargetReplacesLightweightIntent() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true)
        coEvery { dao.getByIdInSession(500,42) } returns MessageEntity(id=500,sessionId=42,content="原目标")
        val next=MessageEntity(id=600,sessionId=42,swipeGroupId="group",content="新目标")
        coEvery { dao.getMainMessageById(42,600) } returns next
        coEvery { dao.getEffectiveSwipeSelectionsForGroups(42,"main",listOf("group")) } returns listOf(BranchSwipeSelectionEntity(42,"main","group",601))
        val handle=bookmarkReaderHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        vm.openBookmarkedMessage(600);advanceUntilIdle();assertEquals(next,vm.state.value.bookmarkReadOnlyMessage);assertEquals(600L,handle.get<Long>("bookmark_reader_id_42"))
    }

    @Test fun bookmarkReaderLateOldReadCannotReplaceNewTarget() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true);val gate=CompletableDeferred<MessageEntity?>()
        coEvery { dao.getByIdInSession(500,42) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val next=MessageEntity(id=600,sessionId=42,swipeGroupId="new",content="新目标")
        coEvery { dao.getMainMessageById(42,600) } returns next
        coEvery { dao.getEffectiveSwipeSelectionsForGroups(42,"main",listOf("new")) } returns listOf(BranchSwipeSelectionEntity(42,"main","new",601))
        val vm=createViewModel(savedStateHandle=bookmarkReaderHandle(),messageDao=dao);runCurrent()
        vm.closeBookmarkedReadOnlyMessage();vm.openBookmarkedMessage(600);runCurrent()
        assertEquals(next,vm.state.value.bookmarkReadOnlyMessage)
        gate.complete(MessageEntity(id=500,sessionId=42,content="旧请求晚到"));advanceUntilIdle()
        assertEquals(next,vm.state.value.bookmarkReadOnlyMessage);assertEquals(600L,vm.state.value.bookmarkReadOnlyId);assertNull(vm.state.value.bookmarkLocatingId)
    }
    @Test fun bookmarkReaderActualBranchChangeClosesIntent() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true)
        coEvery { dao.getByIdInSession(500,42) } returns MessageEntity(id=500,sessionId=42,content="旧原文")
        val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=1))
        val handle=bookmarkReaderHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao,sessionBranchDao=branches)
        advanceUntilIdle();assertNotNull(vm.state.value.bookmarkReadOnlyMessage)
        vm.switchBranch("B");advanceUntilIdle();assertEquals("B",vm.state.value.currentBranchId)
        assertNull(vm.state.value.bookmarkReadOnlyId);assertFalse(handle.contains("bookmark_reader_id_42"))
    }

    private fun historyHandle(branch: String = "main", size: Int = 81) = SavedStateHandle(mapOf(
        "sessionId" to 42L, "history_window_branch_42" to branch, "history_window_end_42" to 540L,
        "history_window_size_42" to size, "history_window_anchor_42" to 500L))
    private fun historyDao(): MessageDao = mockk<MessageDao>(relaxed=true).also { dao ->
        coEvery { dao.getMainMessageById(42,540) } returns MessageEntity(id=540,sessionId=42,content="窗口末尾")
        coEvery { dao.getMainMessageById(42,500) } returns MessageEntity(id=500,sessionId=42,content="原文")
        coEvery { dao.getMainMessagesBefore(42,541,82) } returns (540L downTo 459L).map { MessageEntity(id=it,sessionId=42,content="消息$it") }
        coEvery { dao.getMainMessagesAfter(42,540,1) } returns listOf(MessageEntity(id=541,sessionId=42))
        coEvery { dao.getMainMessagesTail(42,81) } returns (700L downTo 620L).map { MessageEntity(id=it,sessionId=42) }
    }
    @Test fun historyWindowRestoreKeepsBoundedRowsWithoutRepeatedFocus() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao)
        advanceUntilIdle();assertTrue(vm.state.value.isReady);assertTrue(vm.state.value.historyWindowRestored)
        assertEquals(81,vm.state.value.messages.size);assertEquals(460L,vm.state.value.messages.first().id)
        assertNull(vm.state.value.focusedMessageId);assertTrue(vm.state.value.hasNewerMessages)
        coVerify(exactly=0) { dao.getMainMessagesTail(any(),any()) }
        assertTrue(handle.keys().filter { it.startsWith("history_window_") }.all { handle.get<Any>(it) is Long || handle.get<Any>(it) is Int || handle.get<Any>(it) is String })
    }
    @Test fun historyWindowPendingFocusIsConsumedSeparately() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();handle["history_window_focus_42"]=500L
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle();assertEquals(500L,vm.state.value.focusedMessageId)
        vm.clearFocusedMessage();assertFalse(handle.contains("history_window_focus_42"));assertEquals(540L,handle.get<Long>("history_window_end_42"))
        val next=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle();assertNull(next.state.value.focusedMessageId)
    }
    @Test fun historyWindowReadFailureRetainsCursorForRealRetry() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();coEvery { dao.getMainMessagesBefore(42,541,82) } throws IllegalStateException("synthetic")
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle();assertFalse(vm.state.value.isReady);assertNotNull(vm.state.value.initialLoadError)
        assertEquals(540L,handle.get<Long>("history_window_end_42"))
        coEvery { dao.getMainMessagesBefore(42,541,82) } returns (540L downTo 459L).map { MessageEntity(id=it,sessionId=42) }
        vm.retryInitialization();advanceUntilIdle();assertTrue(vm.state.value.isReady);assertTrue(vm.state.value.historyWindowRestored)
    }
    @Test fun historyWindowMissingWrongSessionOrInactiveAnchorFallsBackWithoutAdoption() = runTest(testDispatcher) {
        for(mode in 0..2) {
            val dao=historyDao();val handle=historyHandle()
            when(mode) {
                0 -> coEvery { dao.getMainMessageById(42,540) } returns null
                1 -> coEvery { dao.getMainMessageById(42,500) } returns MessageEntity(id=500,sessionId=43)
                else -> { coEvery { dao.getMainMessageById(42,500) } returns MessageEntity(id=500,sessionId=42,swipeGroupId="group")
                    coEvery { dao.getEffectiveSwipeSelectionsForGroups(42,"main",listOf("group")) } returns listOf(BranchSwipeSelectionEntity(sessionId=42,branchId="main",swipeGroupId="group",selectedMessageId=501)) }
            }
            val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
            assertFalse(vm.state.value.historyWindowRestored);assertFalse(handle.contains("history_window_end_42"));assertEquals(700L,vm.state.value.messages.last().id)
            coVerify(exactly=0) { dao.selectSwipeVariantForBranch(any(),any(),any(),any()) }
        }
    }
    @Test fun historyWindowWrongScopeAndOversizedCapacityAreDiscarded() = runTest(testDispatcher) {
        for(handle in listOf(historyHandle(branch="other"),historyHandle(size=201))) {
            val dao=historyDao();val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
            assertFalse(vm.state.value.historyWindowRestored);assertFalse(handle.contains("history_window_end_42"))
            coVerify(exactly=0) { dao.getMainMessagesBefore(any(),any(),any()) }
        }
    }
    @Test fun historyWindowLatestResetCannotReopenOldBookmark() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        assertTrue(vm.returnToLatestMessages());advanceUntilIdle();assertFalse(handle.contains("history_window_end_42"))
        val next=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle();assertEquals(700L,next.state.value.messages.last().id);assertFalse(next.state.value.hasNewerMessages)
    }
    @Test fun historyWindowPagingReplacesCursorAndDropsCroppedAnchor() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        for(page in 0..3) {
            val end=vm.state.value.messages.last().id
            coEvery { dao.getMainMessagesAfter(42,end,41) } returns (end+1..end+41).map { MessageEntity(id=it,sessionId=42) }
            vm.loadNewerMessages();advanceUntilIdle()
        }
        assertEquals(200,vm.state.value.messages.size);assertEquals(vm.state.value.messages.last().id,handle.get<Long>("history_window_end_42"))
        assertFalse(handle.contains("history_window_anchor_42"));assertNull(vm.state.value.focusedMessageId)
    }
    @Test fun historyWindowNavigationArgumentIsOnlyInitialCommand() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();handle["sourceMessageId"]=500L;handle["sourceBranchId"]="main";handle["history_navigation_consumed_42"]=true
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao,sourceMessageId=500L,sourceBranchId="main");advanceUntilIdle();assertTrue(vm.state.value.historyWindowRestored);assertNull(vm.state.value.focusedMessageId)
        vm.returnToLatestMessages();advanceUntilIdle();val next=createViewModel(savedStateHandle=handle,messageDao=dao,sourceMessageId=500L,sourceBranchId="main");advanceUntilIdle()
        assertFalse(next.state.value.hasNewerMessages);assertNull(next.state.value.focusedMessageId)
    }

    @Test fun historyWindowChildWaitsForBoundedReadWithoutMainFallback() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true);val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=1))
        val prefs=uiPreferences();coEvery { prefs.getLastChatBranch(42) } returns "B"
        coEvery { dao.getVisibleMessageById(42,"B",540) } returns MessageEntity(id=540,sessionId=42,branchId="B")
        coEvery { dao.getVisibleMessageById(42,"B",500) } returns MessageEntity(id=500,sessionId=42,branchId="B")
        val gate=CompletableDeferred<Unit>()
        coEvery { dao.getVisibleMessagesBefore(42,"B",541,82) } coAnswers { gate.await();(540L downTo 459L).map { MessageEntity(id=it,sessionId=42,branchId="B") } }
        coEvery { dao.getVisibleMessagesAfter(42,"B",540,1) } returns listOf(MessageEntity(id=541,sessionId=42,branchId="B"))
        val handle=historyHandle(branch="B");val vm=createViewModel(savedStateHandle=handle,messageDao=dao,sessionBranchDao=branches,uiPreferencesRepository=prefs)
        runCurrent();assertFalse(vm.state.value.isReady);assertEquals(540L,handle.get<Long>("history_window_end_42"))
        gate.complete(Unit);advanceUntilIdle();assertTrue(vm.state.value.isReady);assertEquals("B",vm.state.value.currentBranchId);assertTrue(vm.state.value.historyWindowRestored)
        coVerify(exactly=0) { dao.getMainMessagesTail(any(),any()) }
        vm.switchBranch("main");advanceUntilIdle();assertFalse(vm.state.value.historyWindowRestored);assertFalse(handle.contains("history_window_end_42"))
    }
    @Test fun historyWindowLatePagingCannotReplaceNewBookmarkCursor() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        val late=CompletableDeferred<Unit>()
        coEvery { dao.getMainMessagesAfter(42,540,41) } coAnswers { withContext(NonCancellable) { late.await();(541L..581L).map { MessageEntity(id=it,sessionId=42) } } }
        vm.loadNewerMessages();runCurrent()
        coEvery { dao.getMainMessageById(42,600) } returns MessageEntity(id=600,sessionId=42)
        coEvery { dao.getMainMessagesBefore(42,600,41) } returns (599L downTo 559L).map { MessageEntity(id=it,sessionId=42) }
        coEvery { dao.getMainMessagesAfter(42,600,41) } returns (601L..641L).map { MessageEntity(id=it,sessionId=42) }
        vm.openBookmarkedMessage(600);runCurrent();assertEquals(640L,handle.get<Long>("history_window_end_42"))
        late.complete(Unit);advanceUntilIdle();assertEquals(640L,handle.get<Long>("history_window_end_42"));assertEquals(600L,vm.state.value.focusedMessageId)
        assertFalse(vm.state.value.historyWindowRestored)
    }

    @Test fun historyWindowConsumedSourceKeepsActualBranchWhenPreferencesDiffer() = runTest(testDispatcher) {
        val dao=mockk<MessageDao>(relaxed=true);val branches=mockk<SessionBranchDao>(relaxed=true)
        coEvery { branches.getBySession(42) } returns listOf(SessionBranchEntity(sessionId=42,branchId="B",sourceMessageId=1))
        val prefs=uiPreferences();coEvery { prefs.getLastChatBranch(42) } returns "main"
        coEvery { dao.getVisibleMessageById(42,"B",540) } returns MessageEntity(id=540,sessionId=42,branchId="B")
        coEvery { dao.getVisibleMessageById(42,"B",500) } returns MessageEntity(id=500,sessionId=42,branchId="B")
        coEvery { dao.getVisibleMessagesBefore(42,"B",541,82) } returns (540L downTo 459L).map { MessageEntity(id=it,sessionId=42,branchId="B") }
        coEvery { dao.getVisibleMessagesAfter(42,"B",540,1) } returns listOf(MessageEntity(id=541,sessionId=42,branchId="B"))
        coEvery { dao.getVisibleMessagesTail(42,"B",81) } returns (700L downTo 620L).map { MessageEntity(id=it,sessionId=42,branchId="B") }
        val handle=historyHandle(branch="B");handle["sourceMessageId"]=500L;handle["sourceBranchId"]="B"
        handle["history_navigation_consumed_42"]=true;handle["history_navigation_branch_42"]="B"
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao,sessionBranchDao=branches,uiPreferencesRepository=prefs,sourceMessageId=500L,sourceBranchId="B");advanceUntilIdle()
        assertTrue("branch=${vm.state.value.currentBranchId}, error=${vm.state.value.initialLoadError}, keys=${handle.keys()}", vm.state.value.historyWindowRestored);assertEquals("B",vm.state.value.currentBranchId);assertNull(vm.state.value.focusedMessageId)
        vm.returnToLatestMessages();advanceUntilIdle();assertFalse(handle.contains("history_window_end_42"));assertEquals("B",handle.get<String>("history_navigation_branch_42"))
        val next=createViewModel(savedStateHandle=handle,messageDao=dao,sessionBranchDao=branches,uiPreferencesRepository=prefs,sourceMessageId=500L,sourceBranchId="B");advanceUntilIdle()
        assertEquals("B",next.state.value.currentBranchId);assertEquals(700L,next.state.value.messages.last().id);assertNull(next.state.value.focusedMessageId)
        coVerify(exactly=0) { prefs.setLastChatBranch(any(),any()) }
    }

    @Test fun recentReadingManualTailRestoresBoundedRowsWithoutFocus() = runTest(testDispatcher) {
        val dao=historyDao();val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        coEvery { dao.getMainMessageById(42,700) } returns MessageEntity(id=700,sessionId=42,createdAt=1L)
        coEvery { dao.getMainMessageById(42,680) } returns MessageEntity(id=680,sessionId=42,createdAt=1L)
        coEvery { dao.getMainMessagesTail(42,81) } returns (700L downTo 620L).map { MessageEntity(id=it,sessionId=42,createdAt=1L) }
        coEvery { dao.getMainMessagesBefore(42,701,81) } returns (700L downTo 620L).map { MessageEntity(id=it,sessionId=42,createdAt=1L) }
        coEvery { dao.getMainMessagesAfter(42,700,1) } returns emptyList()
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        assertFalse(handle.contains("history_window_end_42"))
        vm.rememberMessageReadingPosition("main",700,80,680)
        assertEquals(80,handle.get<Int>("history_window_size_42"));assertEquals(680L,handle.get<Long>("history_window_anchor_42"))
        val next=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        assertTrue(next.state.value.historyWindowRestored);assertFalse(next.state.value.hasNewerMessages)
        assertEquals(vm.state.value.messages,next.state.value.messages);assertNull(next.state.value.focusedMessageId)
        next.returnToLatestMessages();advanceUntilIdle();assertFalse(handle.contains("history_window_end_42"))
        val latest=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        assertFalse(latest.state.value.historyWindowRestored);assertEquals(700L,latest.state.value.messages.last().id)
    }
    @Test fun recentReadingNearTailBookmarkKeepsSmallWindowAfterFocusConsumed() = runTest(testDispatcher) {
        val dao=historyDao();val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        coEvery { dao.getMainMessageById(42,680) } returns MessageEntity(id=680,sessionId=42,createdAt=1L)
        coEvery { dao.getMainMessageById(42,700) } returns MessageEntity(id=700,sessionId=42,createdAt=1L)
        coEvery { dao.getMainMessagesBefore(42,680,41) } returns (679L downTo 639L).map { MessageEntity(id=it,sessionId=42,createdAt=1L) }
        coEvery { dao.getMainMessagesAfter(42,680,41) } returns (681L..700L).map { MessageEntity(id=it,sessionId=42,createdAt=1L) }
        coEvery { dao.getMainMessagesBefore(42,701,62) } returns (700L downTo 639L).map { MessageEntity(id=it,sessionId=42,createdAt=1L) }
        coEvery { dao.getMainMessagesAfter(42,700,1) } returns emptyList()
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        vm.openBookmarkedMessage(680);advanceUntilIdle();assertEquals(61,vm.state.value.messages.size)
        vm.clearFocusedMessage();assertEquals(700L,handle.get<Long>("history_window_end_42"))
        val next=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        assertTrue(next.state.value.historyWindowRestored);assertEquals(vm.state.value.messages,next.state.value.messages)
        assertNull(next.state.value.focusedMessageId);assertFalse(next.state.value.hasNewerMessages)
    }
    @Test fun recentReadingStaleWindowOrBranchCannotSaveReadingIntent() = runTest(testDispatcher) {
        val dao=historyDao();val handle=SavedStateHandle(mapOf("sessionId" to 42L))
        val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        vm.rememberMessageReadingPosition("B",700,80,680)
        vm.rememberMessageReadingPosition("main",699,80,680)
        vm.rememberMessageReadingPosition("main",700,79,680)
        vm.rememberMessageReadingPosition("main",700,80,1)
        assertFalse(handle.contains("history_window_end_42"))
        val gate=CompletableDeferred<Unit>()
        coEvery { dao.getMainMessagesTail(42,81) } coAnswers { gate.await();(700L downTo 620L).map { MessageEntity(id=it,sessionId=42,createdAt=1L) } }
        vm.returnToLatestMessages();runCurrent();assertTrue(vm.state.value.isLoadingHistory)
        vm.rememberMessageReadingPosition("main",700,80,680);assertFalse(handle.contains("history_window_end_42"))
        gate.complete(Unit);advanceUntilIdle();assertFalse(handle.contains("history_window_end_42"))
    }
    @Test fun recentReadingFailedLatestReadKeepsOriginalIntent() = runTest(testDispatcher) {
        val dao=historyDao();val handle=historyHandle();val vm=createViewModel(savedStateHandle=handle,messageDao=dao);advanceUntilIdle()
        coEvery { dao.getMainMessagesTail(42,81) } throws IllegalStateException("synthetic")
        vm.returnToLatestMessages();advanceUntilIdle()
        assertEquals(540L,handle.get<Long>("history_window_end_42"));assertEquals(500L,handle.get<Long>("history_window_anchor_42"))
        assertNotNull(vm.state.value.error);assertEquals(540L,vm.state.value.messages.last().id)
    }
}
