package com.mojing.app.media

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardImageProcessorTest {

    @Test
    fun finalJpegFilesAreUniqueAndStoredInFilesDir() {
        val filesDir = Files.createTempDirectory("character-card-test").toFile()
        try {
            val first = CharacterCardImageProcessor.createFinalJpegFile(filesDir)
            val second = CharacterCardImageProcessor.createFinalJpegFile(filesDir)

            assertNotEquals(first.name, second.name)
            assertEquals(filesDir.canonicalPath, first.parentFile?.canonicalPath)
            assertEquals(filesDir.canonicalPath, second.parentFile?.canonicalPath)
            assertTrue(first.name.startsWith("character_card_"))
            assertTrue(first.name.endsWith(".jpg"))
            assertTrue(second.name.startsWith("character_card_"))
            assertTrue(second.name.endsWith(".jpg"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun oversizedImportSourceLeavesNoTemporaryFile() = runTest {
        val directory = Files.createTempDirectory("character-card-import-test").toFile()
        val target = directory.resolve("oversized.bin")
        try {
            val result = runCatching {
                CharacterCardImageProcessor.copyImportSource(
                    ByteArrayInputStream(ByteArray(9)),
                    target,
                    maxBytes = 8,
                )
            }

            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("上限"))
            assertTrue(!target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancelledImportSourceLeavesNoTemporaryFile() = runTest {
        val directory = Files.createTempDirectory("character-card-cancel-test").toFile()
        val target = directory.resolve("cancelled.bin")
        try {
            val worker = launch {
                val workerJob = currentCoroutineContext()[Job]!!
                val input = object : InputStream() {
                    private var delivered = false

                    override fun read(): Int = -1

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (delivered) return -1
                        delivered = true
                        buffer[offset] = 1
                        workerJob.cancel()
                        return 1
                    }
                }
                CharacterCardImageProcessor.copyImportSource(input, target)
            }
            worker.join()

            assertTrue(!target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
