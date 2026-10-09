package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Keeps the original reading position while the existing owner reloads its message. */
@Composable
internal fun BookmarkReadOnlyDialog(
    sessionId: Long,
    readingBranchId: String,
    messageId: Long,
    body: String?,
    sourceLabel: String?,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
) {
    key(sessionId, readingBranchId, messageId) {
        // Create the saveable scroll state before the delayed body. Never clamp it with a placeholder.
        val scroll = rememberScrollState()
        AlertDialog(
            shape = RoundedCornerShape(16.dp),
            onDismissRequest = onDismiss,
            title = { Text("收藏原文 · 只读") },
            text = {
                Column {
                    Text(
                        sourceLabel?.let { "来自$it。阅读不会切换故事线或采用版本。" }
                            ?: "阅读不会切换故事线或采用版本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        body != null -> Text(body,
                            modifier = Modifier.padding(top = 12.dp)
                                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                                .verticalScroll(scroll),
                            style = MaterialTheme.typography.bodyMedium)
                        error != null -> Text(error, modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.error)
                        else -> Text("正在读取收藏原文…", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            dismissButton = {
                if (error != null) TextButton(onClick = onRetry, enabled = !loading) { Text("重试读取") }
                else TextButton(onClick = onCopy, enabled = body != null && !loading) { Text("复制全文") }
            },
        )
    }
}
