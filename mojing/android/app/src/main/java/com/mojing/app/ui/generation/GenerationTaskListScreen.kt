package com.mojing.app.ui.generation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun GenerationTaskEntity.isActive() = status in setOf(
    GenerationTaskStatus.QUEUED, GenerationTaskStatus.RUNNING, GenerationTaskStatus.PAUSED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationTaskListScreen(
    onBack: () -> Unit,
    onOpenResult: (GenerationTaskEntity) -> Unit = {},
    viewModel: GenerationTaskListViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val paused by viewModel.queuePaused.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val retryingIds by viewModel.retryingTaskIds.collectAsStateWithLifecycle()
    val message by viewModel.snackbar.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var cancelTarget by remember { mutableStateOf<GenerationTaskEntity?>(null) }
    var detail by remember { mutableStateOf<GenerationTaskEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val active = tasks.count { it.isActive() }
    val failed = tasks.count { it.status == GenerationTaskStatus.FAILED }
    val visible = tasks.filter { when (filter) { 1 -> it.isActive(); 2 -> it.status == GenerationTaskStatus.FAILED; else -> true } }
    GenerationTaskFeedback(message, snackbar, viewModel::consumeSnackbar)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("生成记录") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (paused) if (tasks.any { it.status == GenerationTaskStatus.RUNNING }) "正在完成当前步骤" else "生成已暂停" else if (active > 0) "正在为你的世界添笔" else "每一次灵感，都有迹可循",
                            style = MaterialTheme.typography.titleLarge)
                        Text(if (paused) "当前步骤保存后停下。已保存的内容不会丢失，继续时从原进度接上。"
                            else "$active 项待完成 · $failed 项需要处理。离开此页后，生成会继续。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (paused || active > 0) {
                            Button(enabled = !busy, onClick = { if (paused) viewModel.resumeQueue() else viewModel.pauseQueue() }) {
                                Text(if (busy) "正在处理…" else if (paused) "继续生成" else "暂停生成")
                            }
                        }
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("全部", "进行中", "需处理").forEachIndexed { i, label ->
                        SegmentedButton(selected = filter == i, onClick = { filter = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
                }
            }
            if (visible.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (tasks.isEmpty()) "还没有生成记录" else "这里暂时没有任务", style = MaterialTheme.typography.titleMedium)
                    Text(if (tasks.isEmpty()) "从角色、百科或世界的 AI 创作开始，进度会汇集在这里。" else "可以切换分类查看其他记录。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visible, key = { it.id }) { t ->
                val retrying = t.id in retryingIds
                val color = when (t.status) {
                    GenerationTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                    GenerationTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(kindLabel(t.taskKind), style = MaterialTheme.typography.labelMedium)
                            Text(if (paused && t.status == GenerationTaskStatus.RUNNING) "正在收尾" else statusLabel(t.status),
                                style = MaterialTheme.typography.labelMedium, color = color)
                        }
                        Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (t.progressTotal > 0) {
                            val progress = (t.progressDone.toFloat() / t.progressTotal).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text("已完成 ${t.progressDone} / ${t.progressTotal}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (t.errorMessage.isNotBlank()) {
                            Text(t.errorMessage, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = color)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(dateFormat.format(Date(t.createdAt)), Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (t.isActive()) TextButton(enabled = !busy, onClick = { cancelTarget = t }) { Text("取消") }
                            if (t.status == GenerationTaskStatus.FAILED && isRetryableKind(t.taskKind)) {
                                TextButton(enabled = !busy && !retrying, onClick = { viewModel.retryFailedTask(t) }) {
                                    if (retrying) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(if (retrying) "重新排队中" else "继续尝试")
                                }
                            }
                            TextButton(onClick = { detail = t }) { Text("详情") }
                        }
                        if (t.progressDone > 0 || t.status == GenerationTaskStatus.COMPLETED) {
                            OutlinedButton(onClick = { onOpenResult(t) }, modifier = Modifier.fillMaxWidth()) { Text("查看已生成内容") }
                        }
                    }
                }
            }
            if (tasks.isNotEmpty()) item {
                Text("显示最近 150 项记录，进行中的任务优先。取消和失败均保留已保存内容。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    cancelTarget?.let { t ->
        AlertDialog(onDismissRequest = { cancelTarget = null }, title = { Text("取消这次生成？") },
            text = { Text("${t.title}\n\n已保存 ${t.progressDone} 项内容会保留，剩余部分不再继续。") },
            confirmButton = { TextButton(onClick = { viewModel.cancelTask(t.id); cancelTarget = null }) { Text("取消生成") } },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text("保留任务") } })
    }
    detail?.let { original ->
        val t = tasks.firstOrNull { it.id == original.id } ?: original
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(t.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${kindLabel(t.taskKind)} · ${statusLabel(t.status)}")
                Text("已完成 ${t.progressDone} / ${t.progressTotal}")
                if (t.errorMessage.isNotBlank()) Text(t.errorMessage)
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }
}

private fun isRetryableKind(kind: String): Boolean =
    kind == GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES ||
        kind == GenerationTaskKinds.ENCYCLOPEDIA_META_FILL ||
        kind == GenerationTaskKinds.CHARACTER_PERSONA_AI ||
        kind == GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI

private fun kindLabel(kind: String): String = when (kind) {
    GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES -> "百科条目"
    GenerationTaskKinds.ENCYCLOPEDIA_META_FILL -> "百科扩展字段"
    GenerationTaskKinds.CHARACTER_PERSONA_AI -> "角色人设"
    GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> "世界观模板"
    else -> kind
}

private fun statusLabel(s: String): String = when (s) {
    GenerationTaskStatus.QUEUED -> "排队中"
    GenerationTaskStatus.RUNNING -> "生成中"
    GenerationTaskStatus.PAUSED -> "已暂停"
    GenerationTaskStatus.COMPLETED -> "已完成"
    GenerationTaskStatus.FAILED -> "失败"
    GenerationTaskStatus.CANCELLED -> "已取消"
    else -> s
}

@Composable
internal fun GenerationTaskFeedback(message: String?, host: SnackbarHostState, onConsumed: (String) -> Unit) {
    val consume by rememberUpdatedState(onConsumed)
    LaunchedEffect(message, host) {
        message?.let {
            host.showSnackbar(it)
            consume(it)
        }
    }
}
