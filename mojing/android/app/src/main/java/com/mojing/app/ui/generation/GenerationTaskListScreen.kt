package com.mojing.app.ui.generation

import com.mojing.app.ui.common.MoJingTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import com.mojing.app.ui.common.MoJingTextField as TextField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.domain.generation.CharacterPersonaAiPayload
import com.mojing.app.domain.generation.WorldTemplatePromptAiPayload
import com.google.gson.Gson
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
    val application by viewModel.application.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val filter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTaskKind by viewModel.selectedTaskKind.collectAsStateWithLifecycle()
    val listState = rememberGenerationListState(filter, historyCursors.size, searchQuery, selectedTaskKind)
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
        TopAppBar(
                        expandedHeight = 52.dp,title = { Text(if (browsingHistory) "全部记录" else "生成记录", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
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
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (paused) if (tasks.any { it.status == GenerationTaskStatus.RUNNING }) "正在完成当前步骤" else "生成已暂停" else if (active > 0) "生成队列运行中" else "暂无进行中的任务",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (paused) "当前步骤保存后暂停，可从原进度继续。"
                            else "$active 项待完成 · $failed 项需处理 · 后台继续生成",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (paused || active > 0) {
                            OutlinedButton(enabled = !busy, onClick = { if (paused) viewModel.resumeQueue() else viewModel.pauseQueue() }) {
                                Text(if (busy) "正在处理…" else if (paused) "继续队列" else "暂停队列")
                            }
                        }
                    }
                }
            }
            stickyHeader(key = "generation-filter") {
                Column(Modifier.fillMaxWidth()) {
                    GenerationTaskSearchBar(
                        query = searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                    )
                    GenerationTaskFilterBar(filter = filter, selectedTaskKind = selectedTaskKind,
                        onSelectTaskKind = viewModel::selectTaskKind, onSelect = viewModel::selectHistoryFilter)
                }
            }
            if (visible.isEmpty() && !loading && loadError == null) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hasSearchFilters = searchQuery.isNotBlank() || selectedTaskKind != null
                    Text(if (hasSearchFilters) "没有匹配的生成记录" else if (browsingHistory) "当前分类没有记录" else if (tasks.isEmpty()) "还没有生成记录" else "这里暂时没有任务", style = MaterialTheme.typography.titleMedium)
                    Text(if (hasSearchFilters) "可修改标题关键词或类型筛选。" else if (browsingHistory) "可切换分类或返回近期记录。" else if (tasks.isEmpty()) "从角色、百科或世界的 AI 创作开始，进度会汇集在这里。" else "可以切换分类查看其他记录。",
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
        onApply = viewModel::previewSnapshot,
        onDismiss = { viewModel.cancelResultLookup(); detailId = null }, onOpen = { t ->
            resultError = null
            viewModel.openResult(t, onOpen = { target -> detailId = null; onOpenResult(target) },
                onError = { resultError = it })
        })
    GenerationResultApplySheet(application, viewModel::dismissSnapshot,
        { application.taskId?.let(viewModel::previewSnapshot) }, viewModel::applySnapshot)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerationTaskSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        inputModifier = Modifier.semantics { contentDescription = "搜索生成记录" },
        singleLine = true,
        placeholder = { Text("搜索生成记录") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = onClear) { Icon(Icons.Outlined.Clear, contentDescription = "清除生成记录搜索") } }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun GenerationTaskFilterBar(
    filter: Int,
    selectedTaskKind: String? = null,
    onSelectTaskKind: ((String?) -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("全部", "进行中", "需处理").forEachIndexed { i, label ->
                    SegmentedButton(selected = filter == i, onClick = { onSelect(i) },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            if (onSelectTaskKind != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = selectedTaskKind == null, onClick = { onSelectTaskKind(null) }, label = { Text("全部类型") })
                    listOf(
                        GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                        GenerationTaskKinds.ENCYCLOPEDIA_META_FILL,
                        GenerationTaskKinds.CHARACTER_PERSONA_AI,
                        GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI,
                    ).forEach { kind ->
                        FilterChip(
                            selected = selectedTaskKind == kind,
                            onClick = { onSelectTaskKind(if (selectedTaskKind == kind) null else kind) },
                            label = { Text(kindLabel(kind), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberGenerationListState(filter: Int, page: Int = 0, keyword: String = "", taskKind: String? = null): LazyListState {
    val state = rememberLazyListState()
    var previousFilter by rememberSaveable { mutableIntStateOf(filter) }
    var previousPage by rememberSaveable { mutableIntStateOf(page) }
    var previousKeyword by rememberSaveable { mutableStateOf(keyword) }
    var previousTaskKind by rememberSaveable { mutableStateOf(taskKind) }
    LaunchedEffect(filter, page, keyword, taskKind) {
        if (previousFilter != filter || previousPage != page || previousKeyword != keyword || previousTaskKind != taskKind) {
            previousPage = page
            previousFilter = filter
            previousKeyword = keyword
            previousTaskKind = taskKind
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
    onApply: ((GenerationTaskEntity) -> Unit)? = null,
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
            onApply = onApply?.let { action -> { action(t) } },
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
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),onDismissRequest = { if (!busy) onDismiss() }, title = { Text("取消这次生成？") },
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onApply: (() -> Unit)? = null,
) {
    val detailScroll = rememberScrollState()
    LaunchedEffect(openError, retryError) { if (openError != null || retryError != null) detailScroll.scrollTo(0) }
    ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),onDismissRequest = onDismiss, dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 24.dp)) {
            Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("生成详情", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭生成详情") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).verticalScroll(detailScroll).padding(vertical = 16.dp),
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(kindLabel(task.taskKind), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GenerationTaskStatusLabel(task.status)
                }
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
                    val failed = task.status == GenerationTaskStatus.FAILED
                    Surface(
                        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (task.status == GenerationTaskStatus.COMPLETED) "生成时反馈" else "生成反馈", style = MaterialTheme.typography.titleSmall)
                            GenerationFeedbackText(task.errorMessage)
                        }
                    }
                }
                GenerationResultSnapshotPreview(task, onApply)
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                val actionEnabled = !busy && !retrying && !opening
                val actionError = retryError ?: openError
                actionError?.let {
                    Text(
                        text = if (retryError != null) "继续生成未完成：${it.lineSequence().firstOrNull().orEmpty()}"
                        else "打开失败：${it.lineSequence().firstOrNull().orEmpty()}",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (task.status == GenerationTaskStatus.FAILED && isRetryableKind(task.taskKind) && onRetry != null) {
                    Button(onClick = onRetry, enabled = actionEnabled, modifier = Modifier.fillMaxWidth()) {
                        Text(if (retrying) "重新排队中…" else "继续尝试")
                    }
                }
                val hasResultAction = task.progressDone > 0 || task.status == GenerationTaskStatus.COMPLETED
                val hasCancelAction = task.isActive() && onCancel != null
                if (hasResultAction || hasCancelAction) {
                    val label = if (opening) "正在打开…" else if (openError != null) "重试打开"
                        else if (task.snapshotApplicationLabel() != null) task.currentSnapshotTargetLabel()
                        else "查看已生成内容"
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2,
                    ) {
                        if (hasResultAction) {
                            if (task.status == GenerationTaskStatus.FAILED && isRetryableKind(task.taskKind) && onRetry != null) {
                                OutlinedButton(
                                    onClick = onOpen,
                                    enabled = canOpen && !opening && !retrying,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                ) { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            } else {
                                Button(
                                    onClick = onOpen,
                                    enabled = canOpen && !opening,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                ) { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        if (hasCancelAction) {
                            OutlinedButton(
                                onClick = onCancel!!,
                                enabled = actionEnabled,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) { Text("取消生成") }
                        }
                    }
                }
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

@Composable
private fun GenerationResultSnapshotPreview(task: GenerationTaskEntity, onApply: (() -> Unit)?) {
    val snapshot = remember(task.resultJson) { com.mojing.app.domain.generation.GenerationResultSnapshotCodec.decode(task.resultJson) }
        ?: return
    val clipboard = LocalClipboardManager.current
    val text = when (snapshot) {
        is com.mojing.app.domain.generation.GenerationResultSnapshot.CharacterPersona -> snapshot.personaPrompt
        is com.mojing.app.domain.generation.GenerationResultSnapshot.WorldTemplate -> buildString {
            snapshot.summary?.let { appendLine("摘要：$it") }
            snapshot.worldPrompt?.let { appendLine("世界书：$it") }
        }.trim()
    }
    val current = remember(task.payloadJson, task.taskKind) {
        runCatching {
            when (task.taskKind) {
                GenerationTaskKinds.CHARACTER_PERSONA_AI -> Gson().fromJson(task.payloadJson, CharacterPersonaAiPayload::class.java)
                    .personaPrompt?.takeIf(String::isNotBlank)?.let { "人设：$it" }.orEmpty()
                GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> {
                    val p = Gson().fromJson(task.payloadJson, WorldTemplatePromptAiPayload::class.java)
                    buildString {
                        (p.expectedSummary ?: p.summary)?.takeIf(String::isNotBlank)?.let { appendLine("摘要：$it") }
                        (p.expectedWorldPrompt ?: p.worldPrompt)?.takeIf(String::isNotBlank)?.let { append("世界书：$it") }
                    }.trim()
                }
                else -> ""
            }
        }.getOrDefault("")
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI 结果快照", style = MaterialTheme.typography.titleSmall)
            task.snapshotApplicationLabel()?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            if (current.isNotBlank()) {
                Text("生成时内容", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(current, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            }
            Text("生成内容", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(text, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制结果") }
                if (task.resultAppliedAt == null && onApply != null) {
                    Button(onClick = onApply) { Text("对比并应用") }
                } else if (task.resultAppliedAt != null) {
                    Text("已应用", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

internal fun isRetryableKind(kind: String): Boolean =
    kind == GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES ||
        kind == GenerationTaskKinds.ENCYCLOPEDIA_META_FILL ||
        kind == GenerationTaskKinds.CHARACTER_PERSONA_AI ||
        kind == GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI

internal fun GenerationTaskEntity.snapshotApplicationLabel(): String? {
    if (status != GenerationTaskStatus.COMPLETED) return null
    val snapshot = com.mojing.app.domain.generation.GenerationResultSnapshotCodec.decode(resultJson) ?: return null
    if (snapshot.taskKind != taskKind) return null
    return if (resultAppliedAt == null) "生成已完成 · 待应用" else "生成已完成 · 已应用"
}

internal fun GenerationTaskEntity.currentSnapshotTargetLabel(): String =
    if (taskKind == GenerationTaskKinds.CHARACTER_PERSONA_AI) "查看当前角色" else "查看当前模板"

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
