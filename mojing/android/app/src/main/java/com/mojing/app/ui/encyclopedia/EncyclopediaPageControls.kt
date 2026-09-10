package com.mojing.app.ui.encyclopedia

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun EncyclopediaPageControls(page: Int, hasNext: Boolean, loading: Boolean, confirming: Boolean, error: String?,
    previous: () -> Unit, next: () -> Unit, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(previous, enabled = !loading && !confirming && page > 1) { Text("上一页") }
        Text("第 $page 页", style = MaterialTheme.typography.bodySmall)
        TextButton(next, enabled = !loading && !confirming && error == null && hasNext) { Text("下一页") }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(retry, enabled = !loading && !confirming) { Text("重试") }
    }
}
