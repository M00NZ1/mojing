package com.mojing.app.media

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.mojing.app.media.newmedia.SpeechPlaybackControl
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

@OptIn(ExperimentalCoroutinesApi::class)
class AzurePlaybackOwnerTest {
    companion object {

        private var ownedHandler: Handler? = null

        @JvmStatic @BeforeClass fun installHandler() {

            mockkStatic(Looper::class)

            every { Looper.getMainLooper() } returns mockk(relaxed = true)

            mockkConstructor(Handler::class)

            every { anyConstructed<Handler>().post(any()) } answers { firstArg<Runnable>().run(); true }

            // The singleton may already hold a Handler created by another test class.

            ownedHandler = AzureSpeech::class.java.getDeclaredMethod("getMainHandler").let {
                it.isAccessible = true; it.invoke(AzureSpeech) as Handler
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
    private val prepared = mutableListOf<MediaPlayer.OnPreparedListener>()
    private val completions = mutableListOf<MediaPlayer.OnCompletionListener>()
    private var handoffAction: () -> Unit = {}

    @Before fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        mockkConstructor(MediaPlayer::class)
        every { anyConstructed<MediaPlayer>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaPlayer>().setOnPreparedListener(any()) } answers { prepared += firstArg<MediaPlayer.OnPreparedListener>(); Unit }
        every { anyConstructed<MediaPlayer>().setOnCompletionListener(any()) } answers { completions += firstArg<MediaPlayer.OnCompletionListener>(); Unit }
        every { anyConstructed<MediaPlayer>().setOnErrorListener(any()) } just Runs
        every { anyConstructed<MediaPlayer>().prepareAsync() } answers { handoffAction(); Unit }
        every { anyConstructed<MediaPlayer>().start() } just Runs
        every { anyConstructed<MediaPlayer>().pause() } just Runs
        every { anyConstructed<MediaPlayer>().stop() } just Runs
        every { anyConstructed<MediaPlayer>().release() } just Runs
        prepared.clear(); completions.clear(); handoffAction = {}
    }

    @After fun tearDown() {
        TtsPlayer.stop()
        unmockkConstructor(MediaPlayer::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test fun resumeBetweenPlayerCreationAndCallbackHandoffStartsAfterPrepared() = runTest(dispatcher) {
        val file = tempAudio()
        val control = SpeechPlaybackControl(); val lease = control.bind({}, {})!!
        control.pause()
        handoffAction = { control.resume() }
        val pending = async { AzureSpeech.playFile(file, control, lease, 1, 1) }
        runCurrent()
        prepared.single().onPrepared(mockk(relaxed = true)); runCurrent()
        verify(exactly = 1) { anyConstructed<MediaPlayer>().start() }
        completions.single().onCompletion(mockk(relaxed = true)); runCurrent()
        assertTrue(pending.await()); assertFalse(file.exists())
    }

    @Test fun pauseBetweenPlayerCreationAndCallbackHandoffDefersPreparedStart() = runTest(dispatcher) {
        val file = tempAudio()
        val control = SpeechPlaybackControl(); val lease = control.bind({}, {})!!
        handoffAction = { control.pause() }
        val pending = async { AzureSpeech.playFile(file, control, lease, 1, 1) }
        runCurrent()
        prepared.single().onPrepared(mockk(relaxed = true)); runCurrent()
        verify(exactly = 0) { anyConstructed<MediaPlayer>().start() }
        control.resume(); runCurrent(); verify(exactly = 1) { anyConstructed<MediaPlayer>().start() }
        completions.single().onCompletion(mockk(relaxed = true)); runCurrent()
        assertTrue(pending.await())
    }

    @Test fun closeWhilePlayingStopsHandleAndDeletesFile() = runTest(dispatcher) {
        val file = tempAudio()
        val control = SpeechPlaybackControl(); val lease = control.bind({}, {})!!
        val pending = async { AzureSpeech.playFile(file, control, lease, 1, 1) }
        runCurrent(); prepared.single().onPrepared(mockk(relaxed = true)); runCurrent()
        control.close(); runCurrent()
        assertFalse(pending.await()); assertFalse(file.exists()); assertFalse(control.owns(lease))
    }

    @Test fun oldCompletionCannotClearReplacementLease() = runTest(dispatcher) {
        val firstFile = tempAudio(); val secondFile = tempAudio()
        val control = SpeechPlaybackControl(); val firstLease = control.bind({}, {})!!
        val first = async { AzureSpeech.playFile(firstFile, control, firstLease, 1, 1) }
        runCurrent(); prepared.single().onPrepared(mockk(relaxed = true)); runCurrent()
        val secondLease = control.bind({}, {})!!
        completions.single().onCompletion(mockk(relaxed = true)); runCurrent()
        assertFalse(control.owns(firstLease)); assertTrue(control.owns(secondLease)); assertFalse(first.await())
        secondFile.delete()
    }

    private fun tempAudio(): File = createTempFile("mojing-azure-owner", ".mp3").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
}
