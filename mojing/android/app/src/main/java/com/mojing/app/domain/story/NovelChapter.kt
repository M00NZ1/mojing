package com.mojing.app.domain.story

import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.ConversationMessageText
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object NovelChapter {
    class EmptyBodyException : IllegalArgumentException("章节正文为空，请重试")
    private val chapterHeading = Regex("^第([\\s0-9零〇一二两三四五六七八九十百千]+)章.*")
    private val markdownHeading = Regex("^#{1,6}\\s*(.{1,80})$")
    data class LeadingTitle(val title: String, val chapterNumberText: String? = null)
    /** Rename only the leading heading; matching words in the story remain untouched. */
    fun renameContent(content: String, oldTitle: String, newTitle: String): String {
        val title = newTitle.trim()
        require(title.isNotEmpty() && '\n' !in title && '\r' !in title)
        val prefix = Regex("^\\s*(?:<NARRATION>\\s*)?").find(content)!!.value
        val remainder = content.substring(prefix.length)
        val rawFirstLine = remainder.substringBefore('\n')
        val closingTag = rawFirstLine.takeIf { it.trimEnd().endsWith("</NARRATION>") }?.let { "</NARRATION>" }.orEmpty()
        val firstLine = rawFirstLine.trimEnd().removeSuffix(closingTag).trim()
        val normalizedFirstLine = leadingTitleFromFirstLine(firstLine)?.title
        val replacesHeading = oldTitle.isNotBlank() && (firstLine == oldTitle.trim() || normalizedFirstLine == oldTitle.trim()) ||
            oldTitle.isBlank() && normalizedFirstLine != null
        val lineBreak = when {
            "\r\n" in remainder -> "\r\n"
            '\n' in remainder -> "\n"
            else -> ""
        }
        val remainderAfterHeading = closingTag + if (lineBreak.isNotEmpty()) "$lineBreak${remainder.substringAfter('\n', "")}" else ""
        return if (replacesHeading) {
            prefix + title + remainderAfterHeading
        } else prefix + title + "\n\n" + remainder
    }

    /** The same first-line title grammar used by the story directory, including Markdown headings. */
    fun leadingTitleFromFirstLine(firstLine: String): LeadingTitle? = parseLeadingLine(firstLine)

    /** Search the whole preview to preserve directory support for older multiline content. */
    fun leadingTitleFromText(text: String): LeadingTitle? {
        val lines = text.lineSequence().toList()
        return lines.firstNotNullOfOrNull(::parseChapterLine)
            ?: lines.firstNotNullOfOrNull(::parseMarkdownLine)
    }

    private fun parseLeadingLine(line: String): LeadingTitle? = parseChapterLine(line) ?: parseMarkdownLine(line)

    private fun parseChapterLine(line: String): LeadingTitle? {
        val normalized = normalizeLeadingLine(line)
        val match = chapterHeading.matchEntire(normalized) ?: return null
        return LeadingTitle(normalized, match.groupValues[1].trim())
    }

    private fun parseMarkdownLine(line: String): LeadingTitle? {
        val trimmed = line.trim()
        if (!trimmed.startsWith('#')) return null
        val match = markdownHeading.matchEntire(trimmed) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { LeadingTitle(it) }
    }

    private fun normalizeLeadingLine(line: String): String {
        var normalized = line.trim().removeSuffix("</NARRATION>").trim()
        while (normalized.startsWith('#') || normalized.startsWith('>')) normalized = normalized.drop(1).trimStart()
        normalized = normalized.replaceFirst(Regex("^旁白\\s*[:：]\\s*"), "")
        return normalized
    }

    fun incomplete(json: String): Boolean = runCatching { JsonParser.parseString(json).asJsonObject.get("chapter_incomplete")?.asBoolean == true }.getOrDefault(false)
    /** Only the current line's own tail can be completed without rewriting inherited or earlier prose. */
    fun canResumeTail(branchId: String, sourceBranchId: String, json: String): Boolean =
        branchId == sourceBranchId && incomplete(json) && number(json) != null
    fun draftMetadata(json: String, number: Int, title: String): String = JsonParser.parseString(metadata(json, number, title)).asJsonObject.apply { addProperty("chapter_incomplete", true) }.toString()
    fun number(json: String): Int? = runCatching {
        JsonParser.parseString(json).asJsonObject.get("chapter_number")?.asInt?.takeIf { it > 0 }
    }.getOrNull()
    fun title(json: String): String = runCatching {
        JsonParser.parseString(json).asJsonObject.get("chapter_title")?.asString.orEmpty()
    }.getOrDefault("")
    fun metadata(json: String, number: Int, title: String): String {
        require(number > 0 && title.isNotBlank())
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { com.google.gson.JsonObject() }
        root.addProperty("mode", "story_writing")
        root.addProperty("chapter_number", number)
        root.addProperty("chapter_title", title.trim())
        return root.toString()
    }
    fun heading(number: Int, title: String): String = if (chapterHeading.matches(title.trim())) title.trim() else "第 $number 章 ${title.trim()}".trim()
    fun body(message: MessageEntity): String {
        val text = ConversationMessageText.forUserVisibleText(message.content, message.speakerType).trim()
        val heading = title(message.structuredContentJson)
        return if (heading.isNotBlank() && text.lineSequence().firstOrNull()?.trim() == heading) text.substringAfter('\n', "").trim() else text
    }
    fun generated(number: Int, requestedTitle: String, raw: String): Pair<String, String> {
        val cleaned = ConversationMessageText.forUserVisibleText(raw, "narrator").trim()
        val first = cleaned.lineSequence().firstOrNull().orEmpty().trim().trimStart('#', ' ')
        val heading = requestedTitle.trim().ifBlank {
            if (chapterHeading.matches(first)) first else "第 $number 章"
        }
        val body = if (first == heading || chapterHeading.matches(first)) cleaned.substringAfter('\n', "").trim() else cleaned
        if (body.isBlank()) throw EmptyBodyException()
        return heading to "<NARRATION>$heading\n\n$body</NARRATION>"
    }
    suspend fun export(output: OutputStream, title: String, maxId: Long,
        loadPage: suspend (Long, Int) -> List<MessageEntity>): Long {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.appendLine(title.trim().ifBlank { "未命名小说" }).appendLine()
        var cursor = 0L
        var count = 0L
        while (cursor < maxId) {
            currentCoroutineContext().ensureActive()
            val page = loadPage(cursor, 128)
            if (page.isEmpty()) break
            for (message in page) {
                require(message.id > cursor)
                if (message.id > maxId) break
                cursor = message.id
                if (message.speakerType == "user") continue
                val number = number(message.structuredContentJson)
                val chapterTitle = title(message.structuredContentJson)
                if (number != null) writer.appendLine(heading(number, chapterTitle)).appendLine()
                else if (chapterTitle.isNotBlank()) writer.appendLine(chapterTitle).appendLine()
                val body = body(message)
                if (body.isNotBlank()) { writer.appendLine(body).appendLine(); count++ }
            }
            if (page.last().id >= maxId || page.size < 128) break
        }
        writer.flush()
        return count
    }
}
