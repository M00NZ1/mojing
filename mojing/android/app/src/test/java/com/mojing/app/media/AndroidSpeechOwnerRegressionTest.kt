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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeechOwnerRegressionTest {
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
    private val initCallbacks = mutableListOf<(Int) -> Unit>()
    private val utteranceIds = mutableListOf<String>()
    private lateinit var oldFactory: (Context, (Int) -> Unit, String?) -> TextToSpeech
    private lateinit var oldDispatcher: kotlinx.coroutines.CoroutineDispatcher

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        oldFactory = AndroidTts.engineFactory
        oldDispatcher = AndroidTts.textDispatcher
        AndroidTts.textDispatcher = dispatcher
        AndroidTts.engineFactory = { _, callback, _ -> initCallbacks += callback; engine }
        mockkStatic(TextToSpeech::class)
        every { TextToSpeech.getMaxSpeechInputLength() } returns 100
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } answers {
            utteranceIds += arg<String>(3)
            TextToSpeech.SUCCESS
        }
        AndroidTts.stop()
        field("tts", null)
        field("isInitialized", false)
        field("isInitializing", false)
        every { context.applicationContext } returns context
    }

    @After fun tearDown() {
        AndroidTts.stop()
        field("tts", null)
        field("isInitialized", false)
        field("isInitializing", false)
        AndroidTts.engineFactory = oldFactory
        AndroidTts.textDispatcher = oldDispatcher
        initCallbacks.clear()
        unmockkStatic(TextToSpeech::class)
        Dispatchers.resetMain()
    }

    @Test fun pauseBeforeFirstInitDoesNotSpeakUntilResume() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        control.pause()
        val pending = async { AndroidTts.speakAwaitCompletion(context, "首次初始化", VoiceChoice(), control) }
        runCurrent()
        assertEquals(1, initCallbacks.size)
        initCallbacks.single()(TextToSpeech.SUCCESS)
        runCurrent()
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
        control.resume()
        runCurrent()
        assertEquals(1, utteranceIds.size)
        listener().onDone(utteranceIds.single())
        runCurrent()
        assertTrue(pending.await())
    }

    @Test fun closeDuringInitializationFailsRequestAndIgnoresLateInit() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        val pending = async { AndroidTts.speakAwaitCompletion(context, "关闭初始化", VoiceChoice(), control) }
        runCurrent()
        assertEquals(1, initCallbacks.size)
        control.close()
        runCurrent()
        assertFalse(pending.await())
        initCallbacks.single()(TextToSpeech.SUCCESS)
        runCurrent()
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test fun factoryFailureReleasesControlAndFailsRequest() = runTest(dispatcher) {
        AndroidTts.engineFactory = { _, _, _ -> error("factory failure") }
        val control = SpeechPlaybackControl()
        val pending = async { runCatching { AndroidTts.speakAwaitCompletion(context, "构造失败", VoiceChoice(), control) } }
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        assertEquals(SpeechPlaybackControl.Snapshot(), control.snapshot.value)
    }

    @Test fun oldErrorAfterResumeCannotFailNewUtterance() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        val pending = async { AndroidTts.speakAwaitCompletion(context, "abcdef", VoiceChoice(), control) }
        runCurrent()
        initCallbacks.single()(TextToSpeech.SUCCESS)
        runCurrent()
        val oldId = utteranceIds.single()
        listener().onRangeStart(oldId, 2, 3, 0)
        control.pause(); runCurrent(); control.resume(); runCurrent()
        val newId = utteranceIds.last()
        assertNotEquals(oldId, newId)
        listener().onError(oldId)
        runCurrent()
        assertFalse(pending.isCompleted)
        listener().onDone(newId)
        runCurrent()
        assertTrue(pending.await())
    }

    @Test fun oldCompletionCannotFinishReplacementWithDifferentControl() = runTest(dispatcher) {
        val firstControl = SpeechPlaybackControl()
        val first = async { AndroidTts.speakAwaitCompletion(context, "第一请求", VoiceChoice(), firstControl) }
        runCurrent(); initCallbacks.single()(TextToSpeech.SUCCESS); runCurrent()
        val oldId = utteranceIds.single()
        val secondControl = SpeechPlaybackControl()
        val second = async { AndroidTts.speakAwaitCompletion(context, "第二请求", VoiceChoice(), secondControl) }
        runCurrent()
        val newId = utteranceIds.last()
        listener().onDone(oldId); runCurrent()
        assertFalse(second.isCompleted)
        listener().onDone(newId); runCurrent()
        assertTrue(second.await()); assertFalse(first.await())
    }

    @Test fun oldFinallyCannotUnbindReplacementWithSameControl() = runTest(dispatcher) {
        val control = SpeechPlaybackControl()
        val first = async { AndroidTts.speakAwaitCompletion(context, "第一段", VoiceChoice(), control) }
        runCurrent(); initCallbacks.single()(TextToSpeech.SUCCESS); runCurrent()
        val oldId = utteranceIds.single()
        val second = async { AndroidTts.speakAwaitCompletion(context, "第二段", VoiceChoice(), control) }
        runCurrent(); val newId = utteranceIds.last()
        listener().onDone(oldId); runCurrent()
        assertFalse(second.isCompleted)
        listener().onDone(newId); runCurrent()
        assertTrue(second.await()); assertFalse(first.await()); assertEquals(SpeechPlaybackControl.Snapshot(), control.snapshot.value)
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
