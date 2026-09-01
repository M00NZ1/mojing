package com.mojing.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ContentDocumentWriterTest {

    @Test
    fun writesTheCompletePayloadInBoundedChunks() = runTest {
        val payload = ByteArray(19) { it.toByte() }
        val output = ByteArrayOutputStream()

        ContentDocumentWriter.writeBytes(output, payload, chunkSize = 4)

        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun observesCancellationBeforeWritingTheNextChunk() = runTest {
        lateinit var writerJob: Job
        val writes = mutableListOf<Int>()
        val output = object : OutputStream() {
            override fun write(value: Int) = error("single-byte write is not used")

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writes += length
                if (writes.size == 1) writerJob.cancel()
            }
        }
        val failure = CompletableDeferred<Throwable?>()
        writerJob = launch {
            failure.complete(
                runCatching {
                    ContentDocumentWriter.writeBytes(output, ByteArray(12), chunkSize = 4)
                }.exceptionOrNull(),
            )
        }
        writerJob.join()

        assertTrue(failure.await() is CancellationException)
        assertEquals(listOf(4), writes)
    }

    @Test
    fun propagatesProviderWriteFailure() = runTest {
        val output = object : OutputStream() {
            override fun write(value: Int) = throw IOException("provider failed")

            override fun write(bytes: ByteArray, offset: Int, length: Int) =
                throw IOException("provider failed")
        }

        val result = runCatching {
            ContentDocumentWriter.writeBytes(output, ByteArray(4), chunkSize = 4)
        }

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun streamingWriterReturnsItsResultAndFlushesTheTarget() = runTest {
        var flushCount = 0
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                flushCount += 1
                super.flush()
            }
        }

        val result = ContentDocumentWriter.writeStream(output) { stream ->
            stream.write(byteArrayOf(1, 2, 3))
            "written"
        }

        assertEquals("written", result)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(1, flushCount)
    }
}
