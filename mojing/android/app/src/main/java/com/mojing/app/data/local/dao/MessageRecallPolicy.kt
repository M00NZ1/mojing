package com.mojing.app.data.local.dao

import com.mojing.app.data.local.entity.MessageEntity

internal data class MessageRecallPlan(
    val messagesToDelete: List<MessageEntity>,
)

internal object MessageRecallPolicy {
    fun plan(
        target: MessageEntity,
        derivedChildren: List<MessageEntity>,
    ): MessageRecallPlan {
        val ownedChildren = derivedChildren
            .filter { child ->
                child.sessionId == target.sessionId &&
                    child.parentMessageId == target.id &&
                    !child.includeInContext
            }
            .distinctBy(MessageEntity::id)
        return MessageRecallPlan(
            messagesToDelete = ownedChildren + target,
        )
    }
}
