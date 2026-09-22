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
import androidx.compose.material.icons.filled.Close
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

internal fun GenerationTaskEntity.isActive() = status in setOf(
    GenerationTaskStatus.QUEUED, GenerationTaskStatus.RUNNING, GenerationTaskStatus.PAUSED)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun GenerationTaskListScreen(
    onBack: () -> Unit,
    onOpenResult: (com.mojing.app.domain.generation.GenerationResultTarget) -> Unit = {},
    viewModel: GenerationTaskListViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val historyCursors by viewModel.historyCursors.collectAsStateWithLifecycle()
    val hasOlder by viewModel.hasOlder.collectAsStateWithLifecycle()
    val browsingHistory = historyCursors.isNotEmpty()
    val openingResultId by viewModel.openingResultId.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val paused by viewModel.queuePaused.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val retryingIds by viewModel.retryingTaskIds.collectAsStateWithLifecycle()
    val message by viewModel.snackbar.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val filter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val listState = rememberGenerationListState(filter, historyCursors.size)
    var cancelTargetId by remember { mutableStateOf<Long?>(null) }
    var cancelError by remember(cancelTargetId) { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var retryError by remember(detailId) { mutableStateOf<String?>(null) }
    var resultError by remember(detailId) { mutableStateOf<String?>(null) }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelResultLookup() } }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val active = tasks.count { it.isActive() }
    val failed = tasks.count { it.status == GenerationTaskStatus.FAILED }
    val visible = tasks.filter { when (filter) { 1 -> it.isActive(); 2 -> it.status == GenerationTaskStatus.FAILED; else -> true } }
    GenerationTaskFeedback(message, snackbar, viewModel::consumeSnackbar)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(if (browsingHistory) "全部记录" else "生成记录", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = { if (browsingHistory) viewModel.showRecent() else viewModel.showHistory() }) {
                Text(if (browsingHistory) "近期记录" else "全部记录")
            }
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
            if (!browsingHistory && (tasks.isNotEmpty() || (!loading && loadError == null))) item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (paused) if (tasks.any { it.status == GenerationTaskStatus.RUNNING }) "正在完成当前步骤" else "生成已暂停" else if (active > 0) "生成队列运行中" else "暂无进行中的任务",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (paused) "当前步骤保存后停下。已保存的内容不会丢失，继续时从原进度接上。"
                            else "$active 项待完成 · $failed 项需要处理。离开此页后，生成会继续。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (paused || active > 0) {
                            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { if (paused) viewModel.resumeQueue() else viewModel.pauseQueue() }) {
                                Text(if (busy) "正在处理…" else if (paused) "继续生成" else "暂停生成")
                            }
                        }
                    }
                }
            }
            stickyHeader(key = "generation-filter") {
                GenerationTaskFilterBar(filter, viewModel::selectHistoryFilter)
            }
            if (visible.isEmpty() && !loading && loadError == null) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (browsingHistory) "当前分类没有记录" else if (tasks.isEmpty()) "还没有生成记录" else "这里暂时没有任务", style = MaterialTheme.typography.titleMedium)
                    Text(if (browsingHistory) "可切换分类或返回近期记录。" else if (tasks.isEmpty()) "从角色、百科或世界的 AI 创作开始，进度会汇集在这里。" else "可以切换分类查看其他记录。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(if (loading) emptyList() else visible, key = { it.id }) { t ->
                GenerationTaskCard(
                    task = t,
                    createdLabel = dateFormat.format(Date(t.createdAt)),
                    queuePaused = paused,
                    busy = busy,
                    retrying = t.id in retryingIds,
                    canOpen = openingResultId == null,
                    opening = openingResultId == t.id,
                    onDetail = { detailId = t.id },
                    onCancel = { cancelTargetId = t.id },
                    onRetry = { viewModel.retryFailedTask(t) },
                    onOpen = { viewModel.openResult(t, onOpenResult) },
                )
            }
            if (browsingHistory) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !loading && historyCursors.size > 1, onClick = viewModel::newerPage) { Text("上一页") }
                    Text("第 ${historyCursors.size} 页", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !loading && loadError == null && hasOlder, onClick = viewModel::olderPage) { Text("下一页") }
                }
            }
            if (!browsingHistory && tasks.isNotEmpty()) item {
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
        busy = busy, retryingIds = retryingIds, retryError = retryError,
        onRetry = { task ->
            retryError = null
            viewModel.retryFailedTask(task) { success ->
                if (detailId == task.id && !success) retryError = "重新排队未完成，请再试一次。已保存的进度保留。"
            }
        },
        onCancel = { task ->
            viewModel.cancelResultLookup()
            detailId = null
            cancelTargetId = task.id
        },
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
internal fun rememberGenerationListState(filter: Int, page: Int = 0): LazyListState {
    val state = rememberLazyListState()
    var previousFilter by rememberSaveable { mutableIntStateOf(filter) }
    var previousPage by rememberSaveable { mutableIntStateOf(page) }
    LaunchedEffect(filter, page) {
        if (previousFilter != filter || previousPage != page) {
            previousPage = page
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
    busy: Boolean = false,
    retryingIds: Set<Long> = emptySet(),
    retryError: String? = null,
    onRetry: ((GenerationTaskEntity) -> Unit)? = null,
    onCancel: ((GenerationTaskEntity) -> Unit)? = null,
) {
    val latest = tasks.firstOrNull { it.id == selectedId }
    var lastVisible by remember(selectedId) { mutableStateOf<GenerationTaskEntity?>(null) }
    SideEffect { if (latest != null) lastVisible = latest }
    (latest ?: lastVisible)?.let { t ->
        GenerationTaskDetailSheet(t, onDismiss = onDismiss,
            canOpen = openingId == null, opening = openingId == t.id, openError = error,
            busy = busy, retrying = t.id in retryingIds, retryError = retryError,
            onRetry = onRetry?.let { action -> { action(t) } },
            onCancel = onCancel?.let { action -> { action(t) } },
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
    busy: Boolean = false,
    retrying: Boolean = false,
    retryError: String? = null,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val detailScroll = rememberScrollState()
    LaunchedEffect(openError, retryError) { if (openError != null || retryError != null) detailScroll.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 24.dp)) {
            val actionMaxHeight = maxHeight * 0.5f
            Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("生成详情", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭生成详情") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).verticalScroll(detailScroll),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                retryError?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("继续生成未完成", style = MaterialTheme.typography.titleSmall)
                            GenerationFeedbackText(error)
                        }
                    }
                }
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
                SelectionContainer { Text(task.title, style = MaterialTheme.typography.titleLarge) }
                val createdAt = remember(task.createdAt) {
                    SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(task.createdAt))
                }
                Text("创建于 $createdAt", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().heightIn(max = actionMaxHeight).verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.status == GenerationTaskStatus.FAILED && isRetryableKind(task.taskKind) && onRetry != null) {
                    Button(onClick = onRetry, enabled = !busy && !retrying && !opening, modifier = Modifier.fillMaxWidth()) {
                        Text(if (retrying) "重新排队中…" else "继续尝试")
                    }
                }
                if (task.progressDone > 0 || task.status == GenerationTaskStatus.COMPLETED) {
                    val label = if (opening) "正在打开…" else if (openError != null) "重试打开" else "查看已生成内容"
                    if (task.status == GenerationTaskStatus.FAILED && isRetryableKind(task.taskKind) && onRetry != null) {
                        OutlinedButton(onClick = onOpen, enabled = canOpen && !opening && !retrying, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    } else {
                        Button(onClick = onOpen, enabled = canOpen && !opening, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
                if (task.isActive() && onCancel != null) {
                    OutlinedButton(onClick = onCancel, enabled = !busy && !retrying && !opening,
                        modifier = Modifier.fillMaxWidth()) { Text("取消生成") }
                }
            }
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

internal fun isRetryableKind(kind: String): Boolean =
    kind == GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES ||
        kind == GenerationTaskKinds.ENCYCLOPEDIA_META_FILL ||
        kind == GenerationTaskKinds.CHARACTER_PERSONA_AI ||
        kind == GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI

internal fun kindLabel(kind: String): String = when (kind) {
    GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES -> "百科条目"
    GenerationTaskKinds.ENCYCLOPEDIA_META_FILL -> "百科扩展字段"
    GenerationTaskKinds.CHARACTER_PERSONA_AI -> "角色人设"
    GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> "世界观模板"
    else -> kind
}

internal fun statusLabel(s: String): String = when (s) {
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
