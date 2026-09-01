package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageAttachmentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MessageGallerySaveTest {

    @Test
    fun `saves only valid image attachments and reports partial failure`() = runTest {
        val requestedPaths = mutableListOf<String>()
        val attachments = listOf(
            attachment(1, "/files/first.png", "image/png"),
            attachment(2, "/files/voice.mp3", "audio/mpeg", assetType = "audio"),
            attachment(3, "", "image/png"),
            attachment(4, "/files/second.jpg", "image/jpeg"),
        )

        val result = saveGalleryImageAttachments(attachments) { attachment ->
            requestedPaths += attachment.storagePath
            if (attachment.id == 4L) Result.failure(Exception("disk full")) else Result.success(Unit)
        }

        assertEquals(listOf("/files/first.png", "/files/second.jpg"), requestedPaths)
        assertEquals(2, result.requestedCount)
        assertEquals(1, result.savedCount)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun `gallery save cancellation stops the remaining attachments`() = runTest {
        val requestedPaths = mutableListOf<String>()
        val attachments = listOf(
            attachment(1, "/files/first.png", "image/png"),
            attachment(2, "/files/second.png", "image/png"),
        )

        try {
            saveGalleryImageAttachments(attachments) { attachment ->
                requestedPaths += attachment.storagePath
                throw CancellationException("leave")
            }
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals(listOf("/files/first.png"), requestedPaths)
    }

    private fun attachment(
        id: Long,
        path: String,
        mimeType: String,
        assetType: String = "image",
    ) = MessageAttachmentEntity(
        id = id,
        messageId = 7,
        assetType = assetType,
        fileName = path.substringAfterLast('/'),
        mimeType = mimeType,
        storagePath = path,
    )
}
