package com.mojing.app.media

import android.media.MediaPlayer
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import kotlin.io.path.createTempFile

class TtsPlayerTest {
    private val completions = mutableListOf<MediaPlayer.OnCompletionListener>()
    private val prepared = mutableListOf<MediaPlayer.OnPreparedListener>()
    @Before fun setUp() {
        mockkConstructor(MediaPlayer::class)
        every { anyConstructed<MediaPlayer>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaPlayer>().setOnCompletionListener(any()) } answers { completions.add(firstArg()); Unit }
        every { anyConstructed<MediaPlayer>().setOnErrorListener(any()) } just Runs
        every { anyConstructed<MediaPlayer>().setOnPreparedListener(any()) } answers { prepared.add(firstArg()); Unit }
        every { anyConstructed<MediaPlayer>().prepareAsync() } just Runs
        every { anyConstructed<MediaPlayer>().start() } just Runs
        every { anyConstructed<MediaPlayer>().stop() } just Runs
        every { anyConstructed<MediaPlayer>().release() } just Runs
    }
    @After fun tearDown() { TtsPlayer.stop(); unmockkConstructor(MediaPlayer::class) }
    @Test fun staleStopAndCompletionCannotStopNewPlayback() {
        val firstFile = createTempFile("mojing-voice-first").toFile()
        val secondFile = createTempFile("mojing-voice-second").toFile()
        try {
            val first = TtsPlayer.playOwned(firstFile, true)!!
            val second = TtsPlayer.playOwned(secondFile, true)!!
            prepared.first().onPrepared(mockk(relaxed = true))
            verify(exactly = 0) { anyConstructed<MediaPlayer>().start() }
            prepared.last().onPrepared(mockk(relaxed = true))
            verify(exactly = 1) { anyConstructed<MediaPlayer>().start() }
            assertTrue(first.completion.isCompleted)
            assertFalse(firstFile.exists())
            TtsPlayer.stop(first)
            completions.first().onCompletion(mockk(relaxed = true))
            assertFalse(second.completion.isCompleted)
            assertTrue(secondFile.exists())
            TtsPlayer.stop(second)
            prepared.last().onPrepared(mockk(relaxed = true))
            verify(exactly = 1) { anyConstructed<MediaPlayer>().start() }
            assertTrue(second.completion.isCompleted)
            assertFalse(secondFile.exists())
        } finally { firstFile.delete(); secondFile.delete() }
    }
}
