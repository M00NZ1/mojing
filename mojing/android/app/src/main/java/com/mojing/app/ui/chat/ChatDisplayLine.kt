package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 单条聊天时间线上的展示单元：无 swipe 时为单条消息；有 swipe 时为同一 [swipeGroupId] 下的多版本。
 */
data class ChatDisplayLine(
    val stableKey: String,
    val swipeGroupId: String?,
    val variants: List<MessageEntity>,
    val selectedIndex: Int,
) {
    fun selectedMessage(): MessageEntity =
        variants[selectedIndex.coerceIn(0, variants.lastIndex)]
}

private fun selectedVariantIndex(variants: List<MessageEntity>): Int =
    variants.indexOfLast { it.includeInContext }.let { index ->
        if (index < 0) variants.lastIndex else index
    }

internal fun MessageEntity.isDerivedChildOf(parentMessageIds: Set<Long>): Boolean =
    !includeInContext && parentMessageId?.let(parentMessageIds::contains) == true

fun List<MessageEntity>.toChatDisplayLines(): List<ChatDisplayLine> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.createdAt }
    val consumed = mutableSetOf<Long>()
    val byGroup = filter { !it.swipeGroupId.isNullOrBlank() }
        .groupBy { it.swipeGroupId!! }
        .mapValues { (_, v) -> v.sortedBy { it.createdAt } }
    val inactiveSwipeVariantIds = byGroup.values.flatMap { variants ->
        val selectedIndex = selectedVariantIndex(variants)
        variants.filterIndexed { index, _ -> index != selectedIndex }.map(MessageEntity::id)
    }.toSet()
    val lines = ArrayList<ChatDisplayLine>(sorted.size)
    for (m in sorted.filterNot { it.isDerivedChildOf(inactiveSwipeVariantIds) }) {
        if (m.id in consumed) continue
        val gid = m.swipeGroupId?.takeIf { it.isNotBlank() }
        if (gid == null) {
            lines.add(ChatDisplayLine("m${m.id}", null, listOf(m), 0))
        } else {
            val vars = byGroup[gid] ?: listOf(m)
            vars.forEach { consumed.add(it.id) }
            val activeIdx = selectedVariantIndex(vars)
            lines.add(ChatDisplayLine("g$gid", gid, vars, activeIdx))
        }
    }
    return lines
}

/**
 * 当前故事线实际送入上下文的展示时间线。
 * 自动配图/语音会追加独立的展示消息，但不能因此把它前面的角色回复判成旧回合。
 */
internal fun List<MessageEntity>.toActiveContextTimeline(): List<MessageEntity> =
    toChatDisplayLines().map(ChatDisplayLine::selectedMessage).filter(MessageEntity::includeInContext)

/** 用于合并展示：同一人连续发言只显示一次昵称（与 QQ / Telegram 类似）。 */
fun MessageEntity.speakerClusterKey(): String = when (speakerType) {
    "user" -> "user"
    "narrator" -> "narrator"
    else -> "character:${characterId ?: 0L}"
}

data class ChatLineUiMeta(
    val line: ChatDisplayLine,
    val senderLabel: String,
    val showSenderHeader: Boolean,
    val timeText: String,
)

fun List<ChatDisplayLine>.decorateChatLineList(
    characterNames: Map<Long, String>,
    userDisplayName: String,
    narratorName: String,
): List<ChatLineUiMeta> {
    if (isEmpty()) return emptyList()
    return mapIndexed { index, line ->
        val msg = line.selectedMessage()
        val prev = getOrNull(index - 1)?.selectedMessage()
        val showSenderHeader = prev == null || prev.speakerClusterKey() != msg.speakerClusterKey()
        val senderLabel = when (msg.speakerType) {
            "user" -> userDisplayName.trim().ifBlank { "我" }
            "narrator" -> narratorName.trim().ifBlank { "旁白" }
            else -> {
                val id = msg.characterId ?: 0L
                characterNames[id]?.trim()?.takeIf { it.isNotEmpty() } ?: "角色"
            }
        }
        ChatLineUiMeta(
            line = line,
            senderLabel = senderLabel,
            showSenderHeader = showSenderHeader,
            timeText = formatChatMessageTime(msg.createdAt),
        )
    }
}

private fun formatChatMessageTime(createdAt: Long): String {
    val zone = ZoneId.systemDefault()
    val msg = Instant.ofEpochMilli(createdAt).atZone(zone)
    val now = ZonedDateTime.now(zone)
    val today = now.toLocalDate()
    val msgDate = msg.toLocalDate()
    val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    return when {
        msgDate == today -> msg.format(hm)
        msgDate == today.minusDays(1) -> "昨天 ${msg.format(hm)}"
        msgDate.year == today.year -> msg.format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault()))
        else -> msg.format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.getDefault()))
    }
}
