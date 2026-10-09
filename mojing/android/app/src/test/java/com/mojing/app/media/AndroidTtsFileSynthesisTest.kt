package com.mojing.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mojing.app.data.VoiceChoice
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTtsFileSynthesisTest {
    private suspend inline fun <reified T : Throwable> assertFailsWith(noinline block: suspend () -> Unit): T {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected failure type", error is T)
        return error as T
    }

    companion object {
        private var handler: Handler? = null

        @JvmStatic @BeforeClass fun installHandler() {
            mockkStatic(Looper::class)
            every { Looper.getMainLooper() } returns mockk(relaxed = true)
            mockkConstructor(Handler::class)
            every { anyConstructed<Handler>().post(any()) } answers { firstArg<Runnable>().run(); true }
            handler = AndroidTts::class.java.getDeclaredField("mainHandler").let {
                it.isAccessible = true; it.get(null) as Handler
            }.also {
                mockkObject(it)
                every { it.post(any()) } answers { firstArg<Runnable>().run(); true }
            }
        }

        @JvmStatic @AfterClass fun removeHandler() {
            handler?.let { unmockkObject(it) }
            unmockkConstructor(Handler::class)
            unmockkStatic(Looper::class)
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private lateinit var listener: UtteranceProgressListener
    private lateinit var output: File
    private lateinit var originalFactory: (Context, (Int) -> Unit, String?) -> TextToSpeech
    private lateinit var originalValidationDispatcher: kotlinx.coroutines.CoroutineDispatcher
    private lateinit var originalTextDispatcher: kotlinx.coroutines.CoroutineDispatcher
    private lateinit var originalAudioMimeDetector: (File) -> String?

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        originalTextDispatcher = AndroidTts.textDispatcher
        AndroidTts.textDispatcher = dispatcher
        originalFactory = AndroidTts.engineFactory
        originalValidationDispatcher = AndroidTts.fileValidationDispatcher
        originalAudioMimeDetector = AndroidTts.audioMimeDetector
        AndroidTts.fileValidationDispatcher = dispatcher
        mockkStatic(TextToSpeech::class)
        every { TextToSpeech.getMaxSpeechInputLength() } returns 5
        every { context.applicationContext } returns context
        output = createTempDir("mojing-android-voice")
        AndroidTts.audioMimeDetector = { "audio/wav" }
        AndroidTts.stop()
        field("tts", engine)
        field("isInitialized", true)
        field("isInitializing", false)
        field("activeEngineId", "system")
        field("activeVoiceId", "")
        field("engineToken", 900L)
        field("activePlanToken", null)
        field("activePlanPaused", false)
        listener = AndroidTts::class.java.getDeclaredMethod(
            "listenerFor", TextToSpeech::class.java, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(AndroidTts, engine, 900L) as UtteranceProgressListener
    }

    @After fun tearDown() {
        AndroidTts.stop()
        field("tts", null)
        field("isInitialized", false)
        field("isInitializing", false)
        AndroidTts.audioMimeDetector = originalAudioMimeDetector
        AndroidTts.engineFactory = originalFactory
        AndroidTts.fileValidationDispatcher = originalValidationDispatcher
        AndroidTts.textDispatcher = originalTextDispatcher
        check(output.isDirectory && output.name.startsWith("mojing-"))
        output.deleteRecursively()
        unmockkStatic(TextToSpeech::class)
        Dispatchers.resetMain()
    }

    @Test fun threeChunksAreOrderedAndNeverCallSpeak() = runTest(dispatcher) {
        val spoken = mutableListOf<String>()
        val ids = mutableListOf<String>()
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } answers { spoken += firstArg<CharSequence>().toString(); TextToSpeech.SUCCESS }
        every { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) } answers {
            val file = arg<File>(2); file.writeText(arg<CharSequence>(0).toString()); ids += arg<String>(3)
            listener.onDone(arg<String>(3)); TextToSpeech.SUCCESS
        }

        val result = AndroidTts.synthesizeToFiles(context, "abcdefghijklmno", VoiceChoice(), output, "attempt-ordered")

        assertEquals(3, result.size)
        assertEquals(listOf("abcde", "fghij", "klmno"), result.map { it.file.readText() })
        assertEquals(listOf("audio/wav", "audio/wav", "audio/wav"), result.map { it.mimeType })
        assertTrue(spoken.isEmpty())
        assertEquals(3, ids.size)
    }

    @Test fun synchronousSecondChunkFailureDeletesAllAttemptFiles() = runTest(dispatcher) {
        var calls = 0
        every { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) } answers {
            calls++
            val file = arg<File>(2); file.writeText("segment-$calls")
            if (calls == 1) listener.onDone(arg<String>(3))
            if (calls == 2) TextToSpeech.ERROR else TextToSpeech.SUCCESS
        }

        val error = runCatching {
            AndroidTts.synthesizeToFiles(context, "abcdefghijk", VoiceChoice(), output, "attempt-fail")
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(2, calls)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test fun activeManualOrInitializingRequestRejectsWithoutStoppingIt() = runTest(dispatcher) {
        every { engine.stop() } returns TextToSpeech.SUCCESS
        field("activePlanToken", 77L)
        assertFailsWith<IllegalStateException> {
            AndroidTts.synthesizeToFiles(context, "abc", VoiceChoice(), output, "attempt-busy")
        }
        verify(exactly = 0) { engine.stop() }
        field("activePlanToken", null)
        field("isInitializing", true)
        assertFailsWith<IllegalStateException> {
            AndroidTts.synthesizeToFiles(context, "abc", VoiceChoice(), output, "attempt-init")
        }
        verify(exactly = 0) { engine.stop() }
    }

    @Test fun initializationFailureCompletesFileRequestAndLeavesNoFiles() = runTest(dispatcher) {
        field("tts", null)
        field("isInitialized", false)
        field("isInitializing", false)
        AndroidTts.engineFactory = { _, callback, _ ->
            callback(TextToSpeech.ERROR)
            engine
        }

        val error = runCatching {
            AndroidTts.synthesizeToFiles(context, "初始化失败", VoiceChoice(), output, "attempt-init-fail")
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test fun oldFileCallbackCannotStopOrCompleteNewManualSpeech() = runTest(dispatcher) {
        val oldIds = mutableListOf<String>()
        every { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) } answers {
            val file = arg<File>(2)
            file.writeText("pending")
            oldIds += arg<String>(3)
            TextToSpeech.SUCCESS
        }
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.SUCCESS
        val fileJob = async {
            runCatching { AndroidTts.synthesizeToFiles(context, "自动配音", VoiceChoice(), output, "attempt-replaced") }
        }
        runCurrent()
        AndroidTts.speakWithVoice(context, "手动朗读", VoiceChoice(), null)
        runCurrent()
        oldIds.singleOrNull()?.let(listener::onDone)
        runCurrent()

        assertTrue(fileJob.isCompleted)
        assertTrue(fileJob.await().exceptionOrNull() is IllegalStateException)
        verify(exactly = 1) { engine.speak(any<CharSequence>(), any(), any(), any()) }
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationAfterCompleteBeforeAwaitHandoffDeletesFilesWithoutStoppingNewOwner() = runTest(dispatcher) {
        val validationDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        AndroidTts.fileValidationDispatcher = validationDispatcher
        val ids = mutableListOf<String>()
        every { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) } answers {
            val file = arg<File>(2)
            file.writeText("complete-before-handoff")
            ids += arg<String>(3)
            TextToSpeech.SUCCESS
        }
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.SUCCESS
        val fileJob = async {
            AndroidTts.synthesizeToFiles(context, "交付窗口", VoiceChoice(), output, "attempt-handoff")
        }
        runCurrent()
        listener.onDone(ids.single())
        validationDispatcher.scheduler.runCurrent()
        assertTrue(output.listFiles().orEmpty().isNotEmpty())
        AndroidTts.speakWithVoice(context, "新手动朗读", VoiceChoice(), null)
        fileJob.cancel()
        runCatching { fileJob.await() }
        runCurrent()
        verify(exactly = 1) { engine.speak(any<CharSequence>(), any(), any(), any()) }
        verify(exactly = 0) { engine.stop() }
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationAfterCompleteBeforeAwaitHandoffCleansExactState() = runTest(dispatcher) {
        val validationDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        AndroidTts.fileValidationDispatcher = validationDispatcher
        val ids = mutableListOf<String>()
        every { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) } answers {
            val file = arg<File>(2)
            file.writeText("handoff-window")
            ids += arg<String>(3)
            TextToSpeech.SUCCESS
        }
        val fileJob = async {
            AndroidTts.synthesizeToFiles(context, "交付取消", VoiceChoice(), output, "attempt-handoff-cancel")
        }
        runCurrent()
        listener.onDone(ids.single())
        validationDispatcher.scheduler.runCurrent()
        assertTrue(output.listFiles().orEmpty().isNotEmpty())
        fileJob.cancel()
        runCatching { fileJob.await() }
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test fun completedFileRequestSurvivesManualTakeoverBeforeNormalAwait() = runTest(dispatcher) {
        val validationDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        AndroidTts.fileValidationDispatcher = validationDispatcher
        val ids = mutableListOf<String>()
        every { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) } answers {
            val file = arg<File>(2)
            file.writeText("handoff-success")
            ids += arg<String>(3)
            TextToSpeech.SUCCESS
        }
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.SUCCESS
        every { engine.stop() } returns TextToSpeech.SUCCESS

        val fileJob = async {
            AndroidTts.synthesizeToFiles(context, "交付成功", VoiceChoice(), output, "attempt-handoff-ok")
        }
        runCurrent()
        listener.onDone(ids.single())
        validationDispatcher.scheduler.runCurrent()
        assertTrue(output.listFiles().orEmpty().isNotEmpty())

        AndroidTts.speakWithVoice(context, "新手动朗读", VoiceChoice(), null)
        runCurrent()
        val result = fileJob.await()

        assertTrue(result.single().file.isFile)
        verify(exactly = 1) { engine.speak(any<CharSequence>(), any(), any(), any()) }
        verify(exactly = 0) { engine.stop() }
    }

    @Test fun audioMimeDetectorFailureCompletesAndCleansAttempt() = runTest(dispatcher) {
        val validationDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        AndroidTts.fileValidationDispatcher = validationDispatcher
        AndroidTts.audioMimeDetector = { error("detector failed") }
        val ids = mutableListOf<String>()
        every { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) } answers {
            val file = arg<File>(2)
            file.writeText("detector-failure")
            ids += arg<String>(3)
            TextToSpeech.SUCCESS
        }

        val fileJob = async {
            runCatching { AndroidTts.synthesizeToFiles(context, "检测失败", VoiceChoice(), output, "attempt-detector") }
        }
        runCurrent()
        listener.onDone(ids.single())
        validationDispatcher.scheduler.runCurrent()

        assertNotNull(fileJob.await().exceptionOrNull())
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test fun pausedManualSpeechIsNotInterruptedByAutomaticFileRequest() = runTest(dispatcher) {
        every { engine.stop() } returns TextToSpeech.SUCCESS
        field("activePlanToken", 88L)
        field("activePlanPaused", true)

        assertFailsWith<IllegalStateException> {
            AndroidTts.synthesizeToFiles(context, "自动配音", VoiceChoice(), output, "attempt-paused")
        }

        verify(exactly = 0) { engine.stop() }
    }

    @Test fun missingExplicitEngineRejectsBothFileAndManualWithoutFallbackOrOwnerStop() = runTest(dispatcher) {
        mockkConstructor(android.content.Intent::class)
        try {
            every { anyConstructed<android.content.Intent>().setPackage(any()) } answers { self as android.content.Intent }
            val packages = mockk<android.content.pm.PackageManager>()
            every { context.packageManager } returns packages
            every { packages.queryIntentServices(any(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) } returns emptyList()
            assertFailsWith<IllegalStateException> {
                AndroidTts.synthesizeToFiles(context, "不可回退", VoiceChoice("android:missing.engine"), output, "attempt-missing")
            }
            field("activePlanToken", 88L)
            var error: String? = null
            AndroidTts.speakWithVoice(context, "手动也不回退", VoiceChoice("android:missing.engine")) { error = it }
            assertTrue(error.orEmpty().contains("未安装"))
            verify(exactly = 0) { engine.stop() }
            verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
            verify(exactly = 0) { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) }
        } finally { unmockkConstructor(android.content.Intent::class) }
    }

    @Test fun disabledWrongPackageAndAmbiguousExplicitServicesCannotStartSynthesis() = runTest(dispatcher) {
        mockkConstructor(android.content.Intent::class)
        try {
            every { anyConstructed<android.content.Intent>().setPackage(any()) } answers { self as android.content.Intent }
            val packages = mockk<android.content.pm.PackageManager>()
            every { context.packageManager } returns packages
            fun service(packageName: String, enabled: Boolean) = android.content.pm.ResolveInfo().also { resolved ->
                resolved.serviceInfo = android.content.pm.ServiceInfo().also { it.packageName = packageName; it.enabled = enabled }
            }
            val installed = service("local.engine", true)
            for (results in listOf(listOf(service("local.engine", false)), listOf(service("other.engine", true)), listOf(installed, installed))) {
                every { packages.queryIntentServices(any(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) } returns results
                assertFailsWith<IllegalStateException> {
                    AndroidTts.synthesizeToFiles(context, "无效的引擎目录", VoiceChoice("android:local.engine"), output, "attempt-invalid-service")
                }
            }
            verify(exactly = 0) { engine.stop() }
            verify(exactly = 0) { engine.synthesizeToFile(any<CharSequence>(), any(), any<File>(), any()) }
        } finally { unmockkConstructor(android.content.Intent::class) }
    }

    private fun field(name: String, value: Any?) {
        AndroidTts::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
}
