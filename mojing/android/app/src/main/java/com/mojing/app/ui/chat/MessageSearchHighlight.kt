package com.mojing.app.ui.chat

import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.BreakIterator
import java.util.Locale

/** Map normalized search text back to original grapheme ranges without changing the body. */
internal fun messageSearchRanges(
    text: String,
    query: String,
    onProgress: (() -> Unit)? = null,
): List<IntRange> {
    val needle = MessageSearchTokenizer.normalize(query.trim())
    if (needle.isEmpty() || text.isEmpty()) return emptyList()
    val normalized = StringBuilder()
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        onProgress?.invoke()
        val part = MessageSearchTokenizer.normalize(text.substring(start, end))
        normalized.append(part)
        repeat(part.length) { starts.add(start); ends.add(end) }
        start = end
        end = iterator.next()
    }
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (from <= normalized.length - needle.length) {
        onProgress?.invoke()
        val index = normalized.indexOf(needle, from)
        if (index < 0) break
        val range = starts[index] until ends[index + needle.lastIndex]
        if (ranges.lastOrNull() != range) ranges.add(range)
        from = index + needle.length
    }
    return ranges
}

internal class MessageSearchHighlightResult(
    val ranges: List<IntRange>,
    val annotated: AnnotatedString,
)

internal fun buildMessageSearchHighlight(
    text: String,
    query: String,
    onProgress: (() -> Unit)? = null,
): MessageSearchHighlightResult {
    val ranges = messageSearchRanges(text, query, onProgress)
    return MessageSearchHighlightResult(ranges, highlightedMessageText(text, ranges))
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
/** Preserve original text and offsets while sizing existing paragraph gaps for reader mode. */
private fun readerParagraphs(text: AnnotatedString, gapHeight: TextUnit): AnnotatedString = buildAnnotatedString {
    append(text)
    Regex("\n\n+").findAll(text.text).forEach { gap ->
        addStyle(ParagraphStyle(lineHeight = gapHeight), gap.range.first + 1, gap.range.last + 1)
    }
}

@Composable
internal fun SearchableMessageText(
    text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val reader = LocalChatDensityMetrics.current.bodyFontSp == 17f
    val paragraphGap = with(LocalDensity.current) { 14.dp.toSp() }
    val ink = if (reader && MaterialTheme.colorScheme.background == com.mojing.app.ui.theme.MoJingDesignTokens.background)
        com.mojing.app.ui.theme.MoJingDesignTokens.readerText else color
    val display = remember(text, reader, paragraphGap) { if (reader) readerParagraphs(AnnotatedString(text), paragraphGap) else AnnotatedString(text) }
    val highlight = LocalMessageSearchHighlight.current
    val query = remember(highlight.query) { MessageSearchTokenizer.normalize(highlight.query.trim()) }
    if (query.isEmpty() || text.isEmpty()) {
        Text(display, modifier = modifier, style = style, color = ink, maxLines = maxLines)
        return
    }
    val resultState = remember(text, query) {
        mutableStateOf<MessageSearchHighlightResult?>(null)
    }
    LaunchedEffect(text, query) {
        resultState.value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val result = buildMessageSearchHighlight(text, query) { context.ensureActive() }
            context.ensureActive()
            result
        }
    }
    val highlightResult = resultState.value
    if (highlightResult == null) {
        Text(display, modifier = modifier, style = style, color = ink, maxLines = maxLines)
        return
    }
    val ranges = highlightResult.ranges
    val annotated = remember(highlightResult, reader, paragraphGap) { if (reader) readerParagraphs(highlightResult.annotated, paragraphGap) else highlightResult.annotated }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(text, highlightResult) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(highlight, highlightResult, layout) {
        val result = layout ?: return@LaunchedEffect
        if (!highlight.focus || highlight.focusClaimed || ranges.isEmpty()) return@LaunchedEffect
        highlight.focusClaimed = true
        val line = result.getLineForOffset(ranges.first().first)
        requester.bringIntoView(Rect(0f, result.getLineTop(line), result.size.width.toFloat(), result.getLineBottom(line)))
    }
    Text(annotated, modifier.bringIntoViewRequester(requester), style = style, color = ink,
        maxLines = maxLines, onTextLayout = { layout = it })
}
