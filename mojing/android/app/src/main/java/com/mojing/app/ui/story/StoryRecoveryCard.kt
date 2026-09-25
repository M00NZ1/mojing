package com.mojing.app.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mojing.app.ui.common.MoJingButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun StoryRecoveryCard(
    state: StorySimulationState,
    onSaveOrOpen: () -> Unit,
    onCopy: () -> Unit,
    onDiscard: () -> Unit,
    onNewStory: () -> Unit,
    onRetryRecovery: () -> Unit,
    onCopyRecovery: () -> Unit,
    onDiscardUnreadable: () -> Unit,
    onRetryInterrupted: () -> Unit,
    onDiscardInterrupted: () -> Unit,
) {
    if (!state.isRestoring && !state.isGenerating && state.recoveryError == null && !state.hasPendingStory && state.savedSessionId == null && !state.hasInterruptedGeneration) return
    if (state.isGenerating && !state.hasPendingStory && state.savedSessionId == null && state.recoveryError == null) return
    var expanded by remember(state.storyTitle) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.isRestoring -> {
                    CircularProgressIndicator()
                    Text("正在读取上次创作…")
                }
                state.recoveryError != null -> {
                    Text("恢复创作", style = MaterialTheme.typography.titleMedium)
                    Text(state.recoveryError, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetryRecovery, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) { Text("重新读取") }
                    if (state.canCopyRecoveryData) {
                        OutlinedButton(onClick = onCopyRecovery, modifier = Modifier.fillMaxWidth()) { Text("复制恢复数据") }
                        TextButton(onClick = onDiscardUnreadable, enabled = !state.isSaving) { Text("清除无法读取的草稿") }
                    }
                }
                state.hasPendingStory -> {
                    Text(if (state.recoveredStory) "继续上次创作" else "正文已生成", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(state.storyTitle, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${state.chapterCount} 章 · " + if (state.draftPersisted) "草稿已保存在本机，可稍后继续" else "完整正文保留在当前页面", style = MaterialTheme.typography.bodyMedium)
                    if (state.preview.isNotBlank()) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起预览" else "展开正文预览") }
                        if (expanded) Text(state.preview, Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = onSaveOrOpen, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isSaving) "正在保存…" else if (state.error != null) "重试保存" else "保存并进入会话")
                    }
                    OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text("复制完整正文") }
                    TextButton(onClick = onDiscard, enabled = !state.isSaving) { Text("放弃本次正文") }
                }
                state.hasInterruptedGeneration -> {
                    Text("上次生成中断", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("已保留本机收到的预览，不能代表小说已经生成完成。", style = MaterialTheme.typography.bodyMedium)
                    if (state.preview.isNotBlank()) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起预览" else "展开已收到的预览") }
                        if (expanded) Text(state.preview, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    } else Text("中断前还没有收到可显示的正文。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRetryInterrupted, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                        Text("按当前设定重新生成")
                    }
                    OutlinedButton(onClick = onCopy, enabled = state.preview.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("复制已收到的预览") }
                    TextButton(onClick = onDiscardInterrupted, enabled = !state.isSaving) { Text("放弃这次中断记录") }
                }
                else -> {
                    Text(if (state.savedSessionMissing) "这篇会话已删除" else "上次创作已保存", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(state.storyTitle, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!state.savedSessionMissing) Button(onClick = onSaveOrOpen, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) { Text("打开已保存的会话") }
                    OutlinedButton(onClick = onNewStory, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) { Text("开始新作") }
                }
            }
        }
    }
}
