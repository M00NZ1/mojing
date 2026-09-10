package com.mojing.app.ui.generation

import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun GenerationTaskListScreen(
    onBack: () -> Unit,
    onOpenResult: (com.mojing.app.domain.generation.GenerationResultTarget) -> Unit = {},
    viewModel: GenerationTaskListViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val openingResultId by viewModel.openingResultId.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val paused by viewModel.queuePaused.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val retryingIds by viewModel.retryingTaskIds.collectAsStateWithLifecycle()
    val message by viewModel.snackbar.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberGenerationListState(filter)
    var cancelTargetId by remember { mutableStateOf<Long?>(null) }
    var cancelError by remember(cancelTargetId) { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var resultError by remember(detailId) { mutableStateOf<String?>(null) }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelResultLookup() } }
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
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取生成记录…", style = MaterialTheme.typography.bodySmall)
                }
            }
            loadError?.let { error ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (tasks.isNotEmpty()) Text("已显示的记录保留，更新暂时不可用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = viewModel::retryLoad) { Text("重新读取") }
                        }
                    }
                }
            }
            if (tasks.isNotEmpty() || (!loading && loadError == null)) item {
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
            stickyHeader(key = "generation-filter") {
                GenerationTaskFilterBar(filter) { filter = it }
            }
            if (visible.isEmpty() && !loading && loadError == null) item {
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
                        Text(dateFormat.format(Date(t.createdAt)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (t.isActive()) TextButton(enabled = !busy, onClick = { cancelTargetId = t.id }) { Text("取消") }
                            if (t.status == GenerationTaskStatus.FAILED && isRetryableKind(t.taskKind)) {
                                TextButton(enabled = !busy && !retrying, onClick = { viewModel.retryFailedTask(t) }) {
                                    if (retrying) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(if (retrying) "重新排队中" else "继续尝试")
                                }
                            }
                            TextButton(onClick = { detailId = t.id }) { Text("详情") }
                        }
                        if (t.progressDone > 0 || t.status == GenerationTaskStatus.COMPLETED) {
                            OutlinedButton(enabled = openingResultId == null,
                                onClick = { viewModel.openResult(t, onOpenResult) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (openingResultId == t.id) "正在打开…" else "查看已生成内容")
                            }
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
    GenerationTaskCancelConfirmation(tasks, cancelTargetId, busy,
        onDismiss = { cancelTargetId = null }, error = cancelError, onCancel = { id ->
            cancelError = null
            viewModel.cancelTask(id) { success ->
                if (cancelTargetId == id) {
                    if (success) cancelTargetId = null
                    else cancelError = "取消未完成，请重试。已保存内容仍保留。"
                }
            }
        })
    GenerationTaskDetailHost(tasks, detailId, openingResultId, resultError,
        onDismiss = { viewModel.cancelResultLookup(); detailId = null }, onOpen = { t ->
            resultError = null
            viewModel.openResult(t, onOpen = { target -> detailId = null; onOpenResult(target) },
                onError = { resultError = it })
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerationTaskFilterBar(filter: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            listOf("全部", "进行中", "需处理").forEachIndexed { i, label ->
                SegmentedButton(selected = filter == i, onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
            }
        }
    }
}

@Composable
internal fun rememberGenerationListState(filter: Int): LazyListState {
    val state = rememberLazyListState()
    var previousFilter by rememberSaveable { mutableIntStateOf(filter) }
    LaunchedEffect(filter) {
        if (previousFilter != filter) {
            previousFilter = filter
            state.scrollToItem(0)
        }
    }
    return state
}

@Composable
internal fun GenerationTaskDetailHost(
    tasks: List<GenerationTaskEntity>, selectedId: Long?, openingId: Long?, error: String?,
    onDismiss: () -> Unit, onOpen: (GenerationTaskEntity) -> Unit,
) {
    val latest = tasks.firstOrNull { it.id == selectedId }
    var lastVisible by remember(selectedId) { mutableStateOf<GenerationTaskEntity?>(null) }
    SideEffect { if (latest != null) lastVisible = latest }
    (latest ?: lastVisible)?.let { t ->
        GenerationTaskDetailSheet(t, onDismiss = onDismiss,
            canOpen = openingId == null, opening = openingId == t.id, openError = error,
            onOpen = { onOpen(t) })
    }
}

@Composable
internal fun GenerationTaskCancelConfirmation(
    tasks: List<GenerationTaskEntity>,
    targetId: Long?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCancel: (Long) -> Unit,
    error: String? = null,
) {
    val task = tasks.firstOrNull { it.id == targetId && it.isActive() }
    LaunchedEffect(targetId, task?.id) {
        if (targetId != null && task == null) onDismiss()
    }
    if (task != null) {
        AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("取消这次生成？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${task.title}\n\n已保存 ${task.progressDone} 项内容会保留，剩余部分不再继续。")
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { onCancel(task.id) }) { Text(if (busy) "正在取消…" else "取消生成") } },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("保留任务") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerationTaskDetailSheet(
    task: GenerationTaskEntity,
    onDismiss: () -> Unit,
    canOpen: Boolean,
    onOpen: () -> Unit,
    opening: Boolean = false,
    openError: String? = null,
) {
    val detailScroll = rememberScrollState()
    LaunchedEffect(openError) { if (openError != null) detailScroll.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(detailScroll),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                openError?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("打开失败", style = MaterialTheme.typography.titleSmall)
                            GenerationFeedbackText(error)
                        }
                    }
                }
                Text("${kindLabel(task.taskKind)} · ${statusLabel(task.status)}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(task.title, style = MaterialTheme.typography.headlineSmall) }
                if (task.progressTotal > 0) {
                    LinearProgressIndicator(
                        progress = { (task.progressDone.toFloat() / task.progressTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    Text("已完成 ${task.progressDone} / ${task.progressTotal}")
                }
                if (task.errorMessage.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("生成反馈", style = MaterialTheme.typography.titleSmall)
                            GenerationFeedbackText(task.errorMessage)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.progressDone > 0 || task.status == GenerationTaskStatus.COMPLETED) {
                    Button(onClick = onOpen, enabled = canOpen && !opening, modifier = Modifier.fillMaxWidth()) {
                        Text(if (opening) "正在打开…" else if (openError != null) "重试打开" else "查看已生成内容")
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}

@Composable
internal fun GenerationFeedbackText(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (expanded) {
            SelectionContainer {
                Text(text, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            SelectionContainer {
                Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    onTextLayout = { overflow = it.hasVisualOverflow })
            }
        }
        if (expanded || overflow) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起反馈" else "展开反馈") }
        }
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
