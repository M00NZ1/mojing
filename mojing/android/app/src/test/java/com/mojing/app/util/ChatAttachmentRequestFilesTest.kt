package com.mojing.app.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatAttachmentRequestFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun requestFilesUseExactUuidPrefixAndIgnoreOtherFiles() {
        val root = temporaryFolder.newFolder("files")
        val context = mockk<Context> { every { filesDir } returns root }
        val requestId = "123e4567-e89b-12d3-a456-426614174000"
        val owned = ChatAttachmentFiles.requestMediaFile(context, 7L, requestId, 2)
            .apply { writeBytes(byteArrayOf(1)) }
        File(root, "attachments/7/bundle_${requestId}_3.txt").writeBytes(byteArrayOf(2))
        File(root, "attachments/7/bundle_other_${requestId}_4.bin").writeBytes(byteArrayOf(3))

        val files = ChatAttachmentFiles.enumerateRequestFiles(context, 7L, requestId)

        assertEquals(listOf(owned.canonicalFile), files.map { it.canonicalFile })
    }

    @Test
    fun cleanupChecksReferenceBeforeEachDeleteAndStaysRequestScoped() = runTest {
        val root = temporaryFolder.newFolder("files")
        val context = mockk<Context> { every { filesDir } returns root }
        val requestId = "123e4567-e89b-12d3-a456-426614174000"
        val first = ChatAttachmentFiles.requestMediaFile(context, 8L, requestId, 0)
            .apply { writeBytes(byteArrayOf(1)) }
        val second = ChatAttachmentFiles.requestMediaFile(context, 8L, requestId, 1)
            .apply { writeBytes(byteArrayOf(2)) }
        val other = ChatAttachmentFiles.requestMediaFile(
            context,
            8L,
            "123e4567-e89b-12d3-a456-426614174001",
            0,
        ).apply { writeBytes(byteArrayOf(3)) }
        val checked = mutableListOf<String>()

        val deleted = ChatAttachmentFiles.cleanupRequestFiles(context, 8L, requestId) { path ->
            checked += path
            path == first.absolutePath
        }

        assertEquals(1, deleted)
        assertTrue(checked.contains(first.absolutePath))
        assertTrue(first.exists())
        assertTrue(!second.exists())
        assertTrue(other.exists())
    }

    @Test
    fun copyRequestFileNeverOverwritesExistingPath() = runTest {
        val root = temporaryFolder.newFolder("files")
        val context = mockk<Context> { every { filesDir } returns root }
        val requestId = "123e4567-e89b-12d3-a456-426614174000"
        val output = ChatAttachmentFiles.requestMediaFile(context, 9L, requestId, 0)
            .apply { writeBytes(byteArrayOf(7)) }

        runCatching {
            ChatAttachmentFiles.copyInputToRequestFile(
                context,
                9L,
                requestId,
                0,
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            )
        }

        assertTrue(byteArrayOf(7).contentEquals(output.readBytes()))
    }
    @Test
    fun orphanSweepRechecksLiveRequestAfterReferenceQuery() = runTest {
        val root = temporaryFolder.newFolder("files")
        val context = mockk<Context> { every { filesDir } returns root }
        val request = "123e4567-e89b-12d3-a456-426614174000"
        val file = ChatAttachmentFiles.requestMediaFile(context, 10L, request, 0)
            .apply { writeBytes(byteArrayOf(1)) }
        val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val deleted = ChatAttachmentFiles.cleanupOrphanedBundleFiles(context, 10L, active) {
            active.add(request)
            false
        }
        assertEquals(0, deleted)
        assertTrue(file.exists())
    }

}
