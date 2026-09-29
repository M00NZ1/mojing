package com.mojing.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mojing.app.data.VoiceChoice
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidTtsPreviewTest {
    companion object {
        @JvmStatic @BeforeClass fun installHandler() {
            mockkStatic(Looper::class)
            every { Looper.getMainLooper() } returns mockk(relaxed = true)
            mockkConstructor(Handler::class)
            every { anyConstructed<Handler>().post(any()) } answers { firstArg<Runnable>().run(); true }
        }
        @JvmStatic @AfterClass fun removeHandler() {
            unmockkConstructor(Handler::class)
            unmockkStatic(Looper::class)
        }
    }
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private val utterances = mutableListOf<String>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        AndroidTts.textDispatcher = dispatcher
        mockkStatic(TextToSpeech::class)
        every { TextToSpeech.getMaxSpeechInputLength() } returns 100
        every { context.applicationContext } returns context
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } answers {
            utterances += arg<String>(3)
            TextToSpeech.SUCCESS
        }
        AndroidTts.stop()
        field("tts", engine)
        field("isInitialized", true)
        field("isInitializing", false)
        field("activeEngineId", "system")
        field("activeVoiceId", "")
    }

    @After fun tearDown() {
        AndroidTts.stop()
        field("tts", null)
        field("isInitialized", false)
        unmockkStatic(TextToSpeech::class)
        AndroidTts.textDispatcher = Dispatchers.Default
        Dispatchers.resetMain()
    }

    @Test fun speechWaitsForFinalChunk() = runTest(dispatcher) {
        val pending = async { AndroidTts.speakAwaitCompletion(context, "这是一段朗读。".repeat(40), VoiceChoice()) }
        runCurrent()
        assertTrue(utterances.size > 1)
        val listener = listener()
        listener.onDone(utterances.first())
        runCurrent()
        assertFalse(pending.isCompleted)
        listener.onDone(utterances.last())
        runCurrent()
        assertTrue(pending.await())
    }

    @Test fun cancellingCurrentPreviewStopsItsEngine() = runTest(dispatcher) {
        val pending = async { AndroidTts.preview(context, "你好", VoiceChoice()) }
        runCurrent()
        pending.cancel()
        runCurrent()
        verify(exactly = 1) { engine.stop() }
    }

    @Test fun contentRemovedBySpeechCleanupNeverStartsPlayback() = runTest(dispatcher) {
        assertFalse(AndroidTts.speakAwaitCompletion(context, "😀", VoiceChoice()))
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test fun replacedPreviewCannotStopNewSpeech() = runTest(dispatcher) {
        val pending = async { AndroidTts.preview(context, "试听", VoiceChoice()) }
        runCurrent()
        AndroidTts.speakWithVoice(context, "新的对话朗读", VoiceChoice(), null)
        runCurrent()
        assertFalse(pending.await())
        verify(exactly = 0) { engine.stop() }
    }

    private fun field(name: String, value: Any?) {
        AndroidTts::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }

    private fun listener(): UtteranceProgressListener {
        val token = AndroidTts::class.java.getDeclaredField("engineToken").apply { isAccessible = true }.getLong(null)
        return AndroidTts::class.java.getDeclaredMethod("listenerFor", TextToSpeech::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(AndroidTts, engine, token) as UtteranceProgressListener
    }
}
