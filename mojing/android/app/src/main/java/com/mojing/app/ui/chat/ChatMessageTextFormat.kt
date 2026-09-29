package com.mojing.app.ui.chat

import com.mojing.app.domain.engine.ConversationMessageText
import com.mojing.app.domain.engine.StructuredParser

/** 气泡内展示：压缩连续空白与过多换行，减轻模型输出里的「大块空白」观感。 */
object ChatMessageTextFormat {
    data class QuotedBody(val quote: String?, val body: String)

    internal const val ASYNC_BODY_CHAR_THRESHOLD = 8192
    private const val ACTION_PREVIEW_SOURCE_CHARS = 2048

    private val sessionPreviewHiddenTail = Regex("<(?:CHOICES|OPTION|GEN_IMAGE|GEN_SPEECH)\\b", RegexOption.IGNORE_CASE)
    private val sessionPreviewDisplayTag = Regex("</?(?:NARRATION|THOUGHT|SPEECH)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val sessionPreviewDanglingTag = Regex("</?[A-Za-z_]+(?:\\s[^>]*)?$", RegexOption.IGNORE_CASE)

    fun splitQuote(raw: String): QuotedBody {
        val normalized = raw.replace("\r\n", "\n")
        val end = normalized.indexOf("\n\n")
        return if (normalized.startsWith("> ") && end > 0) {
            QuotedBody(normalized.substring(2, end), normalized.substring(end + 2))
        } else QuotedBody(null, raw)
    }

    fun searchPreview(raw: String, speakerType: String?, query: String, maxChars: Int = 120): String {
        val truncatedStructure = speakerType != "user" &&
            (sessionPreviewDisplayTag.containsMatchIn(raw) || sessionPreviewHiddenTail.containsMatchIn(raw)) &&
            !StructuredParser.isStructured(raw)
        val text = (if (truncatedStructure) sessionListPreview(raw, speakerType, raw.length)
            else ConversationMessageText.forUserVisibleText(raw, speakerType))
            .replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return "（无正文）"
        val size = maxChars.coerceAtLeast(2)
        val needle = query.trim().replace(Regex("\\s+"), " ")
        val hit = if (needle.isEmpty()) 0 else messageSearchRanges(text, needle).firstOrNull()?.first ?: 0
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
    fun visibleBody(raw: String, speakerType: String? = null): String {
        if (speakerType == "user" || !StructuredParser.isStructured(raw)) return forBubbleDisplay(raw)
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

    fun forClipboard(raw: String, speakerType: String? = null): String = visibleBody(raw, speakerType)

    fun hasEditChanges(raw: String, speakerType: String?, edited: String): Boolean {
        val candidate = edited.trim()
        return candidate.isNotEmpty() && candidate != visibleBody(raw, speakerType).trim()
    }

    fun quoteSnippet(raw: String, maxChars: Int, speakerType: String? = null): String {
        val ownBody = if (speakerType == null || speakerType == "user") splitQuote(raw).body else raw
        val visible = visibleBody(ownBody, speakerType)
        val line = visible.lineSequence().firstOrNull().orEmpty()
        var end = maxChars.coerceAtLeast(0).coerceAtMost(line.length)
        if (end > 0 && end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
        return line.substring(0, end)
    }

    /** 完整正文已在手时的单行摘要。 */
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

    /** The action sheet needs a short lead-in, not a full chapter parse on the UI thread. */
    fun actionPreview(raw: String, speakerType: String?): String =
        if (raw.length <= ACTION_PREVIEW_SOURCE_CHARS) {
            preview(raw, speakerType, 240, "此消息包含非文本内容")
        } else {
            sessionListPreview(raw.take(ACTION_PREVIEW_SOURCE_CHARS), speakerType, 240)
                .ifBlank { "此消息包含非文本内容" }
        }

    /** List DAOs supply only a prefix; remove incomplete protocol tags before showing it. */
    fun sessionListPreview(rawPrefix: String, speakerType: String?, maxChars: Int): String {
        if (speakerType == "user") return preview(rawPrefix, speakerType, maxChars)
        val visible = ConversationMessageText.forUserVisibleText(rawPrefix, speakerType)
        val beforeHiddenTail = sessionPreviewHiddenTail.find(visible)?.let { visible.substring(0, it.range.first) }
            ?: visible
        return beforeHiddenTail
            .replace(sessionPreviewDisplayTag, "")
            .replace(sessionPreviewDanglingTag, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars.coerceAtLeast(0))
    }
}
