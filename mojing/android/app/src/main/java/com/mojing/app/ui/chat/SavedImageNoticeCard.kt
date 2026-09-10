package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SavedImageNoticeCard(busy: Boolean, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("配图已保存，列表暂未刷新", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpen, enabled = !busy) { Text(if (busy) "请稍候…" else "查看配图") }
        }
    }
}
