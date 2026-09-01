package com.mojing.app.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatAttachmentFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun copyInputToFileWritesCompletePayload() = runTest {
        val output = temporaryFolder.newFile("complete.png")
        output.delete()
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        ChatAttachmentFiles.copyInputToFile(ByteArrayInputStream(payload), output)

        assertTrue(payload.contentEquals(output.readBytes()))
    }

    @Test
    fun copyInputToFileDeletesPartialFileWhenLimitIsExceeded() = runTest {
        val output = temporaryFolder.newFile("oversized.png")
        output.delete()

        runCatching {
            ChatAttachmentFiles.copyInputToFile(
                ByteArrayInputStream(ByteArray(16)),
                output,
                maxBytes = 8,
            )
        }

        assertFalse(output.exists())
    }

    @Test
    fun copyInputToFileRejectsEmptySourceWithoutLeavingFile() = runTest {
        val output = temporaryFolder.newFile("empty.png")
        output.delete()

        runCatching { ChatAttachmentFiles.copyInputToFile(ByteArrayInputStream(byteArrayOf()), output) }

        assertFalse(output.exists())
    }

    @Test
    fun copyInputToFileDeletesPartialFileWhenSourceFails() = runTest {
        val output = temporaryFolder.newFile("failed.png")
        output.delete()
        val failingInput = object : InputStream() {
            private var deliveredFirstChunk = false

            override fun read(): Int = throw IOException("source failed")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (deliveredFirstChunk) throw IOException("source failed")
                deliveredFirstChunk = true
                buffer[offset] = 1
                return 1
            }
        }

        runCatching { ChatAttachmentFiles.copyInputToFile(failingInput, output) }

        assertFalse(output.exists())
    }

    @Test
    fun copyInputToFileDeletesPartialFileWhenCopyIsCancelled() = runTest {
        val output = temporaryFolder.newFile("cancelled.png")
        output.delete()

        val worker = launch {
            val workerJob = currentCoroutineContext()[Job]!!
            val cancellingInput = object : InputStream() {
                private var deliveredFirstChunk = false

                override fun read(): Int = -1

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (deliveredFirstChunk) return -1
                    deliveredFirstChunk = true
                    buffer[offset] = 1
                    workerJob.cancel()
                    return 1
                }
            }

            ChatAttachmentFiles.copyInputToFile(cancellingInput, output)
        }
        worker.join()

        assertFalse(output.exists())
    }

    @Test
    fun persistedMediaCleanupStaysInsideOwnedChatDirectories() {
        val rootFilesDir = temporaryFolder.newFolder("files")
        val sessionDir = File(rootFilesDir, "attachments/42").apply { mkdirs() }
        val otherSessionDir = File(rootFilesDir, "attachments/99").apply { mkdirs() }
        val ttsDir = File(rootFilesDir, "tts_cache").apply { mkdirs() }
        val image = File(sessionDir, "generated.png").apply { writeBytes(byteArrayOf(1)) }
        val otherSessionImage = File(otherSessionDir, "keep.png").apply { writeBytes(byteArrayOf(2)) }
        val speech = File(ttsDir, "generated.mp3").apply { writeBytes(byteArrayOf(3)) }
        val external = temporaryFolder.newFile("external.png").apply { writeBytes(byteArrayOf(4)) }
        val context = mockk<Context> { every { filesDir } returns rootFilesDir }

        assertTrue(ChatAttachmentFiles.deleteOwnedPersistedMediaFile(context, 42L, image.absolutePath))
        assertTrue(ChatAttachmentFiles.deleteOwnedPersistedMediaFile(context, 42L, speech.absolutePath))
        assertTrue(ChatAttachmentFiles.deleteOwnedPersistedMediaFile(context, 42L, otherSessionImage.absolutePath))
        assertTrue(ChatAttachmentFiles.deleteOwnedPersistedMediaFile(context, 42L, external.absolutePath))

        assertFalse(image.exists())
        assertFalse(speech.exists())
        assertTrue(otherSessionImage.exists())
        assertTrue(external.exists())
    }
}
