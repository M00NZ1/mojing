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
    val number = root?.get("chapter_number")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
    val metadataTitle = root?.get("chapter_title")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim() }.getOrNull() }
    val heading = Regex("(?im)^\\s*(?:[#>]+\\s*)?(?:旁白\\s*[:：]\\s*)?(第\\s*([0-9一二三四五六七八九十百]+)\\s*章[^\\n]*)").find(contentPreview)
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
    if (value.all(Char::isDigit)) return value.toIntOrNull()
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    if (value == "十") return 10
    val ten = value.indexOf('十')
    return if (ten >= 0) {
        val high = value.substring(0, ten).mapNotNull(digits::get).joinToString("").toIntOrNull() ?: 1
        val low = value.substring(ten + 1).mapNotNull(digits::get).joinToString("").toIntOrNull() ?: 0
        high * 10 + low
    } else value.mapNotNull(digits::get).joinToString("").toIntOrNull()
}
