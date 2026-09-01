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
    fun recallDeletesOnlyOwnedDerivedMediaWithTarget() {
        val target = message(8L, swipeGroupId = "reply-1", createdAt = 300L)
        val ownedMedia = message(9L, parentMessageId = target.id, includeInContext = false)
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
