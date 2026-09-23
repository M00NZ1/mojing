package com.mojing.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.runtime.remember

@Composable
fun ModelOptionRow(name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, query: String = "") {
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    val label = remember(name, query, enabled, highlight, highlightText) {
        buildAnnotatedString {
            append(name)
            val term = query.trim()
            if (term.isNotEmpty() && enabled) {
                var start = name.indexOf(term, ignoreCase = true)
                while (start >= 0) {
                    addStyle(SpanStyle(background = highlight, color = highlightText, fontWeight = FontWeight.SemiBold), start, start + term.length)
                    start = name.indexOf(term, start + term.length, ignoreCase = true)
                }
            }
        }
    }
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        selected -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                Box(Modifier.padding(start = 12.dp).size(20.dp), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Default.Check, "已选择", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
