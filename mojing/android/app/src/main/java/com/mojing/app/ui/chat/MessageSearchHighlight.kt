package com.mojing.app.ui.chat

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import com.mojing.app.data.local.search.MessageSearchTokenizer
import java.text.BreakIterator
import java.util.Locale

/** Map normalized search text back to original grapheme ranges without changing the body. */
internal fun messageSearchRanges(text: String, query: String): List<IntRange> {
    val needle = MessageSearchTokenizer.normalize(query.trim())
    if (needle.isEmpty() || text.isEmpty()) return emptyList()
    val normalized = StringBuilder()
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val part = MessageSearchTokenizer.normalize(text.substring(start, end))
        normalized.append(part)
        repeat(part.length) { starts.add(start); ends.add(end) }
        start = end
        end = iterator.next()
    }
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (from <= normalized.length - needle.length) {
        val index = normalized.indexOf(needle, from)
        if (index < 0) break
        val range = starts[index] until ends[index + needle.lastIndex]
        if (ranges.lastOrNull() != range) ranges.add(range)
        from = index + needle.length
    }
    return ranges
}

internal class MessageSearchHighlight(val query: String = "", val focus: Boolean = false) {
    var focusClaimed = false
}
internal val LocalMessageSearchHighlight = staticCompositionLocalOf { MessageSearchHighlight() }

internal fun highlightedMessageText(text: String, ranges: List<IntRange>): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range -> addStyle(SpanStyle(
        background = Color(0xFFFFD54F), color = Color(0xFF211A00), fontWeight = FontWeight.Bold,
    ), range.first, range.last + 1) }
}

/** The normal chat text renderer, with optional search marking and first-hit positioning. */
@Composable
internal fun SearchableMessageText(
    text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val highlight = LocalMessageSearchHighlight.current
    val ranges = remember(text, highlight.query) { messageSearchRanges(text, highlight.query) }
    val annotated = remember(text, ranges) { highlightedMessageText(text, ranges) }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val focus = remember(highlight, text) {
        (highlight.focus && !highlight.focusClaimed && ranges.isNotEmpty()).also {
            if (it) highlight.focusClaimed = true
        }
    }
    LaunchedEffect(highlight, layout, focus) {
        val result = layout ?: return@LaunchedEffect
        if (focus) {
            val line = result.getLineForOffset(ranges.first().first)
            requester.bringIntoView(Rect(0f, result.getLineTop(line), result.size.width.toFloat(), result.getLineBottom(line)))
        }
    }
    Text(annotated, modifier.bringIntoViewRequester(requester), style = style, color = color,
        maxLines = maxLines, onTextLayout = { layout = it })
}
