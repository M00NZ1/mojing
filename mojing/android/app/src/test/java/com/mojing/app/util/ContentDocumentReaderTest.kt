package com.mojing.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ContentDocumentReaderTest {

    @Test
    fun readsContentAtTheExactLimit() = runTest {
        val content = ByteArray(128) { it.toByte() }

        val result = ContentDocumentReader.readBytes(ByteArrayInputStream(content), content.size)

        assertArrayEquals(content, result)
    }

    @Test
    fun rejectsContentBeforeWritingPastTheLimit() = runTest {
        val result = runCatching {
            ContentDocumentReader.readBytes(ByteArrayInputStream(ByteArray(129)), 128)
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("上限"))
    }

    @Test
    fun observesCancellationBetweenChunks() = runTest {
        val reads = mutableListOf<Int>()
        lateinit var readerJob: Job
        val input = object : InputStream() {
            override fun read(): Int = error("single-byte read is not used")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads += length
                if (reads.size == 1) {
                    repeat(length) { buffer[offset + it] = 1 }
                    readerJob.cancel()
                    return length
                }
                return -1
            }
        }

        val failure = CompletableDeferred<Throwable?>()
        readerJob = launch {
            failure.complete(
                runCatching {
                    ContentDocumentReader.readBytes(input, 256 * 1024)
                }.exceptionOrNull(),
            )
        }
        readerJob.join()

        assertTrue(failure.await() is CancellationException)
        assertEquals(1, reads.size)
    }
}
