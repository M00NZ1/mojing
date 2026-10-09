package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterStateSheet(
    panel: CharacterStatePanel,
    characterName: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember(panel.sessionId, panel.characterId, panel.branchId) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp)
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(characterName.ifBlank { "角色状态" }, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text("角色状态 · ${panel.branchLabel}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "关闭角色状态" }) {
                    Icon(Icons.Outlined.Close, null)
                }
            }
            HorizontalDivider()
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                panel.updatedAt?.let {
                    Text("更新时间：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (panel.loading) {
                    CircularProgressIndicator()
                    Text("正在读取当前角色状态…", style = MaterialTheme.typography.bodyMedium)
                } else if (panel.error != null) {
                    Text(panel.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                } else if (!panel.snapshotIsValid) {
                    Text("这份自动状态已标记为暂不用于回复。", color = MaterialTheme.colorScheme.error)
                    if (panel.hasOriginalText) Text("原内容已保留，可以清除这条自动状态。", style = MaterialTheme.typography.bodySmall)
                    panel.originalPreview?.let { Text("原内容摘要：$it", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                } else if (panel.displayError != null) {
                    Text(panel.displayError, color = MaterialTheme.colorScheme.error)
                    Text("原内容已保留，可以清除这条自动状态。", style = MaterialTheme.typography.bodySmall)
                    panel.originalPreview?.let { Text("原内容摘要：$it", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                } else if (panel.state == null) {
                    Text("当前还没有自动角色状态。对话达到现有自动更新节奏后会更新。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    CharacterStateField("当前情绪", panel.state.mood)
                    CharacterStateField("当前目标", panel.state.currentGoal)
                    CharacterStateField("对用户态度", panel.state.attitudeToUser)
                    CharacterStateList("近期动作", panel.state.recentKeyActions)
                    CharacterStateList("已知事实", panel.state.knownFacts)
                    CharacterStateList("关系变化", panel.state.relationshipChanges)
                }
            }
            val canClear = panel.state != null || !panel.snapshotIsValid || panel.displayError != null
            if (!panel.loading && canClear) {
                OutlinedButton(
                    onClick = { confirmClear = true }, enabled = !panel.clearing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (panel.clearing) "正在清除…" else "清除当前状态") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { if (!panel.clearing) confirmClear = false },
            title = { Text("确认清除角色状态") },
            text = { Text("只清除当前故事线的自动角色状态，不会删除手动记忆、纠正或其他故事线内容。") },
            confirmButton = {
                Button(
                    onClick = { confirmClear = false; onClear() }, enabled = !panel.clearing,
                    modifier = Modifier.semantics { contentDescription = "确认清除角色状态" },
                ) { Text("确认清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }, enabled = !panel.clearing) { Text("取消") } },
        )
    }
}

@Composable
private fun CharacterStateField(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CharacterStateList(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        values.forEach { value ->
            var expanded by remember(value) { mutableStateOf(false) }
            Text("• $value", style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) 8 else 3, overflow = TextOverflow.Ellipsis)
            if (value.length > 120) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "查看较长内容")
            }
        }
    }
}
