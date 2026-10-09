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
    val canForkChapter: Boolean = false,
    val sourceBranchId: String = "main",
)

/** DAO 按 id 倒序返回；目录保持同一顺序，加载更早页面时可直接追加。 */
internal fun List<StoryContentsMessageProjection>.toContentsEntries(): List<StoryContentsEntry> = map { it.toContentsEntry() }

internal fun StoryContentsMessageProjection.toContentsEntry(): StoryContentsEntry {
    val root = runCatching { JsonParser.parseString(structuredContentJson).asJsonObject }.getOrNull()
    val number = com.mojing.app.domain.story.NovelChapter.number(structuredContentJson)
    val metadataTitle = root?.get("chapter_title")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim() }.getOrNull() }
    val leadingTitle = com.mojing.app.domain.story.NovelChapter.leadingTitleFromText(contentPreview)
    val textTitle = leadingTitle?.title
    val textNumber = leadingTitle?.chapterNumberText?.let(::parseChineseChapterNumber)
    val dateLabel = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(createdAt))
    val title = metadataTitle.orEmpty().ifBlank { textTitle.orEmpty() }.ifBlank {
        "片段 · $dateLabel"
    }
    return StoryContentsEntry(
        incomplete = com.mojing.app.domain.story.NovelChapter.incomplete(structuredContentJson),
        canForkChapter = speakerType == "narrator" && number != null && com.mojing.app.domain.story.NovelChapter.incomplete(structuredContentJson),
        messageId = id,
        title = title,
        dateLabel = dateLabel,
        preview = com.mojing.app.ui.chat.ChatMessageTextFormat
            .sessionListPreview(contentPreview, speakerType, contentPreview.length),
        chapterNumber = number ?: textNumber,
        sourceBranchId = branchId,
    )
}

/** Accept a recorded chapter number as 12, 十二 or 第十二章. Other input remains literal text. */
internal fun contentsQueryChapterNumber(query: String): Int? {
    val number = Regex("^(?:第\\s*)?([0-9零〇一二两三四五六七八九十百千]+)\\s*(?:章)?$")
        .matchEntire(query.trim())?.groupValues?.get(1) ?: return null
    return parseChineseChapterNumber(number)
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
