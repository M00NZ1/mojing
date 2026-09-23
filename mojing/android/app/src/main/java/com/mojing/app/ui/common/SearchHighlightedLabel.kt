package com.mojing.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/** Literal matching for short catalog labels, with colors from the active theme. */
@Composable
internal fun searchHighlightedLabel(text: String, query: String, enabled: Boolean = true): AnnotatedString {
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    return remember(text, query, enabled, highlight, highlightText) {
        buildAnnotatedString {
            append(text)
            val term = query.trim()
            if (term.isNotEmpty() && enabled) {
                var start = text.indexOf(term, ignoreCase = true)
                while (start >= 0) {
                    addStyle(SpanStyle(background = highlight, color = highlightText, fontWeight = FontWeight.SemiBold), start, start + term.length)
                    start = text.indexOf(term, start + term.length, ignoreCase = true)
                }
            }
        }
    }
}
