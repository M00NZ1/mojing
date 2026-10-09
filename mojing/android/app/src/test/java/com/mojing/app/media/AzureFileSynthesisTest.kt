package com.mojing.app.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@OptIn(ExperimentalCoroutinesApi::class)
class AzureFileSynthesisTest {
    private suspend inline fun <reified T : Throwable> assertFailsWith(noinline block: suspend () -> Unit): T {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected failure type", error is T)
        return error as T
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var output: File
    private lateinit var originalAudioMimeDetector: (File) -> String?
    private lateinit var originalFileSynthesis: suspend (String, String, String, String) -> ByteArray

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        output = createTempDir("mojing-azure-voice")
        originalAudioMimeDetector = AzureSpeech.audioMimeDetector
        originalFileSynthesis = AzureSpeech.fileSynthesis
        AzureSpeech.audioMimeDetector = { "audio/mpeg" }
        AzureSpeech.fileSynthesis = { _, _, _, _ ->
            throw AssertionError("network synthesis must not run in unit tests")
        }
    }

    @After fun tearDown() {
        AzureSpeech.audioMimeDetector = originalAudioMimeDetector
        AzureSpeech.fileSynthesis = originalFileSynthesis
        check(output.isDirectory && output.name.startsWith("mojing-"))
        output.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test fun threeChunksAreOrderedAndReturnedAsIndependentFiles() = runTest(dispatcher) {
        val text = "abcdefghijklmno".repeat(500)
        val expected = SpeechChunks.split(text, 2_400)
        val chunks = mutableListOf<String>()
        AzureSpeech.fileSynthesis = { _, _, _, chunk ->
            chunks += chunk
            chunk.toByteArray()
        }

        val files = AzureSpeech.synthesizeToFiles(
            text = text, region = "eastasia", key = "test-key",
            voiceId = "zh-CN-XiaoxiaoNeural", outputDir = output, attemptToken = "azure-order",
        )

        assertEquals(expected, chunks)
        assertEquals(chunks, files.map { it.file.readText() })
        assertEquals(chunks.size, files.size)
        assertTrue(files.all { it.mimeType == "audio/mpeg" })
        assertEquals(chunks.size, output.listFiles().orEmpty().size)
    }

    @Test fun secondChunkFailureDeletesEarlierFiles() = runTest(dispatcher) {
        var calls = 0
        AzureSpeech.fileSynthesis = { _, _, _, chunk ->
            calls++
            if (calls == 2) throw AzureSpeech.SpeechException("第二段失败")
            chunk.toByteArray()
        }

        val error = runCatching {
            AzureSpeech.synthesizeToFiles("abcdefgh".repeat(500), "eastasia", "test-key", "voice", output, "azure-fail")
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(2, calls)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationDuringFinalChunkCleansAttemptFiles() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        AzureSpeech.fileSynthesis = { _, _, _, chunk ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            chunk.toByteArray()
        }
        val job = async {
            AzureSpeech.synthesizeToFiles("abcdefgh".repeat(500), "eastasia", "test-key", "voice", output, "azure-cancel")
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }
}
