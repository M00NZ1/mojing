package com.mojing.app.data.local.dao

import com.mojing.app.data.local.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class MessageRecallPlan(
    val messagesToDelete: List<MessageEntity>,
)

internal object MessageRecallPolicy {
    private fun isGeneratedMedia(metadata: String): Boolean = runCatching {
        val value = Json.parseToJsonElement(metadata).jsonObject
        value["derived_media_version"]?.jsonPrimitive?.content == "1" &&
            value["derived_media_kind"]?.jsonPrimitive?.content in setOf("image", "voice")
    }.getOrDefault(false)

    fun plan(
        target: MessageEntity,
        derivedChildren: List<MessageEntity>,
    ): MessageRecallPlan {
        val ownedChildren = derivedChildren
            .filter { child ->
                child.sessionId == target.sessionId &&
                    child.parentMessageId == target.id &&
                    child.branchId == target.branchId &&
                    !child.includeInContext &&
                    isGeneratedMedia(child.structuredContentJson)
            }
            .distinctBy(MessageEntity::id)
        return MessageRecallPlan(
            messagesToDelete = ownedChildren + target,
        )
    }
}
