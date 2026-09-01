package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageEntity

/**
 * 重新生成只作用于当前故事线最后一条、正在生效的角色回复。
 * 历史消息由“编辑”或“从此处分支”处理，避免把新回复错误追加到故事尾部。
 */
object ReplyRegenerationPolicy {
    fun contextBeforeTarget(
        messages: List<MessageEntity>,
        target: MessageEntity,
        hasNewerMessages: Boolean = false,
    ): List<MessageEntity>? {
        if (hasNewerMessages || target.speakerType != "character" || !target.includeInContext) {
            return null
        }

        val timeline = messages.toChatDisplayLines().map { it.selectedMessage() }
        val targetIndex = timeline.indexOfLast { it.id == target.id }
        if (targetIndex < 0) return null
        val trailing = timeline.drop(targetIndex + 1)
        val sourceReplyIds = setOf(target.id)
        if (trailing.any { !it.isDerivedChildOf(sourceReplyIds) }) return null
        return timeline.take(targetIndex).filter(MessageEntity::includeInContext)
    }

    fun canRegenerate(
        messages: List<MessageEntity>,
        target: MessageEntity,
        hasNewerMessages: Boolean = false,
    ): Boolean = contextBeforeTarget(messages, target, hasNewerMessages) != null

    internal fun blocksRegenerationAfterTarget(
        message: MessageEntity,
        swipeGroupId: String?,
        sourceReplyIds: Set<Long>,
    ): Boolean {
        val isSiblingVariant = swipeGroupId != null && message.swipeGroupId == swipeGroupId
        return !isSiblingVariant && !message.isDerivedChildOf(sourceReplyIds)
    }
}
