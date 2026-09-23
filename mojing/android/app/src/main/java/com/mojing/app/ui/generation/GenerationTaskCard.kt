package com.mojing.app.ui.generation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.ui.common.MoJingButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenerationTaskCard(
    task: GenerationTaskEntity,
    createdLabel: String,
    queuePaused: Boolean,
    busy: Boolean,
    retrying: Boolean,
    canOpen: Boolean,
    opening: Boolean,
    onDetail: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    val failed = task.status == GenerationTaskStatus.FAILED
    val running = task.status == GenerationTaskStatus.RUNNING
    val completed = task.status == GenerationTaskStatus.COMPLETED
    val retryable = failed && isRetryableKind(task.taskKind)
    val hasResult = task.progressDone > 0 || completed
    val colors = MaterialTheme.colorScheme
    val icon = when (task.taskKind) {
        GenerationTaskKinds.CHARACTER_PERSONA_AI -> Icons.Default.PersonOutline
        GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> Icons.Default.Public
        else -> Icons.Default.MenuBook
    }
    Card(
        onClick = onDetail,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${kindLabel(task.taskKind)} · $createdLabel", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                if (task.isActive()) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreHoriz, "任务操作")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("取消生成") }, enabled = !busy,
                                onClick = { menuExpanded = false; onCancel() },
                                leadingIcon = { Icon(Icons.Default.Close, null) })
                        }
                    }
                } else {
                    Icon(Icons.Default.ChevronRight, null, tint = colors.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GenerationTaskStatusLabel(task.status, finishing = queuePaused && running)
                if (task.progressTotal > 0) {
                    Text("已完成 ${task.progressDone} / ${task.progressTotal}",
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            if (task.progressTotal > 0 && !completed) {
                LinearProgressIndicator(
                    progress = { (task.progressDone.toFloat() / task.progressTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (task.errorMessage.isNotBlank()) {
                Text(task.errorMessage, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = if (failed) colors.error else colors.onSurfaceVariant)
            }
            if (retryable || hasResult) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (retryable) {
                        MoJingButton(onClick = onRetry, enabled = !busy && !retrying) {
                            if (retrying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (retrying) "重新排队中…" else "继续尝试")
                        }
                    }
                    if (hasResult) {
                        val label = if (opening) "正在打开…" else if (completed) "查看生成内容" else "查看已保存内容"
                        TextButton(onClick = onOpen, enabled = canOpen && !opening) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GenerationTaskStatusLabel(status: String, finishing: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val tint = when (status) {
        GenerationTaskStatus.FAILED -> colors.error
        GenerationTaskStatus.RUNNING -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(when (status) {
            GenerationTaskStatus.FAILED -> Icons.Default.ErrorOutline
            GenerationTaskStatus.COMPLETED -> Icons.Default.CheckCircleOutline
            GenerationTaskStatus.PAUSED -> Icons.Default.Pause
            GenerationTaskStatus.CANCELLED -> Icons.Default.Close
            else -> Icons.Default.Schedule
        }, null, Modifier.size(16.dp), tint = tint)
        Text(if (finishing) "正在收尾" else statusLabel(status),
            style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
