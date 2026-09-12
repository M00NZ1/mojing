package com.mojing.app.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
internal fun StoryGenerationProgressCard(
    state: StorySimulationState,
    onStop: () -> Unit,
    onCopy: (AnnotatedString) -> Unit,
    onRetry: () -> Unit,
    onCopyCompleted: (() -> Unit)? = null,
) {
    if (!state.isGenerating && state.preview.isBlank() && state.error == null) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isGenerating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(state.generationStage ?: "已收到有限预览", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
            state.generationModel?.takeIf(String::isNotBlank)?.let {
                Text(it, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("已用 ${state.generationElapsedMs / 1000} 秒 · ${state.receivedChars} 字" +
                (state.firstContentDelayMs?.let { " · 首字 ${it / 1000} 秒" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.preview.isNotBlank()) {
                Text(state.preview, Modifier.height(180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.hasPendingStory && onCopyCompleted != null) {
                        TextButton(onClick = onCopyCompleted) { Text("复制完整正文") }
                    } else TextButton(onClick = { onCopy(AnnotatedString(state.preview)) }) { Text("复制已接收预览") }
                    if (state.isGenerating) TextButton(onClick = onStop) { Text("停止") }
                }
            } else if (state.isGenerating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在等待模型返回正文…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onStop) { Text("停止") }
                }
            }
            if (state.isGenerating) Text("本轮按开始生成时的设定创作", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasPendingStory && state.preview.isBlank() && onCopyCompleted != null) {
                TextButton(onClick = onCopyCompleted) { Text("复制完整正文") }
            }
            if (!state.isGenerating && !state.isSaving && state.error != null) {
                Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(when { state.savedSessionId != null -> "打开已保存的会话"; state.hasPendingStory -> "重试保存"; else -> "重试" })
                }
            }
        }
    }
}
