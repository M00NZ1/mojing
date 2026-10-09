package com.mojing.app.ui.chat.drawer

import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity

internal data class MemorySourceReference(
    val label: String,
    val startMessageId: Long,
    val endMessageId: Long? = null,
)

internal fun SessionMemorySegmentEntity.sourceReference(): MemorySourceReference? {
    val start = startMessageId.takeIf { it > 0L }
    val end = endMessageId.takeIf { it > 0L }
    val target = start ?: end ?: return null
    if (start != null && end != null && start > end) {
        return MemorySourceReference(label = "原文消息 #$target", startMessageId = target)
    }
    val label = if (start != null && end != null && start != end) {
        "原文消息 #$start–#$end"
    } else {
        "原文消息 #$target"
    }
    return MemorySourceReference(
        label = label,
        startMessageId = target,
        endMessageId = end?.takeIf { it != target },
    )
}

internal fun SessionEventNodeEntity.sourceReference(): MemorySourceReference? =
    messageId?.takeIf { it > 0L }?.let { id ->
        MemorySourceReference(label = "原文消息 #$id", startMessageId = id)
    }
