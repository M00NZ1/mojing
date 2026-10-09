package com.mojing.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mojing.app.data.VoiceChoice
import com.mojing.app.media.newmedia.SpeechPlaybackControl
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidTtsPreviewTest {
    companion object {
        private var ownedHandler: Handler? = null
        @JvmStatic @BeforeClass fun installHandler() {
            mockkStatic(Looper::class)
            every { Looper.getMainLooper() } returns mockk(relaxed = true)
            mockkConstructor(Handler::class)
            every { anyConstructed<Handler>().post(any()) } answers { firstArg<Runnable>().run(); true }
            // The singleton may already hold a Handler created by another test class.
            ownedHandler = AndroidTts::class.java.getDeclaredField("mainHandler").let {
                it.isAccessible = true; it.get(null) as Handler
            }.also { handler ->
                mockkObject(handler)
                every { handler.post(any()) } answers { firstArg<Runnable>().run(); true }
            }
        }
        @JvmStatic @AfterClass fun removeHandler() {
            ownedHandler?.let { unmockkObject(it) }; ownedHandler = null
            unmockkConstructor(Handler::class)
            unmockkStatic(Looper::class)
        }
    }
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private val utterances = mutableListOf<String>()
    private val spoken = mutableListOf<String>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        AndroidTts.textDispatcher = dispatcher
        mockkStatic(TextToSpeech::class)
        every { TextToSpeech.getMaxSpeechInputLength() } returns 100
        every { context.applicationContext } returns context
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } answers {
            utterances += arg<String>(3)
            spoken += arg<CharSequence>(0).toString()
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

    @Test fun speechAdvancesOneChunkOnlyAfterPreviousDone() = runTest(dispatcher) {
        field("engineToken", 777L)
        val text = "这是一段朗读。".repeat(40)
        val expected = SpeechChunks.split(text, 100)
        val pending = async { AndroidTts.speakAwaitCompletion(context, text, VoiceChoice()) }
        runCurrent()
        assertEquals(1, utterances.size)
        val listener = listener()
        expected.indices.forEach { index ->
            assertEquals(index + 1, utterances.size)
            assertEquals(expected[index], spoken[index])
            listener.onDone(utterances.last())
            runCurrent()
            assertEquals(index == expected.lastIndex, pending.isCompleted)
        }
        assertEquals(expected.size, utterances.size)
        assertTrue(pending.await())
    }

    @Test fun repeatedRangeResumeKeepsRemainingTextAndRejectsBothOldUtterances() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        val pending = async { AndroidTts.speakAwaitCompletion(context, "abcdefgh", VoiceChoice(), control) }
        runCurrent()
        val listener = listener()
        val first = utterances.last()
        listener.onRangeStart(first, 2, 3, 0)
        control.pause(); control.resume(); runCurrent()
        val second = utterances.last()
        listener.onRangeStart(second, 2, 3, 0)
        control.pause(); control.resume(); runCurrent()
        val third = utterances.last()
        assertEquals(listOf("abcdefgh", "cdefgh", "efgh"), spoken)
        listener.onError(first)
        listener.onDone(second)
        runCurrent()
        assertFalse(pending.isCompleted)
        listener.onDone(third); runCurrent()
        assertTrue(pending.await())
    }

    @Test fun pausedRequestDoesNotStartUntilResumed() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        control.pause()
        val pending = async { AndroidTts.speakAwaitCompletion(context, "暂停后再读", VoiceChoice(), control) }
        runCurrent()
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
        control.resume()
        runCurrent()
        assertEquals(1, utterances.size)
        listener().onDone(utterances.single())
        runCurrent()
        assertTrue(pending.await())
    }

    @Test fun rangePauseResumesRemainingTextAndIgnoresOldDone() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        val text = "abcdef"
        val pending = async { AndroidTts.speakAwaitCompletion(context, text, VoiceChoice(), control) }
        runCurrent()
        val oldId = utterances.single()
        val listener = listener()
        listener.onStart(oldId)
        listener.onRangeStart(oldId, 2, 3, 0)
        control.pause()
        runCurrent()
        control.resume()
        runCurrent()
        assertEquals(listOf(text, "cdef"), spoken)
        listener.onDone(oldId)
        runCurrent()
        assertFalse(pending.isCompleted)
        listener.onDone(utterances.last())
        runCurrent()
        assertTrue(pending.await())
        assertFalse(control.snapshot.value.resumedFromSegmentStart)
    }

    @Test fun invalidRangeResumesFromSegmentStartAndMarksFallback() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        val text = "你好"
        val pending = async { AndroidTts.speakAwaitCompletion(context, text, VoiceChoice(), control) }
        runCurrent()
        val id = utterances.single()
        val listener = listener()
        listener.onRangeStart(id, 99, 100, 0)
        control.pause()
        runCurrent()
        control.resume()
        runCurrent()
        assertEquals(listOf(text, text), spoken)
        assertTrue(control.snapshot.value.resumedFromSegmentStart)
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
