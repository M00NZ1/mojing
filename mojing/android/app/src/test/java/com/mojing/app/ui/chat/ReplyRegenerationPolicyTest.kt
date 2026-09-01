package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRegenerationPolicyTest {
    private fun message(
        id: Long,
        speakerType: String,
        branchId: String = "main",
        swipeGroupId: String? = null,
        includeInContext: Boolean = true,
        parentMessageId: Long? = null,
    ) = MessageEntity(
        id = id,
        sessionId = 42L,
        speakerType = speakerType,
        characterId = if (speakerType == "character") 7L else null,
        branchId = branchId,
        parentMessageId = parentMessageId,
        swipeGroupId = swipeGroupId,
        content = "message-$id",
        includeInContext = includeInContext,
        createdAt = id,
    )

    @Test
    fun fourMessageStoryOnlyAllowsFinalCharacterReply() {
        val messages = listOf(
            message(1, "user"),
            message(2, "character"),
            message(3, "user"),
            message(4, "character"),
        )

        assertFalse(ReplyRegenerationPolicy.canRegenerate(messages, messages[1]))
        assertEquals(
            listOf(1L, 2L, 3L),
            ReplyRegenerationPolicy.contextBeforeTarget(messages, messages[3])?.map { it.id },
        )
    }

    @Test
    fun laterPhysicalSwipeVariantKeepsOriginalStoryPosition() {
        val original = message(2, "character", swipeGroupId = "reply-1", includeInContext = false)
        val laterUserMessage = message(3, "user")
        val activeVariant = message(5, "character", swipeGroupId = "reply-1")
        val messages = listOf(message(1, "user"), original, laterUserMessage, activeVariant)

        assertFalse(ReplyRegenerationPolicy.canRegenerate(messages, activeVariant))
    }

    @Test
    fun finalActiveSwipeVariantCanBeRegenerated() {
        val original = message(2, "character", swipeGroupId = "reply-1", includeInContext = false)
        val activeVariant = message(5, "character", swipeGroupId = "reply-1")
        val messages = listOf(message(1, "user"), original, activeVariant)

        assertTrue(ReplyRegenerationPolicy.canRegenerate(messages, activeVariant))
        assertEquals(
            listOf(1L),
            ReplyRegenerationPolicy.contextBeforeTarget(messages, activeVariant)?.map { it.id },
        )
        assertFalse(ReplyRegenerationPolicy.canRegenerate(messages, original))
    }

    @Test
    fun ownedAutoMediaDoesNotBlockFinalReplyRegeneration() {
        val reply = message(2, "character")
        val generatedMedia = message(3, "character", includeInContext = false, parentMessageId = reply.id)
        val messages = listOf(message(1, "user"), reply, generatedMedia)

        assertTrue(ReplyRegenerationPolicy.canRegenerate(messages, reply))
        assertEquals(
            listOf(1L),
            ReplyRegenerationPolicy.contextBeforeTarget(messages, reply)?.map { it.id },
        )
    }

    @Test
    fun unownedExcludedMessageStillBlocksRegeneration() {
        val reply = message(2, "character")
        val legacyUnownedMedia = message(3, "character", includeInContext = false)

        assertFalse(ReplyRegenerationPolicy.canRegenerate(listOf(message(1, "user"), reply, legacyUnownedMedia), reply))
    }

    @Test
    fun swipeTimelineShowsOnlyMediaOwnedByTheActiveReply() {
        val original = message(2, "character", swipeGroupId = "reply-1", includeInContext = false)
        val originalMedia = message(3, "character", includeInContext = false, parentMessageId = original.id)
        val regenerated = message(4, "character", swipeGroupId = "reply-1")
        val regeneratedMedia = message(5, "character", includeInContext = false, parentMessageId = regenerated.id)
        val user = message(1, "user")

        assertEquals(
            listOf(1L, 4L, 5L),
            listOf(user, original, originalMedia, regenerated, regeneratedMedia)
                .toChatDisplayLines().map { it.selectedMessage().id },
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            listOf(
                user,
                original.copy(includeInContext = true),
                originalMedia,
                regenerated.copy(includeInContext = false),
                regeneratedMedia,
            ).toChatDisplayLines().map { it.selectedMessage().id },
        )
    }

    @Test
    fun persistedTailScanIgnoresSiblingVariantsAndOwnedMediaOnly() {
        val replyIds = setOf(2L, 4L)

        assertFalse(
            ReplyRegenerationPolicy.blocksRegenerationAfterTarget(
                message(4, "character", swipeGroupId = "reply-1"),
                swipeGroupId = "reply-1",
                sourceReplyIds = replyIds,
            ),
        )
        assertFalse(
            ReplyRegenerationPolicy.blocksRegenerationAfterTarget(
                message(5, "character", includeInContext = false, parentMessageId = 4L),
                swipeGroupId = "reply-1",
                sourceReplyIds = replyIds,
            ),
        )
        assertTrue(
            ReplyRegenerationPolicy.blocksRegenerationAfterTarget(
                message(6, "user"),
                swipeGroupId = "reply-1",
                sourceReplyIds = replyIds,
            ),
        )
    }

    @Test
    fun nestedBranchUsesItsVisibleLogicalTimeline() {
        val messages = listOf(
            message(1, "user"),
            message(2, "character"),
            message(3, "user", branchId = "branch-2"),
            message(4, "character", branchId = "branch-2"),
        )

        assertEquals(
            listOf(1L, 2L, 3L),
            ReplyRegenerationPolicy.contextBeforeTarget(messages, messages[3])?.map { it.id },
        )
    }

    @Test
    fun narratorAndNonLatestHistoryViewAreRejected() {
        val narrator = message(2, "narrator")
        val messages = listOf(message(1, "user"), narrator)

        assertNull(ReplyRegenerationPolicy.contextBeforeTarget(messages, narrator))
        assertFalse(
            ReplyRegenerationPolicy.canRegenerate(
                listOf(message(1, "user"), message(2, "character")),
                message(2, "character"),
                hasNewerMessages = true,
            ),
        )
    }
}
