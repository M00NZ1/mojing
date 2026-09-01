package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

@Composable
fun SearchHitSnippet(text: String, query: String, maxLen: Int = 120) {
    val snippet = text.replace('\n', ' ').trim().take(maxLen).ifBlank { "(空)" }
    val q = query.trim()
    if (q.isEmpty()) {
        Text(snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        return
    }
    val hl = MaterialTheme.colorScheme.tertiaryContainer
    val onHl = MaterialTheme.colorScheme.onTertiaryContainer
    val lit = buildAnnotatedString {
        val regex = Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
        var last = 0
        regex.findAll(snippet).forEach { m ->
            if (m.range.first > last) {
                append(snippet.substring(last, m.range.first))
            }
            withStyle(SpanStyle(background = hl, color = onHl)) {
                append(m.value)
            }
            last = m.range.last + 1
        }
        if (last < snippet.length) append(snippet.substring(last))
    }
    Text(lit, style = MaterialTheme.typography.bodySmall, maxLines = 3)
}
