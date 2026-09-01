package com.mojing.app.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream

class LocalImageFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesCompleteImagesToUniquePrivateFiles() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4)

        val first = LocalImageFiles.copyInputToPrivateImage(
            ByteArrayInputStream(payload), temporaryFolder.root, "avatar_", "png",
        )
        val second = LocalImageFiles.copyInputToPrivateImage(
            ByteArrayInputStream(payload), temporaryFolder.root, "avatar_", "png",
        )

        assertNotEquals(first, second)
        assertTrue(payload.contentEquals(java.io.File(first).readBytes()))
        assertTrue(payload.contentEquals(java.io.File(second).readBytes()))
    }

    @Test
    fun rejectsOversizedImagesWithoutLeavingPartialFiles() = runTest {
        val result = runCatching {
            LocalImageFiles.copyInputToPrivateImage(
                ByteArrayInputStream(ByteArray(9)),
                temporaryFolder.root,
                "cover_",
                "jpg",
                maxBytes = 8,
            )
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("上限"))
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationRemovesThePartialImage() = runTest {
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

            LocalImageFiles.copyInputToPrivateImage(
                input, temporaryFolder.root, "cover_", "jpg",
            )
        }
        worker.join()

        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }
}
