package com.mojing.app.ui.chat

import com.mojing.app.domain.engine.ConversationMessageText
import com.mojing.app.domain.engine.StructuredParser

/** 气泡内展示：压缩连续空白与过多换行，减轻模型输出里的「大块空白」观感。 */
object ChatMessageTextFormat {
    data class QuotedBody(val quote: String?, val body: String)

    fun splitQuote(raw: String): QuotedBody {
        val normalized = raw.replace("\r\n", "\n")
        val end = normalized.indexOf("\n\n")
        return if (normalized.startsWith("> ") && end > 0) {
            QuotedBody(normalized.substring(2, end), normalized.substring(end + 2))
        } else QuotedBody(null, raw)
    }

    fun searchPreview(raw: String, speakerType: String?, query: String, maxChars: Int = 120): String {
        val text = ConversationMessageText.forUserVisibleText(raw, speakerType)
            .replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return "（无正文）"
        val size = maxChars.coerceAtLeast(2)
        val needle = query.trim().replace(Regex("\\s+"), " ")
        val hit = if (needle.isEmpty()) 0 else text.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
        var start = (hit - size / 4).coerceAtLeast(0)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
        var end = (start + size).coerceAtMost(text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
    }

    fun forBubbleDisplay(raw: String): String {
        if (raw.isEmpty()) return ""
        return raw
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t\\x0B\\f\\r]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** 用户实际可读的消息正文；供复制、朗读、引用和编辑等气泡动作共用。 */
    fun visibleBody(raw: String): String {
        if (!StructuredParser.isStructured(raw)) return forBubbleDisplay(raw)
        val reply = StructuredParser.parse(raw)
        val parts = buildList {
            reply.narrations.forEach { text ->
                forBubbleDisplay(text).takeIf(String::isNotBlank)?.let { add("🎭 $it") }
            }
            reply.thoughts.forEach { text ->
                forBubbleDisplay(text).takeIf(String::isNotBlank)?.let { add("💭 $it") }
            }
            reply.speeches.forEach { speech ->
                forBubbleDisplay(speech.text).takeIf(String::isNotBlank)?.let(::add)
            }
            forBubbleDisplay(reply.plainText).takeIf(String::isNotBlank)?.let(::add)
        }
        return parts.joinToString("\n\n")
    }

    fun forClipboard(raw: String): String = visibleBody(raw)

    fun quoteSnippet(raw: String, maxChars: Int): String = visibleBody(raw)
        .lineSequence()
        .firstOrNull()
        ?.take(maxChars.coerceAtLeast(0))
        .orEmpty()

    /** 搜索、收藏、分支和会话列表共用的单行摘要。 */
    fun preview(
        raw: String,
        speakerType: String?,
        maxChars: Int,
        emptyText: String = "",
    ): String = ConversationMessageText.forUserVisibleText(raw, speakerType)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxChars.coerceAtLeast(0))
        .ifBlank { emptyText }
}
