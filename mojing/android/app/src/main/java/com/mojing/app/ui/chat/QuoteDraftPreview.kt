package com.mojing.app.ui.chat

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun QuoteDraftPreview(text: String, speakerLabel: String = "", onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (speakerLabel.isBlank()) "引用回复" else "引用 · $speakerLabel",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text.ifBlank { "（无文字内容）" }, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "取消引用") }
        }
    }
}
