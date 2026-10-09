package com.mojing.app.domain.story

import com.google.gson.JsonParser

data class StoryRecoveryResult(
    val chapters: List<StoryChapter>,
    val partialChapter: StoryChapter?,
)

/** Parses independent batch roots and keeps the complete current chapter beyond the UI preview cap. */
object StoryRecoveryParser {
    fun parse(batches: List<String>, completedChapterCount: Int, persistedChapters: List<StoryChapter> = emptyList()): StoryRecoveryResult {
        val complete = mutableListOf<StoryChapter>()
        var partial: StoryChapter? = null
        batches.forEach { raw ->
            splitRoots(raw).forEach { root ->
                val parsed = runCatching { JsonParser.parseString(root).asJsonObject }.getOrNull()
                val items = parsed?.getAsJsonArray("chapters")
                if (items != null) {
                    items.forEach { value ->
                        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        val content = obj.get("content")?.asString ?: obj.get("narrative")?.asString.orEmpty()
                        if (content.isNotBlank()) complete += StoryChapter(
                            chapterNumber(obj, complete.size + 1),
                            obj.get("title")?.asString.orEmpty().ifBlank { "第 ${complete.size + 1} 章" }, content)
                    }
                } else {
                    val scanned = scanChapterObjects(root)
                    scanned.complete.forEach { chapterObject ->
                        chapterObject.toJsonObjectOrNull()?.let { obj ->
                            val content = obj.get("content")?.asString ?: obj.get("narrative")?.asString.orEmpty()
                            if (content.isNotBlank()) complete += StoryChapter(
                                chapterNumber(obj, complete.size + 1),
                                obj.get("title")?.asString.orEmpty().ifBlank { "第 ${complete.size + 1} 章" }, content)
                        }
                    }
                    scanned.partial?.let { partialObject ->
                        val title = stringField(partialObject, "title") ?: "第 ${completedChapterCount + complete.size + 1} 章"
                        val content = stringField(partialObject, "content") ?: stringField(partialObject, "narrative")
                        if (!content.isNullOrBlank()) partial = StoryChapter(completedChapterCount + complete.size + 1, title, content)
                    }
                }
            }
        }
        fun key(chapter: StoryChapter) = chapter.title.trim() to chapter.content.trim()
        val remaining = persistedChapters.groupingBy(::key).eachCount().toMutableMap()
        fun alreadySaved(chapter: StoryChapter): Boolean {
            val k = key(chapter)
            val count = remaining[k] ?: return false
            if (count <= 0) return false
            remaining[k] = count - 1
            return true
        }
        val filtered = if (persistedChapters.isNotEmpty()) complete.filterNot(::alreadySaved)
            else complete.drop(completedChapterCount.coerceAtMost(complete.size))
        val deduped = filtered.mapIndexed { index, chapter -> chapter.copy(number = completedChapterCount + index + 1) }
        val recoveredPartial = partial?.takeUnless(::alreadySaved)
            ?.copy(number = completedChapterCount + deduped.size + 1)
        return StoryRecoveryResult(deduped, recoveredPartial)
    }

    private fun splitRoots(raw: String): List<String> {
        val roots = mutableListOf<String>(); var start = -1; var depth = 0; var quoted = false; var escaped = false
        raw.forEachIndexed { index, c ->
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false; return@forEachIndexed }
            when (c) { '"' -> quoted = true; '{' -> { if (depth++ == 0) start = index }; '}' -> if (--depth == 0 && start >= 0) { roots += raw.substring(start, index + 1); start = -1 } }
        }
        if (start >= 0) roots += raw.substring(start)
        return roots
    }

    private fun chapterNumber(obj: com.google.gson.JsonObject, fallback: Int): Int =
        obj.get("chapter_number")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: Regex("第\\s*(\\d+)\\s*章").find(obj.get("title")?.asString.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: fallback

    private data class ChapterObjectScan(val complete: List<String>, val partial: String?)

    /** Scans only the chapters array, so closed chapter objects survive an unclosed later object. */
    private fun scanChapterObjects(raw: String): ChapterObjectScan {
        val arrayStart = raw.indexOf('[' , raw.indexOf("\"chapters\"")).takeIf { it >= 0 } ?: return ChapterObjectScan(emptyList(), null)
        val complete = mutableListOf<String>()
        var objectStart = -1
        var depth = 0
        var quoted = false
        var escaped = false
        var index = arrayStart + 1
        while (index < raw.length) {
            val c = raw[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> if (depth++ == 0) objectStart = index
                '}' -> if (depth > 0 && --depth == 0 && objectStart >= 0) {
                    complete += raw.substring(objectStart, index + 1)
                    objectStart = -1
                }
                ']' -> if (depth == 0) break
            }
            index++
        }
        return ChapterObjectScan(complete, objectStart.takeIf { it >= 0 }?.let { raw.substring(it) })
    }

    private fun String.toJsonObjectOrNull(): com.google.gson.JsonObject? =
        runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()

    private fun stringField(raw: String, field: String): String? {
        val marker = "\"$field\""
        val key = raw.indexOf(marker); if (key < 0) return null
        val quote = raw.indexOf('"', raw.indexOf(':', key) + 1); if (quote < 0) return null
        val value = StringBuilder()
        var i = quote + 1
        while (i < raw.length) {
            when (val c = raw[i]) {
                '"' -> return value.toString()
                '\\' -> {
                    if (++i >= raw.length) return value.toString().takeIf(String::isNotBlank)
                    when (val escaped = raw[i]) {
                        '"', '\\', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000C')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (i + 4 >= raw.length) return value.toString().takeIf(String::isNotBlank)
                            val hex = raw.substring(i + 1, i + 5)
                            if (!hex.all { it in "0123456789abcdefABCDEF" }) return value.toString().takeIf(String::isNotBlank)
                            value.append(hex.toInt(16).toChar()); i += 4
                        }
                        else -> return value.toString().takeIf(String::isNotBlank)
                    }
                }
                else -> value.append(c)
            }
            i++
        }
        return value.toString().takeIf(String::isNotBlank)
    }
}
