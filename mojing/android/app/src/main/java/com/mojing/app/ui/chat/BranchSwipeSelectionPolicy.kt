package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.BranchSwipeSelectionEntity
import com.mojing.app.data.local.entity.MessageEntity

/**
 * 把数据库中的稀疏故事线覆盖投影到当前读取窗口。
 * 返回值只用于展示、Prompt 和导出，不得把投影后的 includeInContext 写回消息表。
 */
internal fun List<MessageEntity>.withEffectiveSwipeSelections(
    selections: List<BranchSwipeSelectionEntity>,
): List<MessageEntity> {
    if (isEmpty()) return this
    val selectedByGroup = selections.associate { it.swipeGroupId to it.selectedMessageId }
    val fallbackByGroup = asSequence()
        .filter { !it.swipeGroupId.isNullOrBlank() }
        .groupBy { requireNotNull(it.swipeGroupId) }
        .mapValues { (groupId, variants) ->
            selectedByGroup[groupId]
                ?: variants.asSequence()
                    .filter(MessageEntity::includeInContext)
                    .maxWithOrNull(compareBy<MessageEntity> { it.createdAt }.thenBy { it.id })
                    ?.id
                ?: variants.maxWith(
                    compareBy<MessageEntity> { it.createdAt }.thenBy { it.id },
                ).id
        }
    return map { message ->
        val groupId = message.swipeGroupId?.takeIf(String::isNotBlank) ?: return@map message
        val selectedMessageId = fallbackByGroup[groupId] ?: return@map message
        val effective = message.id == selectedMessageId
        if (message.includeInContext == effective) message else message.copy(includeInContext = effective)
    }
}
