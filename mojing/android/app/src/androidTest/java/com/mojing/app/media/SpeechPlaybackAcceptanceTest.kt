package com.mojing.app.media

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real MediaPlayer coverage for the local TTS playback owner.
 *
 * This uses only synthetic PCM WAV files. It proves local playback state and
 * owned-file cleanup on a generic emulator; it does not prove audible output,
 * Android TTS, or any remote speech provider.
 */
@RunWith(AndroidJUnit4::class)
class SpeechPlaybackAcceptanceTest {

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    @Test
    fun localPcmPlaybackHonorsPauseResumeOwnershipAndCleanup() {
        requireOptIn()
        val directory = File(context.cacheDir, "speech-playback-acceptance-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        val ownedFiles = mutableListOf<File>()
        try {
            val firstFile = newWav(directory, "first.wav", 1_200).also(ownedFiles::add)
            val firstPhases = PhaseProbe()
            val first = onMain {
                TtsPlayer.playOwned(
                    audioFile = firstFile,
                    deleteWhenFinished = true,
                    initialPaused = true,
                    onPhaseChanged = firstPhases::offer,
                )
            } ?: error("MediaPlayer did not accept the first local WAV")

            // initialPaused is the deterministic real path for the prepare
            // race: prepared must remain paused and must not auto-start.
            firstPhases.await(TtsPlayer.Phase.PAUSED)
            firstPhases.await(TtsPlayer.Phase.PAUSED)
            assertFalse(onMain { TtsPlayer.isPlaying() })

            onMain { TtsPlayer.resume(first) }
            firstPhases.await(TtsPlayer.Phase.PLAYING)
            assertTrue(onMain { TtsPlayer.isPlaying() })

            onMain { TtsPlayer.pause(first) }
            firstPhases.await(TtsPlayer.Phase.PAUSED)
            assertFalse(onMain { TtsPlayer.isPlaying() })

            onMain { TtsPlayer.resume(first) }
            firstPhases.await(TtsPlayer.Phase.PLAYING)
            assertTrue(onMain { TtsPlayer.isPlaying() })

            val secondFile = newWav(directory, "second.wav", 1_200).also(ownedFiles::add)
            val secondPhases = PhaseProbe()
            val second = onMain {
                TtsPlayer.playOwned(
                    audioFile = secondFile,
                    deleteWhenFinished = true,
                    initialPaused = true,
                    onPhaseChanged = secondPhases::offer,
                )
            } ?: error("MediaPlayer did not accept the second local WAV")
            secondPhases.await(TtsPlayer.Phase.PAUSED)
            secondPhases.await(TtsPlayer.Phase.PAUSED)
            assertFalse(firstFile.exists())
            assertFalse(onMain { TtsPlayer.isPlaying() })

            // The replaced, old handle cannot stop or otherwise affect the
            // currently owned second handle.
            onMain { TtsPlayer.stop(first) }
            assertFalse(onMain { TtsPlayer.isPlaying() })
            onMain { TtsPlayer.resume(second) }
            secondPhases.await(TtsPlayer.Phase.PLAYING)
            assertTrue(onMain { TtsPlayer.isPlaying() })
            onMain { TtsPlayer.stop(first) }
            assertTrue(onMain { TtsPlayer.isPlaying() })

            onMain { TtsPlayer.stop(second) }
            assertFalse(onMain { TtsPlayer.isPlaying() })
            assertFalse(secondFile.exists())
            assertFalse(runBlocking { TtsPlayer.awaitCompletion(first) })
            assertFalse(runBlocking { TtsPlayer.awaitCompletion(second) })

            val completedFile = newWav(directory, "completed.wav", 350).also(ownedFiles::add)
            val completedPhases = PhaseProbe()
            val completed = onMain {
                TtsPlayer.playOwned(
                    audioFile = completedFile,
                    deleteWhenFinished = true,
                    onPhaseChanged = completedPhases::offer,
                )
            } ?: error("MediaPlayer did not accept the completion WAV")
            completedPhases.await(TtsPlayer.Phase.PREPARING)
            completedPhases.await(TtsPlayer.Phase.PLAYING)
            assertTrue(runBlocking { withTimeout(5_000) { TtsPlayer.awaitCompletion(completed) } })
            assertFalse(completedFile.exists())
        } finally {
            onMain { TtsPlayer.stop() }
            ownedFiles.forEach { file ->
                if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
            }
            directory.delete()
        }
    }

    private fun requireOptIn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("speechPlaybackCapture=true is required", args.getString("speechPlaybackCapture") == "true")
        val generic = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.contains("emulator", ignoreCase = true)
        assumeTrue("speech playback acceptance is restricted to a generic emulator", generic)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                result = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun newWav(directory: File, name: String, durationMs: Int): File {
        val file = File(directory, name)
        val sampleRate = 8_000
        val samples = sampleRate * durationMs / 1_000
        val dataSize = samples * 2
        FileOutputStream(file).use { output ->
            fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
            fun writeLe(value: Int) {
                output.write(value and 0xFF)
                output.write((value shr 8) and 0xFF)
                output.write((value shr 16) and 0xFF)
                output.write((value shr 24) and 0xFF)
            }
            fun writeShortLe(value: Int) {
                output.write(value and 0xFF)
                output.write((value shr 8) and 0xFF)
            }
            writeAscii("RIFF")
            writeLe(36 + dataSize)
            writeAscii("WAVEfmt ")
            writeLe(16)
            writeShortLe(1)
            writeShortLe(1)
            writeLe(sampleRate)
            writeLe(sampleRate * 2)
            writeShortLe(2)
            writeShortLe(16)
            writeAscii("data")
            writeLe(dataSize)
            repeat(samples) { index ->
                val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * 2_000).toInt()
                writeShortLe(sample)
            }
        }
        return file
    }

    private class PhaseProbe {
        private val phases = LinkedBlockingQueue<TtsPlayer.Phase>()

        fun offer(phase: TtsPlayer.Phase) {
            phases.offer(phase)
        }

        fun await(expected: TtsPlayer.Phase) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                val phase = phases.poll()
                if (phase == expected) return
                if (phase == null) Thread.yield()
            }
            error("Timed out waiting for TtsPlayer phase $expected")
        }
    }
}
