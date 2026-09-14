package com.mojing.app.domain.story

import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.ConversationMessageText
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object NovelChapter {
    /** Rename only the leading heading; matching words in the story remain untouched. */
    fun renameContent(content: String, oldTitle: String, newTitle: String): String {
        val title = newTitle.trim()
        require(title.isNotEmpty() && '\n' !in title && '\r' !in title)
        val prefix = Regex("^\\s*(?:<NARRATION>\\s*)?").find(content)!!.value
        val remainder = content.substring(prefix.length)
        val firstLine = remainder.substringBefore('\n').trim()
        return if (oldTitle.isNotBlank() && firstLine == oldTitle.trim()) {
            prefix + title + remainder.substringAfter('\n', "").let {
                if ('\n' in remainder) "\n$it" else ""
            }
        } else prefix + title + "\n\n" + remainder
    }

    fun incomplete(json: String): Boolean = runCatching { JsonParser.parseString(json).asJsonObject.get("chapter_incomplete")?.asBoolean == true }.getOrDefault(false)
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
    fun heading(number: Int, title: String): String = if (Regex("^第[\\s0-9一二三四五六七八九十百千]+章.*").matches(title.trim())) title.trim() else "第 $number 章 ${title.trim()}".trim()
    fun body(message: MessageEntity): String {
        val text = ConversationMessageText.forUserVisibleText(message.content, message.speakerType).trim()
        val heading = title(message.structuredContentJson)
        return if (heading.isNotBlank() && text.lineSequence().firstOrNull()?.trim() == heading) text.substringAfter('\n', "").trim() else text
    }
    fun generated(number: Int, requestedTitle: String, raw: String): Pair<String, String> {
        val cleaned = ConversationMessageText.forUserVisibleText(raw, "narrator").trim()
        val first = cleaned.lineSequence().firstOrNull().orEmpty().trim().trimStart('#', ' ')
        val heading = requestedTitle.trim().ifBlank {
            if (Regex("^第[\\s0-9一二三四五六七八九十百千]+章.*").matches(first)) first else "第 $number 章"
        }
        val body = if (first == heading || Regex("^第[\\s0-9一二三四五六七八九十百千]+章.*").matches(first)) cleaned.substringAfter('\n', "").trim() else cleaned
        require(body.isNotBlank()) { "章节正文为空，请重试" }
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
