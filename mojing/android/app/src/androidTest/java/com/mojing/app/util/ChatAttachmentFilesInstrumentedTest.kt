package com.mojing.app.util

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatAttachmentFilesInstrumentedTest {

    @Test
    fun copyFileUri_copiesBytesToAttachmentsDir() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = 42_001L
        val cacheFile = File(context.cacheDir, "itest_attachment_${System.currentTimeMillis()}.bin")
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        cacheFile.writeBytes(payload)
        val uri = Uri.fromFile(cacheFile)

        val outPath = ChatAttachmentFiles.copyUriToSessionFile(context, uri, sessionId)
        val outFile = File(outPath)
        try {
            assertTrue(outFile.exists())
            assertEquals(payload.size.toLong(), outFile.length())
            assertTrue(payload.contentEquals(outFile.readBytes()))
        } finally {
            cacheFile.delete()
            outFile.delete()
        }
    }

    @Test
    fun pendingValidationAcceptsOnlyReadableFilesOwnedByCurrentSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = 42_002L
        val currentDir = File(context.filesDir, "attachments/$sessionId").apply { mkdirs() }
        val otherDir = File(context.filesDir, "attachments/${sessionId + 1}").apply { mkdirs() }
        val valid = File(currentDir, "valid.png").apply { writeText("image") }
        val foreign = File(otherDir, "foreign.png").apply { writeText("image") }
        val missing = File(currentDir, "missing.png")
        val empty = File(currentDir, "empty.png").apply { createNewFile() }
        try {
            val restored = ChatAttachmentFiles.validPendingPaths(
                context,
                sessionId,
                listOf(valid.absolutePath, foreign.absolutePath, missing.absolutePath, empty.absolutePath),
            )

            assertEquals(listOf(valid.absolutePath), restored)
        } finally {
            valid.delete()
            foreign.delete()
            empty.delete()
            currentDir.delete()
            otherDir.delete()
        }
    }
}
