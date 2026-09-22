package com.mojing.app.ui.chat.contents

import com.google.gson.JsonParser
import com.mojing.app.data.local.dao.StoryContentsMessageProjection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoryContentsEntry(
    val messageId: Long,
    val title: String,
    val dateLabel: String,
    val preview: String,
    val chapterNumber: Int?,
    val incomplete: Boolean = false,
)

/** DAO 按 id 倒序返回；目录保持同一顺序，加载更早页面时可直接追加。 */
internal fun List<StoryContentsMessageProjection>.toContentsEntries(): List<StoryContentsEntry> = map { it.toContentsEntry() }

internal fun StoryContentsMessageProjection.toContentsEntry(): StoryContentsEntry {
    val root = runCatching { JsonParser.parseString(structuredContentJson).asJsonObject }.getOrNull()
    val number = com.mojing.app.domain.story.NovelChapter.number(structuredContentJson)
    val metadataTitle = root?.get("chapter_title")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim() }.getOrNull() }
    val heading = Regex("(?im)^\\s*(?:[#>]+\\s*)?(?:旁白\\s*[:：]\\s*)?(第\\s*([0-9零〇一二两三四五六七八九十百千]+)\\s*章[^\\n]*)").find(contentPreview)
    val textTitle = heading?.groupValues?.getOrNull(1)?.trim()
    val textNumber = heading?.groupValues?.getOrNull(2)?.let(::parseChineseChapterNumber)
    val dateLabel = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(createdAt))
    val markdownTitle = Regex("(?im)^\\s*#{1,6}\\s*(.{1,80})$").find(contentPreview)?.groupValues?.getOrNull(1)?.trim()
    val title = metadataTitle.orEmpty().ifBlank { textTitle.orEmpty() }.ifBlank {
        markdownTitle.orEmpty().ifBlank { "片段 · $dateLabel" }
    }
    return StoryContentsEntry(
        incomplete = com.mojing.app.domain.story.NovelChapter.incomplete(structuredContentJson),
        messageId = id,
        title = title,
        dateLabel = dateLabel,
        preview = contentPreview.replace(Regex("\\s+"), " ").trim(),
        chapterNumber = number ?: textNumber,
    )
}

private fun parseChineseChapterNumber(value: String): Int? {
    if (value.all(Char::isDigit)) return value.toIntOrNull()?.takeIf { it > 0 }
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
    if (value.none { it in units }) return value.map { digits[it] ?: return null }
        .joinToString("").toIntOrNull()?.takeIf { it > 0 }
    var total = 0
    var pending: Int? = null
    var previousUnit = 10_000
    for ((index, char) in value.withIndex()) {
        val digit = digits[char]
        if (digit != null) {
            if (pending != null && pending != 0) return null
            pending = digit
        } else {
            val unit = units[char] ?: return null
            if (unit >= previousUnit) return null
            val multiplier = pending ?: if (index == 0 && unit == 10) 1 else return null
            if (multiplier == 0) return null
            total += multiplier * unit
            previousUnit = unit
            pending = null
        }
    }
    return (total + (pending ?: 0)).takeIf { it > 0 }
}
