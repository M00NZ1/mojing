package com.mojing.app.data.local.dao

import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRecallPolicyTest {
    private fun message(
        id: Long,
        sessionId: Long = 42L,
        parentMessageId: Long? = null,
        swipeGroupId: String? = null,
        includeInContext: Boolean = true,
        createdAt: Long = id,
    ) = MessageEntity(
        id = id,
        sessionId = sessionId,
        parentMessageId = parentMessageId,
        swipeGroupId = swipeGroupId,
        includeInContext = includeInContext,
        createdAt = createdAt,
    )

    @Test
    fun excludedStoryAndUnknownOrForeignMediaArePreserved() {
        val target = message(8L)
        val excluded = message(9L, parentMessageId = 8, includeInContext = false).copy(content = "手动排除的剧情")
        val malformed = excluded.copy(id = 10, structuredContentJson = "broken")
        val future = excluded.copy(id = 11, structuredContentJson = """{"derived_media_version":2,"derived_media_kind":"voice"}""")
        val otherBranch = excluded.copy(id = 12, branchId = "A", structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}""")
        assertEquals(listOf(target.id), MessageRecallPolicy.plan(target, listOf(excluded, malformed, future, otherBranch)).messagesToDelete.map { it.id })
    }

    @Test
    fun recallDeletesOnlyOwnedDerivedMediaWithTarget() {
        val target = message(8L, swipeGroupId = "reply-1", createdAt = 300L)
        val ownedMedia = message(9L, parentMessageId = target.id, includeInContext = false).copy(structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}""")
        val wrongSessionChild = message(
            10L,
            sessionId = 99L,
            parentMessageId = target.id,
            includeInContext = false,
        )
        val unrelatedContextChild = message(11L, parentMessageId = target.id)
        val plan = MessageRecallPolicy.plan(
            target,
            listOf(ownedMedia, wrongSessionChild, unrelatedContextChild),
        )

        assertEquals(listOf(ownedMedia.id, target.id), plan.messagesToDelete.map { it.id })
    }
}
