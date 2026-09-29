package com.mojing.app.ui.chat

import com.mojing.app.domain.engine.StructuredParser
import com.mojing.app.domain.engine.StructuredReply

/** Text prepared before a bubble composes its visible paragraphs. The original message stays in Room. */
internal data class PreparedBubbleText(
    val structuredRenderable: Boolean,
    val narrations: List<String>,
    val thoughts: List<String>,
    val speeches: List<String>,
    val choices: List<String>,
    val plainBody: String,
    val fallbackBody: String,
)

internal fun prepareBubbleText(raw: String, narrator: Boolean): PreparedBubbleText {
    val isStructured = StructuredParser.isStructured(raw)
    val reply = if (isStructured) StructuredParser.parse(raw) else StructuredReply()
    val structuredRenderable = isStructured &&
        (reply.narrations.isNotEmpty() || reply.thoughts.isNotEmpty() ||
            reply.speeches.isNotEmpty() || reply.choices.isNotEmpty())
    return PreparedBubbleText(
        structuredRenderable = structuredRenderable,
        narrations = reply.narrations.map(ChatMessageTextFormat::forBubbleDisplay),
        thoughts = reply.thoughts.map(ChatMessageTextFormat::forBubbleDisplay),
        speeches = reply.speeches.map { ChatMessageTextFormat.forBubbleDisplay(it.text) },
        choices = reply.choices.map(ChatMessageTextFormat::forBubbleDisplay),
        plainBody = if (structuredRenderable && !narrator)
            ChatMessageTextFormat.forBubbleDisplay(reply.plainText) else "",
        fallbackBody = if (narrator || !structuredRenderable)
            ChatMessageTextFormat.forBubbleDisplay(if (isStructured) StructuredParser.stripTags(raw) else raw)
        else "",
    )
}
