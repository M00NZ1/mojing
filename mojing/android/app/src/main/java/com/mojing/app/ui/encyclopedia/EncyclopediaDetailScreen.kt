package com.mojing.app.ui.encyclopedia

import com.mojing.app.ui.common.MoJingCenterAlignedTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mojing.app.util.ContentDocumentReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.ui.encyclopedia.components.BatchGenerateDialog
import com.mojing.app.ui.encyclopedia.components.EncyclopediaRelationCanvas
import com.mojing.app.ui.encyclopedia.components.GraphEdge
import com.mojing.app.ui.encyclopedia.components.GraphNode
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.domain.encyclopedia.sourceReferences

@Composable
private fun RelationEndpointAction(
    endpoint: com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint?,
    direction: String,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled && endpoint != null, role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
            .semantics { contentDescription = if (endpoint == null) "$direction：资料不可用" else "打开${direction}条目：${endpoint.title}" }
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.mojing.app.ui.common.MoJingCoverImage(endpoint?.coverImagePath.orEmpty(), Modifier.size(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(endpoint?.title ?: "资料不可用", style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (endpoint != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (endpoint == null) "$direction · 无法打开条目" else "$direction · ${ENTRY_TYPE_LABELS[endpoint.entryType] ?: "其他"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val WorldInfoImportMimeTypes = arrayOf(
    "application/json",
    "text/plain",
    "text/markdown",
    "text/*",
    "application/xml",
    "text/xml",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/octet-stream",
    "*/*",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EncyclopediaDetailScreen(
    encyclopediaId: Long,
    onEditEntry: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenGenerationTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onWorldSettings: () -> Unit,
    relationEntryId: Long = 0L,
    onViewSource: (Long) -> Unit = onEditEntry,
    viewModel: EncyclopediaDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entryTypeTabs = remember(state.encyclopedia) { filteredEntryTypeTabs(state.encyclopedia) }
    var sedimentBatchMode by remember(encyclopediaId) { mutableStateOf(false) }
    var sedimentSelected by remember(encyclopediaId) { mutableStateOf(emptySet<Long>()) }
    val sedimentFilter = state.sedimentFilter
    LaunchedEffect(sedimentFilter) { sedimentSelected = emptySet() }
    LaunchedEffect(state.sedimentEntries) {
        val eligible = state.sedimentEntries.filter { it.confidence != "confirmed" }.map { it.id }.toSet()
        sedimentSelected = sedimentSelected.intersect(eligible)
    }
    val filteredSediment = state.sedimentEntries

    LaunchedEffect(encyclopediaId, relationEntryId) { viewModel.load(encyclopediaId, relationEntryId) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.snackbar) {
        val m = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.consumeSnackbar()
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("character") }
    var tlTitle by remember { mutableStateOf("") }
    var tlDescription by remember { mutableStateOf("") }
    var timelineEditorExpanded by rememberSaveable(encyclopediaId) { mutableStateOf(false) }
    var tlTime by remember { mutableStateOf("") }
    var tlOrder by remember { mutableStateOf("0") }
    var timelineEditId by rememberSaveable(encyclopediaId) { mutableStateOf<Long?>(null) }
    val timelineEditTarget = state.timelineEvents.firstOrNull { it.id == timelineEditId && it.entryId == null }
    var editTlTitle by remember { mutableStateOf("") }
    var editTlDescription by remember { mutableStateOf("") }
    var editTlTime by remember { mutableStateOf("") }
    var editTlOrder by remember { mutableStateOf("0") }
    LaunchedEffect(encyclopediaId, state.mainTab, state.timelineLoaded, timelineEditorExpanded) {
        if (state.mainTab == EncyclopediaMainTab.TIMELINE && state.timelineLoaded && !timelineEditorExpanded) {
            val next = state.timelineMaxSortOrder + 1
            tlOrder = next.coerceAtLeast(0).toString()
        }
        if (state.timelineLoaded && timelineEditorExpanded) {
            tlTitle = viewModel.timelineDraft(null, "title")
            tlDescription = viewModel.timelineDraft(null, "description")
            tlTime = viewModel.timelineDraft(null, "time")
            tlOrder = viewModel.timelineDraft(null, "order", (state.timelineMaxSortOrder + 1).coerceAtLeast(0).toString())
        }
    }
    var showRelDialog by remember { mutableStateOf(false) }
    var relFrom by remember { mutableStateOf<com.mojing.app.data.local.dao.EncyclopediaEntryOption?>(null) }
    var relTo by remember { mutableStateOf<com.mojing.app.data.local.dao.EncyclopediaEntryOption?>(null) }
    var relType by remember { mutableStateOf("关联") }
    var relLabel by remember { mutableStateOf("") }
    var relMenuFrom by remember { mutableStateOf(false) }
    var relMenuTo by remember { mutableStateOf(false) }
    var showWorldInfoDialog by remember { mutableStateOf(false) }
    var isReadingWorldInfoDocument by remember { mutableStateOf(false) }
    var worldInfoJsonText by remember { mutableStateOf("") }
    var showBatchMetaConfirm by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showEncyclopediaMenu by remember { mutableStateOf(false) }
    var deleteEntryTarget by remember(encyclopediaId) { mutableStateOf<com.mojing.app.data.local.dao.EncyclopediaEntryOption?>(null) }
    var timelineDetailId by rememberSaveable(encyclopediaId) { mutableStateOf<Long?>(null) }
    var timelineDeleteId by rememberSaveable(encyclopediaId) { mutableStateOf<Long?>(null) }
    val timelineDetailTarget = state.timelineEvents.firstOrNull { it.id == timelineDetailId }
    val timelineDeleteTarget = state.timelineEvents.firstOrNull { it.id == timelineDeleteId }
    val timelineActionsReady = state.isLoaded && state.loadError == null && state.timelineLoaded &&
        !state.timelineLoading && state.timelineError == null
    LaunchedEffect(timelineEditTarget?.id) {
        timelineEditTarget?.let { event ->
            editTlTitle = viewModel.timelineDraft(event.id, "title", event.title)
            editTlDescription = viewModel.timelineDraft(event.id, "description", event.description)
            editTlTime = viewModel.timelineDraft(event.id, "time", event.eventTime)
            editTlOrder = viewModel.timelineDraft(event.id, "order", event.sortOrder.toString())
        }
    }
    LaunchedEffect(state.timelineLoaded, state.timelineLoading, state.timelineError, state.timelineEvents) {
        if (state.timelineLoaded && !state.timelineLoading && state.timelineError == null) {
            if (timelineDetailTarget == null) timelineDetailId = null
            if (timelineEditTarget == null) timelineEditId = null
            if (timelineDeleteTarget == null) timelineDeleteId = null
        }
    }
    var relationDeleteTarget by remember(encyclopediaId) { mutableStateOf<com.mojing.app.data.local.entity.EntryRelationEntity?>(null) }
    var expandedRelationLabels by remember(encyclopediaId) { mutableStateOf(emptySet<Long>()) }
    var pinnedTimelineIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedGraphNodeId by rememberSaveable(encyclopediaId) { mutableStateOf<Long?>(null) }
    var graphSelectionType by rememberSaveable(encyclopediaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.isLoaded, state.relationTypeFilter) {
        if (state.isLoaded) {
            if (graphSelectionType != null && graphSelectionType != state.relationTypeFilter) selectedGraphNodeId = null
            graphSelectionType = state.relationTypeFilter
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val worldInfoOpenDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (state.worldInfoImportBusy || isReadingWorldInfoDocument) return@rememberLauncherForActivityResult
        isReadingWorldInfoDocument = true
        scope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }
                val bytes = ContentDocumentReader.readBytes(
                    context,
                    uri,
                    ContentDocumentReader.WORLD_INFO_IMPORT_MAX_BYTES,
                )
                viewModel.loadWorldInfoImportBytes(bytes, fileName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("读取文件失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                isReadingWorldInfoDocument = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                        expandedHeight = 52.dp,
                title = {
                    Text(
                        text = state.encyclopedia?.name ?: "百科详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    val genBadge = state.activeGenTasks.size
                    IconButton(onClick = onOpenGenerationTasks) {
                        BadgedBox(
                            badge = {
                                if (genBadge > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ) {
                                        Text(
                                            text = if (genBadge > 9) "9+" else genBadge.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.CloudSync,
                                contentDescription = "查看 AI 生成任务",
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showEncyclopediaMenu = true },
                            enabled = !state.worldInfoImportBusy && !isReadingWorldInfoDocument,
                        ) {
                            if (state.worldInfoImportBusy || isReadingWorldInfoDocument) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                            }
                        }
                        DropdownMenu(
                            expanded = showEncyclopediaMenu,
                            onDismissRequest = { showEncyclopediaMenu = false },
                        ) {
                            if (!state.hasPublicLlmKey) {
                                DropdownMenuItem(
                                    text = { Text("配置 AI 线路") },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                    onClick = {
                                        showEncyclopediaMenu = false
                                        onOpenSettings()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("扩展字段补全（文本）", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            if (state.mainTab == EncyclopediaMainTab.ENTRIES) {
                                                "AI 自动补充各条目的缺失字段，完成后直接保存"
                                            } else {
                                                "需先切换到「条目」标签后再使用"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
                                enabled = !state.isEncyclopediaMetaFillQueued && state.mainTab == EncyclopediaMainTab.ENTRIES,
                                onClick = {
                                    showEncyclopediaMenu = false
                                    if (state.mainTab != EncyclopediaMainTab.ENTRIES) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("请先切换到「条目」标签")
                                        }
                                        return@DropdownMenuItem
                                    }
                                    showBatchMetaConfirm = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.worldInfoImportBusy || isReadingWorldInfoDocument) "正在读取文件…"
                                        else "从文件导入设定（WorldInfo / 文本）",
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                                enabled = !state.worldInfoImportBusy && !isReadingWorldInfoDocument,
                                onClick = {
                                    showEncyclopediaMenu = false
                                    worldInfoOpenDocLauncher.launch(WorldInfoImportMimeTypes)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("粘贴导入设定") },
                                leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                                onClick = {
                                    showEncyclopediaMenu = false
                                    showWorldInfoDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重命名百科") },
                                onClick = {
                                    showEncyclopediaMenu = false
                                    viewModel.beginRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("批量新建条目") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    showEncyclopediaMenu = false
                                    showBatchDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.isLoaded && state.loadError == null) when (state.mainTab) {
                EncyclopediaMainTab.GRAPH -> FloatingActionButton(onClick = {
                    if (state.entryCount < 2) {
                        scope.launch {
                            snackbarHostState.showSnackbar("请至少添加 2 条条目后再建立关系")
                        }
                    } else {
                        relFrom = state.relationAnchor
                        relTo = null
                        viewModel.clearRelationError()
                        showRelDialog = true
                    }
                }) { Icon(Icons.Outlined.Link, "添加关系") }
                EncyclopediaMainTab.ENTRIES -> FloatingActionButton(onClick = { viewModel.clearCreateEntryError(); showCreateDialog = true }) {
                    Icon(Icons.Outlined.Add, "新建条目")
                }
                EncyclopediaMainTab.TIMELINE,
                EncyclopediaMainTab.SEDIMENT -> Unit
            }
        }
    ) { padding ->
        if (state.loadError != null) {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "无法打开百科",
                message = state.loadError.orEmpty(),
                actionLabel = "重新加载",
                onAction = { viewModel.load(encyclopediaId, relationEntryId) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else if (!state.isLoaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("正在读取百科…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
        Column(modifier = Modifier.padding(padding)) {
            if (state.mainTab == EncyclopediaMainTab.ENTRIES) state.encyclopedia?.let { world ->
                WorldOverviewHeader(
                    world = world.copy(entryCount = state.entryCount),
                    stats = state.overviewStats,
                )
            }
            com.mojing.app.ui.common.MoJingSectionTabs(
                labels = listOf("条目", "时间线", "关系图", "沉积"),
                selectedIndex = state.mainTab.ordinal,
                onSelect = { index ->
                    viewModel.setMainTab(listOf(EncyclopediaMainTab.ENTRIES,
                        EncyclopediaMainTab.TIMELINE, EncyclopediaMainTab.GRAPH,
                        EncyclopediaMainTab.SEDIMENT)[index])
                },
            )

            // Tabs and the create FAB keep every action available on short landscape windows.
            if (state.mainTab == EncyclopediaMainTab.ENTRIES &&
                androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 600) {
                WorldOverviewActions(
                    onCreateEntry = {
                        viewModel.clearCreateEntryError()
                        showCreateDialog = true
                    },
                    onOpenTimeline = { viewModel.setMainTab(EncyclopediaMainTab.TIMELINE) },
                    onOpenGraph = { viewModel.setMainTab(EncyclopediaMainTab.GRAPH) },
                    onOpenSediment = { viewModel.setMainTab(EncyclopediaMainTab.SEDIMENT) },
                )
            }

            if (state.encyclopedia != null) {
                WorldSettingsAction(onClick = onWorldSettings)
            }

            if (state.mainTab == EncyclopediaMainTab.ENTRIES) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entryTypeTabs.size) { idx ->
                        val (label, type) = entryTypeTabs[idx]
                        FilterChip(
                            selected = state.selectedType == type,
                            onClick = { viewModel.selectType(type) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            when (state.mainTab) {
                EncyclopediaMainTab.ENTRIES -> {
                    EncyclopediaPageControls(state.entryCursors.size, state.entriesHasNext,
                        state.entriesLoading, false, state.entriesError,
                        viewModel::previousEntryPage, viewModel::nextEntryPage, viewModel::reloadEntryPage)
                    if (state.entriesLoading || state.entriesError != null) {
                        Spacer(Modifier.weight(1f))
                    } else if (state.entries.isEmpty()) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Outlined.Article,
                            title = if (state.entryCursors.size > 1) "本页暂无条目" else if (state.selectedType.isBlank()) "还没有百科条目" else "当前分类没有条目",
                            message = if (state.entryCursors.size > 1) {
                                "可返回上一页继续浏览。"
                            } else if (state.selectedType.isBlank()) {
                                "先创建角色、地点或世界规则，让故事有可以持续引用的设定。"
                            } else {
                                "可以切换分类查看，或直接创建一个新条目。"
                            },
                            actionLabel = "新建条目",
                            onAction = { showCreateDialog = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        key(state.selectedType, state.entryCursors) {
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val wide = maxWidth > 840.dp
                                if (wide) {
                                    Row(Modifier.fillMaxSize()) {
                                        LazyColumn(
                                            modifier = Modifier
                                                .width(300.dp)
                                                .fillMaxHeight()
                                        ) {
                                            items(state.entries, key = { it.id }) { entry ->
                                                EncyclopediaSwipeableEntryRow(
                                                    entry = entry,
                                                    isWideLayout = true,
                                                    selected = state.previewEntryId == entry.id,
                                                    onRowClick = { viewModel.setPreviewEntry(entry.id) },
                                                    onToggleFeatured = { viewModel.toggleEntryFeatured(entry.id) },
                                                    onDelete = { viewModel.clearEntryDeleteError(); deleteEntryTarget = com.mojing.app.data.local.dao.EncyclopediaEntryOption(entry.id, entry.title, entry.entryType) },
                                                )
                                            }
                                        }
                                        VerticalDivider(Modifier.width(1.dp).fillMaxHeight())
                                        val preview = state.previewEntry
                                        Column(
                                            modifier = Modifier
                                                .weight(0.58f)
                                                .fillMaxHeight()
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            Text("预览", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            if (preview == null) {
                                                when {
                                                    state.previewLoading -> {
                                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                                        Text("正在读取条目…", style = MaterialTheme.typography.bodyMedium)
                                                    }
                                                    state.previewError != null -> {
                                                        Text(state.previewError.orEmpty(), color = MaterialTheme.colorScheme.error)
                                                        TextButton(onClick = viewModel::retryEntryPreview) { Text("重试预览") }
                                                        TextButton(onClick = viewModel::reloadEntryPage) { Text("刷新列表") }
                                                    }
                                                    else -> Text("选择条目预览", style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            } else {
                                                if (preview.coverImagePath.isNotBlank()) {
                                                    AsyncImage(
                                                        model = avatarImageModel(LocalContext.current, preview.coverImagePath),
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .padding(bottom = 8.dp)
                                                            .fillMaxWidth(0.45f)
                                                            .aspectRatio(2f / 3f),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                }
                                                Text(preview.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text("类型：${ENTRY_TYPE_LABELS[preview.entryType] ?: "其他"}", style = MaterialTheme.typography.labelSmall)
                                                if (preview.summary.isNotBlank()) {
                                                    Text(preview.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                                                }
                                                if (preview.content.isNotBlank()) {
                                                    Text(
                                                        preview.content.take(4000) + if (preview.content.length > 4000) "…" else "",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.padding(top = 8.dp),
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                                    )
                                                }
                                                TextButton(onClick = { onEditEntry(preview.id) }, modifier = Modifier.padding(top = 12.dp)) {
                                                    Text("编辑此条目")
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(state.entries, key = { it.id }) { entry ->
                                            EncyclopediaSwipeableEntryRow(
                                                entry = entry,
                                                isWideLayout = false,
                                                selected = false,
                                                onRowClick = { onEditEntry(entry.id) },
                                                onToggleFeatured = { viewModel.toggleEntryFeatured(entry.id) },
                                                onDelete = { viewModel.clearEntryDeleteError(); deleteEntryTarget = com.mojing.app.data.local.dao.EncyclopediaEntryOption(entry.id, entry.title, entry.entryType) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                EncyclopediaMainTab.TIMELINE -> {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Measure the remaining viewport after the actual top bar, tabs and world action.
                        val scrollTimelineHeader = maxHeight < 320.dp
                        val timelineHeader: @Composable () -> Unit = {
                            Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("时间线", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = {
                                        tlTitle = viewModel.timelineDraft(null, "title")
                                        tlDescription = viewModel.timelineDraft(null, "description")
                                        tlTime = viewModel.timelineDraft(null, "time")
                                        tlOrder = viewModel.timelineDraft(null, "order", state.timelineMaxSortOrder.plus(1).coerceAtLeast(0).toString())
                                        viewModel.clearTimelineSaveError()
                                        timelineEditorExpanded = true
                                    }, enabled = timelineActionsReady && !state.timelineSaving) {
                                        Text("添加事件")
                                    }
                                }
                                var sourceMenuOpen by remember { mutableStateOf(false) }
                                val sourceLabel = when (state.timelineSource) {
                                    "standalone" -> "独立事件"
                                    "linked" -> "关联条目"
                                    else -> "全部事件"
                                }
                                Box {
                                    TextButton(onClick = { sourceMenuOpen = true },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        enabled = !state.timelineSaving && state.timelineDeletingId == null) {
                                        Text("来源：$sourceLabel")
                                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                                        listOf("" to "全部事件", "standalone" to "独立事件", "linked" to "关联条目").forEach { (source, label) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                                sourceMenuOpen = false
                                                viewModel.selectTimelineSource(source)
                                            })
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                if (state.timelineLoading && !state.timelineLoaded) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text("正在读取时间线…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                state.timelineError?.let { error ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                        TextButton(onClick = viewModel::retryTimelinePage) { Text("重试") }
                                    }
                                }
                                if (state.timelineLoaded && state.timelineEvents.isEmpty() && state.timelineError == null) {
                                    Text(
                                        if (state.timelineSource.isEmpty()) "还没有时间线事件，点击添加事件记录故事的转折。" else "没有${sourceLabel}，可切换来源查看其他事件。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 12.dp),
                                    )
                                }
                                if (state.timelineLoaded && state.timelineEvents.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = viewModel::previousTimelinePage,
                                            enabled = state.timelinePageIndex > 0 && !state.timelineLoading) { Text("上一页") }
                                        Text("第 ${state.timelinePageIndex + 1} 页",
                                            modifier = Modifier.weight(1f),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = viewModel::nextTimelinePage,
                                            enabled = state.timelineHasNext && !state.timelineLoading && state.timelineError == null) { Text("下一页") }
                                    }
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            if (!scrollTimelineHeader) timelineHeader()
                            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (scrollTimelineHeader) {
                                    item(key = "timeline-page-controls") { timelineHeader() }
                                }
                                items(
                                    state.timelineEvents.sortedWith(
                                        compareByDescending<TimelineEventEntity> { it.id in pinnedTimelineIds }
                                            .thenBy { it.sortOrder }
                                            .thenBy { it.id },
                                    ),
                                    key = { it.id },
                                ) { ev ->
                                    SwipeRevealListRow(
                                        swipeEnabled = true,
                                        showMenuButton = true,
                                        clickEnabled = timelineActionsReady && !state.timelineSaving && state.timelineDeletingId == null,
                                        isPinned = ev.id in pinnedTimelineIds,
                                        onPinToggle = {
                                            val wasPinned = ev.id in pinnedTimelineIds
                                            pinnedTimelineIds = if (wasPinned) {
                                                pinnedTimelineIds - ev.id
                                            } else {
                                                pinnedTimelineIds + ev.id
                                            }
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (wasPinned) "已取消本页置顶" else "已在本页置顶",
                                                )
                                            }
                                        },
                                        onDelete = { viewModel.clearTimelineDeleteError(); timelineDeleteId = ev.id },
                                        onClick = {
                                            ev.entryId?.let(onEditEntry) ?: run { timelineDetailId = ev.id }
                                        },
                                        menuExtras = { dismissMenu ->
                                            DropdownMenuItem(
                                                text = { Text("查看事件") },
                                                enabled = timelineActionsReady && !state.timelineSaving && state.timelineDeletingId == null,
                                                onClick = { dismissMenu(); timelineDetailId = ev.id },
                                            )
                                            ev.entryId?.let { entryId ->
                                                DropdownMenuItem(
                                                    text = { Text("编辑条目") },
                                                    enabled = timelineActionsReady && !state.timelineSaving && state.timelineDeletingId == null,
                                                    onClick = { dismissMenu(); onEditEntry(entryId) },
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Text(ev.eventTime.ifBlank { "未标注" }, Modifier.width(76.dp).padding(end = 8.dp),
                                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Column(
                                                    modifier = Modifier.width(24.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                ) {
                                                    Text("●", color = MaterialTheme.colorScheme.primary,
                                                        style = MaterialTheme.typography.labelSmall)
                                                    Spacer(Modifier.height(4.dp))
                                                    VerticalDivider(
                                                        modifier = Modifier.height(24.dp).width(1.dp),
                                                        color = MaterialTheme.colorScheme.outlineVariant,
                                                    )
                                                }
                                                Column(Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(ev.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                                        if (ev.id in pinnedTimelineIds) {
                                                            Icon(
                                                                Icons.Outlined.Star,
                                                                contentDescription = "本页置顶",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.padding(start = 6.dp).size(16.dp),
                                                            )
                                                        }
                                                    }
                                                    if (ev.description.isNotBlank()) {
                                                        Text(ev.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                EncyclopediaMainTab.GRAPH -> {
                    val nodeIds = buildSet {
                        state.relations.forEach { add(it.fromEntryId); add(it.toEntryId) }
                    }
                    val nodes = nodeIds.map { GraphNode(it, state.relationEndpoints[it]?.title ?: "资料不可用",
                        state.relationEndpoints[it]?.coverImagePath.orEmpty()) }
                    val edges = state.relations.map { relation ->
                        GraphEdge(
                            relation.fromEntryId,
                            relation.toEntryId,
                            buildString {
                                append(relation.relationType)
                                relation.label.trim().takeIf { it.isNotBlank() }?.let {
                                    append(" · ")
                                    append(it.take(8))
                                        if (it.length > 8) append("…")
                                }
                            },
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "relation_type_filter") {
                            Text("按关联条目类型筛选", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("" to "全部", "character" to "人物", "faction" to "组织", "location" to "地点").forEach { (type, label) ->
                                    FilterChip(selected = state.relationTypeFilter == type,
                                        onClick = { viewModel.selectRelationType(type) }, label = { Text(label) },
                                        enabled = state.relationDeletingId == null && !state.relationSaving,
                                        modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                        }
                        if (state.relationsLoading && !state.relationsLoaded) item {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在读取关系…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.relationsLoadError?.let { error -> item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = viewModel::retryRelationPage) { Text("重试") }
                            }
                        } }
                        if (state.relationsLoaded && nodes.isEmpty() && state.relationsLoadError == null) item {
                            EmptyState(
                                icon = Icons.Outlined.Link,
                                title = if (state.relationTypeFilter.isBlank()) "还没有条目关系" else "当前分类没有关系",
                                message = if (state.relationTypeFilter.isNotBlank()) {
                                    "显示起点或终点属于所选分类的关系，可切换分类或建立新的联系。"
                                } else if (state.entryCount < 2) {
                                    "至少创建两个条目后，才能建立角色、地点或事件之间的关系。"
                                } else {
                                    "建立条目之间的联系，关系图会在这里呈现。"
                                },
                                actionLabel = if (state.entryCount >= 2) "添加关系" else null,
                                onAction = if (state.entryCount >= 2) {
                                    {
                                        relFrom = state.relationAnchor
                                        relTo = null
                                        viewModel.clearRelationError()
                                        showRelDialog = true
                                    }
                                } else null,
                            )
                        }
                        if (nodes.isNotEmpty()) item {
                            Text("当前页关系图", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface)
                            EncyclopediaRelationCanvas(
                                nodes = nodes,
                                edges = edges,
                                highlightId = selectedGraphNodeId,
                                onNodeTap = { selectedGraphNodeId = it }
                            )
                            val selectedNode = nodes.firstOrNull { it.id == selectedGraphNodeId }
                            if (selectedNode != null) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                                ) {
                                    BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
                                        val compact = maxWidth < 360.dp
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                com.mojing.app.ui.common.MoJingCoverImage(selectedNode.imagePath, Modifier.size(48.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(selectedNode.title, style = MaterialTheme.typography.titleSmall)
                                                    Text(ENTRY_TYPE_LABELS[state.relationEndpoints[selectedNode.id]?.entryType] ?: "其他",
                                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (!compact) TextButton(onClick = { onEditEntry(selectedNode.id) }) { Text("打开条目") }
                                            }
                                            if (compact) TextButton(onClick = { onEditEntry(selectedNode.id) }, modifier = Modifier.fillMaxWidth()) { Text("打开条目") }
                                        }
                                    }
                                }
                            }
                        }
                        items(state.relations, key = { it.id }) { r ->
                            val hasLabel = r.label.isNotBlank()
                            val labelExpanded = r.id in expandedRelationLabels
                            var labelOverflow by remember(r.id, r.label) { mutableStateOf(false) }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${r.relationType} →",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = {
                                            viewModel.clearRelationDeleteError()
                                            relationDeleteTarget = r
                                        }, enabled = state.relationDeletingId == null) {
                                            Icon(Icons.Outlined.Close, "删除关系", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    RelationEndpointAction(
                                        endpoint = state.relationEndpoints[r.fromEntryId],
                                        direction = "起点",
                                        enabled = !state.relationsLoading && state.relationsLoadError == null && state.relationDeletingId == null,
                                        onOpen = { onEditEntry(r.fromEntryId) },
                                    )
                                    RelationEndpointAction(
                                        endpoint = state.relationEndpoints[r.toEntryId],
                                        direction = "终点",
                                        enabled = !state.relationsLoading && state.relationsLoadError == null && state.relationDeletingId == null,
                                        onOpen = { onEditEntry(r.toEntryId) },
                                    )
                                    if (hasLabel) {
                                        Column(Modifier.padding(end = 44.dp)) {
                                            if (labelExpanded) {
                                                Text(
                                                    "备注：",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(
                                                    r.label,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                                                )
                                            } else {
                                                Text(
                                                    "备注：${r.label}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    onTextLayout = { labelOverflow = it.hasVisualOverflow },
                                                )
                                            }
                                            if (labelExpanded || labelOverflow) {
                                                TextButton(onClick = {
                                                    expandedRelationLabels = if (labelExpanded) expandedRelationLabels - r.id else expandedRelationLabels + r.id
                                                }) { Text(if (labelExpanded) "收起备注" else "展开备注") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (state.relationsLoaded && state.relations.isNotEmpty()) item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = viewModel::previousRelationPage,
                                    enabled = state.relationPageIndex > 0 && !state.relationsLoading) { Text("上一页") }
                                Text("第 ${state.relationPageIndex + 1} 页",
                                    modifier = Modifier.weight(1f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = viewModel::nextRelationPage,
                                    enabled = state.relationsHasNext && !state.relationsLoading) { Text("下一页") }
                            }
                        }
                    }
                }
                EncyclopediaMainTab.SEDIMENT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "从对话整理的世界资料",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "核对对话原文后确认资料，也可打开条目继续编辑",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = sedimentFilter == "all",
                                    onClick = { viewModel.setSedimentFilter("all") },
                                    label = { Text("全部 ${state.sedimentTotal}") },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = sedimentFilter == "pending",
                                    onClick = { viewModel.setSedimentFilter("pending") },
                                    label = { Text("待核对 ${state.sedimentTotal - state.sedimentConfirmed}") },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = sedimentFilter == "confirmed",
                                    onClick = { viewModel.setSedimentFilter("confirmed") },
                                    label = { Text("已确认 ${state.sedimentConfirmed}") },
                                )
                            }
                        }
                        EncyclopediaPageControls(state.sedimentCursors.size, state.sedimentHasNext,
                            state.sedimentLoading, state.sedimentConfirming, state.sedimentError,
                            viewModel::previousSedimentPage, viewModel::nextSedimentPage, viewModel::reloadSediment)
                        SedimentBatchControls(
                            selecting = sedimentBatchMode, selectedCount = sedimentSelected.size, busy = state.sedimentConfirming || state.sedimentLoading || state.sedimentError != null,
                            onToggle = { sedimentBatchMode = !sedimentBatchMode; sedimentSelected = emptySet() },
                            onSelect = { sedimentSelected = filteredSediment.filter { it.confidence != "confirmed" }.take(100).map { it.id }.toSet() },
                            onClear = { sedimentSelected = emptySet() },
                            onConfirm = { viewModel.confirmSedimentEntries(sedimentSelected) { sedimentSelected = emptySet() } },
                        )
                        if (filteredSediment.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when {
                                        state.sedimentLoading -> "正在读取资料…"
                                        state.sedimentError != null -> ""
                                        state.sedimentCursors.size > 1 -> "本页暂无资料，可返回上一页"
                                        state.sedimentTotal == 0 -> "对话整理出的资料会显示在这里"
                                        sedimentFilter == "pending" -> "所有资料均已确认"
                                        else -> "暂无已确认资料"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            key(sedimentFilter, state.sedimentCursors) {
                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(filteredSediment, key = { it.id }) { entry ->
                                        SwipeRevealListRow(
                                            swipeEnabled = !state.sedimentConfirming && !state.sedimentLoading && state.sedimentError == null,
                                            clickEnabled = !state.sedimentConfirming && !state.sedimentLoading && state.sedimentError == null,
                                            isPinned = entry.isFeatured,
                                            onPinToggle = { viewModel.toggleEntryFeatured(entry.id) },
                                            onDelete = { viewModel.clearEntryDeleteError(); deleteEntryTarget = com.mojing.app.data.local.dao.EncyclopediaEntryOption(entry.id, entry.title, entry.entryType) },
                                            onClick = { onEditEntry(entry.id) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                            ) {
                                                Column(
                                                    Modifier.fillMaxWidth().padding(16.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    if (sedimentBatchMode && entry.confidence != "confirmed") {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Checkbox(checked = entry.id in sedimentSelected,
                                                                modifier = Modifier.semantics { contentDescription = "选择资料：${entry.title}" },
                                                                enabled = !state.sedimentConfirming && !state.sedimentLoading && state.sedimentError == null && (entry.id in sedimentSelected || sedimentSelected.size < 100),
                                                                onCheckedChange = { checked -> sedimentSelected = if (checked) sedimentSelected + entry.id else sedimentSelected - entry.id })
                                                            Text("选择资料", style = MaterialTheme.typography.labelMedium)
                                                        }
                                                    }
                                                    Text(
                                                        entry.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        "${ENTRY_TYPE_LABELS[entry.entryType] ?: "其他"} · ${entryConfidenceLabel(entry.confidence)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (entry.confidence == "confirmed") MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    if (entry.summary.isNotBlank()) {
                                                        Text(
                                                            entry.summary,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            maxLines = 3,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    Text(
                                                        "资料更新：${DateUtils.getRelativeTimeSpanString(
                                                            entry.updatedAt,
                                                            System.currentTimeMillis(),
                                                            DateUtils.MINUTE_IN_MILLIS,
                                                        )}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(top = 4.dp),
                                                    )
                                                    SedimentSourceActions(
                                                        entry = entry,
                                                        busy = state.sedimentConfirming || state.sedimentLoading || state.sedimentError != null,
                                                        onViewSource = { onViewSource(entry.id) },
                                                        onConfirm = { viewModel.confirmSedimentEntries(setOf(entry.id)) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    val worldAnchorReady = state.encyclopedia?.let { enc ->
        sequenceOf(enc.description, enc.genreTags, enc.worldPrompt, enc.antiCheatPrompt)
            .any { it.trim().isNotEmpty() }
    } ?: false

    if (showBatchDialog) {
        BatchGenerateDialog(
            worldAnchorReady = worldAnchorReady,
            onDismiss = { showBatchDialog = false },
            onGenerate = { type, count, minWords, maxWords, context, preNotes, bg, mode ->
                viewModel.batchGenerate(type, count, minWords, maxWords, context, bg, mode, preNotes)
            },
        )
    }

    if (state.worldInfoReviewText != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
            onDismissRequest = { viewModel.dismissWorldInfoReview() },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("导入预览", style = MaterialTheme.typography.titleLarge)
                if (state.worldInfoImportBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "正在调用模型整理，请勿退出本页…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = state.worldInfoReviewText.orEmpty(),
                    onValueChange = { viewModel.updateWorldInfoReviewText(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp),
                    minLines = 10,
                    label = { Text("文件内容") },
                    enabled = !state.worldInfoImportBusy,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = { viewModel.dismissWorldInfoReview() },
                        enabled = !state.worldInfoImportBusy,
                    ) { Text("关闭") }
                    OutlinedButton(
                        onClick = { viewModel.importWorldInfoFromReviewTryParse() },
                        enabled = !state.worldInfoImportBusy,
                    ) { Text("按格式解析") }
                    Button(
                        onClick = { viewModel.importWorldInfoFromReviewWithAi() },
                        enabled = !state.worldInfoImportBusy,
                    ) { Text("智能解析并入库") }
                }
            }
        }
    }

    if (timelineEditorExpanded) {
        val parsedOrder = tlOrder.trim().takeIf { Regex("-?\\d+").matches(it) }?.toIntOrNull()
        val sortError = tlOrder.isNotBlank() && parsedOrder == null
        Dialog(
            onDismissRequest = { if (!state.timelineSaving) timelineEditorExpanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("添加时间线事件", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = tlTitle,
                            onValueChange = { tlTitle = it; viewModel.saveTimelineDraft(null, "title", it); viewModel.clearTimelineSaveError() },
                            label = { Text("事件标题") },
                            singleLine = true,
                            enabled = !state.timelineSaving,
                            modifier = Modifier.fillMaxWidth(),
                            inputModifier = Modifier.semantics { contentDescription = "事件标题" },
                        )
                        OutlinedTextField(
                            value = tlDescription,
                            onValueChange = { tlDescription = it; viewModel.saveTimelineDraft(null, "description", it); viewModel.clearTimelineSaveError() },
                            label = { Text("事件描述（可选）") },
                            minLines = 3,
                            maxLines = 6,
                            enabled = !state.timelineSaving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                            inputModifier = Modifier.semantics { contentDescription = "事件描述" },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = tlTime,
                                onValueChange = { tlTime = it; viewModel.saveTimelineDraft(null, "time", it); viewModel.clearTimelineSaveError() },
                                label = { Text("时间标签") },
                                singleLine = true,
                                enabled = !state.timelineSaving,
                                modifier = Modifier.weight(1f),
                                inputModifier = Modifier.semantics { contentDescription = "时间标签" },
                            )
                            OutlinedTextField(
                                value = tlOrder,
                                onValueChange = { value ->
                                    tlOrder = value
                                    viewModel.saveTimelineDraft(null, "order", value)
                                    viewModel.clearTimelineSaveError()
                                },
                                label = { Text("排序") },
                                singleLine = true,
                                enabled = !state.timelineSaving,
                                isError = sortError,
                                supportingText = if (sortError) ({ Text("请输入有效整数") }) else null,
                                modifier = Modifier.width(100.dp),
                                inputModifier = Modifier.semantics { contentDescription = "排序" },
                            )
                        }
                        state.timelineSaveError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { viewModel.discardTimelineDraft(null); tlTitle = ""; tlDescription = ""; tlTime = ""; tlOrder = "0"; timelineEditorExpanded = false }, enabled = !state.timelineSaving) { Text("放弃草稿") }
                        TextButton(onClick = { timelineEditorExpanded = false }, enabled = !state.timelineSaving) { Text("关闭") }
                        TextButton(
                            onClick = {
                                viewModel.addTimelineEvent(tlTitle, tlDescription, tlTime, parsedOrder ?: 0) { saved ->
                                    if (saved) {
                                        tlTitle = ""
                                        tlDescription = ""
                                        tlTime = ""
                                        tlOrder = "0"
                                        timelineEditorExpanded = false
                                    }
                                }
                            },
                            enabled = tlTitle.isNotBlank() && parsedOrder != null && state.timelineLoaded && !state.timelineLoading && !state.timelineSaving,
                        ) { Text(if (state.timelineSaving) "正在保存…" else "保存事件") }
                    }
                }
            }
        }
    }

    if (showWorldInfoDialog) {
        com.mojing.app.ui.common.MoJingFormDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { showWorldInfoDialog = false },
            title = { Text("粘贴导入设定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            worldInfoOpenDocLauncher.launch(WorldInfoImportMimeTypes)
                        },
                        enabled = !state.worldInfoImportBusy && !isReadingWorldInfoDocument,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (isReadingWorldInfoDocument) "正在读取…" else "从文件选择…") }
                    OutlinedTextField(
                        value = worldInfoJsonText,
                        onValueChange = { worldInfoJsonText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp),
                        minLines = 6,
                        label = { Text("设定文本") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importWorldInfoJson(worldInfoJsonText)
                        showWorldInfoDialog = false
                        worldInfoJsonText = ""
                    }
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showWorldInfoDialog = false }) { Text("取消") } }
        )
    }

    if (showRelDialog) {
        val endpointsReady = relFrom != null && relTo != null && relFrom?.id != relTo?.id
        com.mojing.app.ui.common.MoJingFormDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (!state.relationSaving) showRelDialog = false },
            title = { Text("添加条目关系") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    com.mojing.app.ui.common.MoJingOutlinedButton(
                        onClick = { relMenuFrom = true }, enabled = !state.relationSaving, modifier = Modifier.fillMaxWidth(),
                    ) { Text("从条目：" + (relFrom?.title ?: "请选择"), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    com.mojing.app.ui.common.MoJingOutlinedButton(
                        onClick = { relMenuTo = true }, enabled = !state.relationSaving, modifier = Modifier.fillMaxWidth(),
                    ) { Text("到条目：" + (relTo?.title ?: "请选择"), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (relFrom != null && relTo != null && relFrom?.id == relTo?.id) {
                        Text("请选择两个不同的条目", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    } else if (relFrom == null || relTo == null) {
                        Text("选择起点和终点条目后即可添加关系", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    OutlinedTextField(enabled = !state.relationSaving, value = relType, onValueChange = { relType = it }, label = { Text("关系类型") }, singleLine = true)
                    OutlinedTextField(enabled = !state.relationSaving, value = relLabel, onValueChange = { relLabel = it }, label = { Text("备注（可选）") }, singleLine = true)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    state.relationError?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        val f = relFrom?.id
                        val t = relTo?.id
                        if (f != null && t != null && f != t) {
                            viewModel.addRelation(f, t, relType, relLabel) {
                                showRelDialog = false
                                relLabel = ""
                            }
                        }
                    }, enabled = endpointsReady && !state.relationSaving) {
                        Text(if (state.relationSaving) "正在保存…" else "添加关系")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showRelDialog = false }, enabled = !state.relationSaving) { Text("取消") } }
        )
    }

    if (showRelDialog && (relMenuFrom || relMenuTo)) {
        EncyclopediaEntryPicker(
            encyclopediaId = encyclopediaId,
            selectedId = if (relMenuFrom) relFrom?.id else relTo?.id,
            loadPage = viewModel::relationOptions,
            onDismiss = { relMenuFrom = false; relMenuTo = false },
            onSelect = { entry ->
                if (relMenuFrom) relFrom = entry else relTo = entry
                relMenuFrom = false; relMenuTo = false
            },
        )
    }



    if (showBatchMetaConfirm) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { showBatchMetaConfirm = false },
            title = { Text("批量补全扩展字段（文本）") },
            text = {
                Text(
                    "将对当前分类的 ${state.filteredEntryCount} 条条目依次补全缺失的扩展字段（不覆盖已有内容），任务在后台队列执行，完成后自动保存。条目较多时请耐心等待并保持网络畅通。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchMetaConfirm = false
                    viewModel.batchAiFillMetaForCurrentEntries()
                }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { showBatchMetaConfirm = false }) { Text("取消") } },
        )
    }

    timelineDeleteTarget?.let { event ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (state.timelineDeletingId != event.id) timelineDeleteId = null },
            title = { Text("确认删除时间线事件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("确定删除「${event.title.ifBlank { "未命名事件" }}」吗？删除后将无法恢复。")
                    state.timelineDeleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTimelineEvent(event.id) { deleted ->
                        if (deleted) {
                            pinnedTimelineIds = pinnedTimelineIds - event.id
                            timelineDeleteId = null
                            scope.launch { snackbarHostState.showSnackbar("时间线事件已删除") }
                        }
                    }
                }, enabled = timelineActionsReady && state.timelineDeletingId != event.id) {
                    Text(if (state.timelineDeletingId == event.id) "正在删除…" else "删除",
                        color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { timelineDeleteId = null },
                enabled = state.timelineDeletingId != event.id) { Text("取消") } },
        )
    }

    relationDeleteTarget?.let { relation ->
        val deleting = state.relationDeletingId == relation.id
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (!deleting) relationDeleteTarget = null },
            title = { Text("确认删除关系") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("确定删除「${state.relationEndpoints[relation.fromEntryId]?.title ?: "资料不可用"} —[${relation.relationType}]→ ${state.relationEndpoints[relation.toEntryId]?.title ?: "资料不可用"}」吗？")
                    state.relationDeleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRelation(relation.id) { deleted ->
                        if (deleted) {
                            relationDeleteTarget = null
                            scope.launch { snackbarHostState.showSnackbar("关系已删除") }
                        }
                    }
                }, enabled = !deleting) {
                    Text(if (deleting) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { relationDeleteTarget = null }, enabled = !deleting) { Text("取消") }
            },
        )
    }

    timelineDetailTarget?.let { event ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { timelineDetailId = null },
            title = { Text(event.title.ifBlank { "时间线事件" }) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("时间：${event.eventTime.ifBlank { "未标注" }}")
                    Text("排序：${event.sortOrder}")
                    if (event.description.isNotBlank()) Text(event.description)
                    if (event.entryId != null) {
                        Text(
                            "该事件关联百科条目，可点击编辑。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (event.entryId == null) {
                        TextButton(onClick = {
                            editTlTitle = viewModel.timelineDraft(event.id, "title", event.title)
                            editTlDescription = viewModel.timelineDraft(event.id, "description", event.description)
                            editTlTime = viewModel.timelineDraft(event.id, "time", event.eventTime)
                            editTlOrder = viewModel.timelineDraft(event.id, "order", event.sortOrder.toString())
                            timelineDetailId = null
                            timelineEditId = event.id
                            viewModel.clearTimelineSaveError()
                        }, enabled = timelineActionsReady) { Text("编辑事件") }
                    }
                    event.entryId?.let { entryId ->
                        TextButton(onClick = {
                            timelineDetailId = null
                            onEditEntry(entryId)
                        }) { Text("编辑条目") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { timelineDetailId = null }) { Text("关闭") }
            },
        )
    }

    timelineEditTarget?.let { event ->
        val parsedOrder = editTlOrder.trim().takeIf { Regex("-?\\d+").matches(it) }?.toIntOrNull()
        val sortError = editTlOrder.isNotBlank() && parsedOrder == null
        Dialog(
            onDismissRequest = { if (!state.timelineSaving) timelineEditId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("编辑时间线事件", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = editTlTitle,
                            onValueChange = { editTlTitle = it; viewModel.saveTimelineDraft(event.id, "title", it); viewModel.clearTimelineSaveError() },
                            label = { Text("事件标题") },
                            singleLine = true,
                            enabled = !state.timelineSaving,
                            modifier = Modifier.fillMaxWidth(),
                            inputModifier = Modifier.semantics { contentDescription = "事件标题" },
                        )
                        OutlinedTextField(
                            value = editTlDescription,
                            onValueChange = { editTlDescription = it; viewModel.saveTimelineDraft(event.id, "description", it); viewModel.clearTimelineSaveError() },
                            label = { Text("事件描述（可选）") },
                            minLines = 3,
                            maxLines = 6,
                            enabled = !state.timelineSaving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                            inputModifier = Modifier.semantics { contentDescription = "事件描述" },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = editTlTime,
                                onValueChange = { editTlTime = it; viewModel.saveTimelineDraft(event.id, "time", it); viewModel.clearTimelineSaveError() },
                                label = { Text("时间标签") },
                                singleLine = true,
                                enabled = !state.timelineSaving,
                                modifier = Modifier.weight(1f),
                                inputModifier = Modifier.semantics { contentDescription = "时间标签" },
                            )
                            OutlinedTextField(
                                value = editTlOrder,
                                onValueChange = { value -> editTlOrder = value; viewModel.saveTimelineDraft(event.id, "order", value); viewModel.clearTimelineSaveError() },
                                label = { Text("排序") },
                                singleLine = true,
                                enabled = !state.timelineSaving,
                                isError = sortError,
                                supportingText = if (sortError) ({ Text("请输入有效整数") }) else null,
                                modifier = Modifier.width(100.dp),
                                inputModifier = Modifier.semantics { contentDescription = "排序" },
                            )
                        }
                        state.timelineSaveError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { viewModel.discardTimelineDraft(event.id); timelineEditId = null }, enabled = !state.timelineSaving) { Text("放弃草稿") }
                        TextButton(onClick = { timelineEditId = null }, enabled = !state.timelineSaving) { Text("关闭") }
                        TextButton(
                            onClick = {
                                viewModel.updateTimelineEvent(event.id, editTlTitle, editTlDescription, editTlTime, parsedOrder ?: 0) { saved ->
                                    if (saved) {
                                        timelineEditId = null
                                    }
                                }
                            },
                            enabled = editTlTitle.isNotBlank() && parsedOrder != null && timelineActionsReady && !state.timelineSaving,
                        ) { Text(if (state.timelineSaving) "正在保存…" else "保存事件") }
                    }
                }
            }
        }
    }

    deleteEntryTarget?.let { entry ->
        val deleting = state.entryDeletingId == entry.id
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (!deleting) deleteEntryTarget = null },
            title = { Text("确认删除条目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除条目「${entry.title.ifBlank { "未命名条目" }}」吗？")
                    Text(
                        if (entry.entryType == "character") {
                            "若有关联角色，会连同其百科镜像删除，并从已有会话移除；聊天正文保留。"
                        } else {
                            "删除后将无法恢复。"
                        },
                    )
                    state.entryDeleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry.id) { deleted ->
                        if (deleted) {
                            deleteEntryTarget = null
                            scope.launch { snackbarHostState.showSnackbar("条目已删除") }
                        }
                    }
                }, enabled = !deleting) { Text(if (deleting) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntryTarget = null }, enabled = !deleting) { Text("取消") }
            },
        )
    }

    state.renameDraft?.let { draft ->
        com.mojing.app.ui.common.MoJingFormDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = viewModel::dismissRename,
            title = { Text("重命名百科") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = viewModel::editRename,
                    label = { Text("名称") },
                    singleLine = true,
                    enabled = !state.renameSaving,
                    isError = state.renameError != null,
                    supportingText = { state.renameError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = !state.renameSaving && draft.isNotBlank(),
                    onClick = viewModel::updateEncyclopediaName) {
                    Text(if (state.renameSaving) "保存中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(enabled = !state.renameSaving, onClick = viewModel::dismissRename) { Text("取消") }
            },
        )
    }

    if (showCreateDialog) {
        com.mojing.app.ui.common.MoJingFormDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (!state.entryCreating) showCreateDialog = false },
            title = { Text("新建条目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.createEntryError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(value = newTitle, onValueChange = { newTitle = it; viewModel.clearCreateEntryError() }, enabled = !state.entryCreating, label = { Text("标题") }, singleLine = true)
                    Text("类型", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ENTRY_TYPES.filter { it.second.isNotEmpty() }.size) { idx ->
                            val (label, type) = ENTRY_TYPES[idx + 1]
                            FilterChip(
                                selected = newType == type,
                                onClick = { newType = type },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.entryCreating && newTitle.isNotBlank(), onClick = {
                    viewModel.createEntry(newTitle, newType) { entryId ->
                        newTitle = ""
                        showCreateDialog = false
                        onEditEntry(entryId)
                    }
                }) { Text(if (state.entryCreating) "正在创建…" else "创建") }
            },
            dismissButton = { TextButton(enabled = !state.entryCreating, onClick = { showCreateDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun EncyclopediaSwipeableEntryRow(
    entry: com.mojing.app.data.local.dao.EncyclopediaEntryListItem,
    isWideLayout: Boolean,
    selected: Boolean,
    onRowClick: () -> Unit,
    onToggleFeatured: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeRevealListRow(
        swipeEnabled = true,
        isPinned = entry.isFeatured,
        onPinToggle = onToggleFeatured,
        onDelete = onDelete,
        onClick = onRowClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (isWideLayout && selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.isFeatured) {
                                Icon(
                                    Icons.Outlined.Star,
                                    contentDescription = "精选",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Text(ENTRY_TYPES.firstOrNull { it.second == entry.entryType }?.first?.takeIf { it.isNotBlank() } ?: entry.entryType, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.summary.isNotBlank()) {
                            Text(
                                entry.summary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (entry.coverImagePath.isNotBlank()) {
                        AsyncImage(
                            model = avatarImageModel(LocalContext.current, entry.coverImagePath),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .width(44.dp)
                                .aspectRatio(2f / 3f),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SedimentSourceActions(
    entry: com.mojing.app.data.local.entity.EncyclopediaEntryEntity,
    busy: Boolean,
    onViewSource: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sourcesState = remember(entry.metaJson, entry.sourceMessageId, entry.sourceSessionId) {
        mutableStateOf<com.mojing.app.domain.encyclopedia.EncyclopediaSourceReferences?>(
            if ((entry.sourceSessionId ?: 0L) <= 0L)
                com.mojing.app.domain.encyclopedia.EncyclopediaSourceReferences(emptyList(), null) else null,
        )
    }
    LaunchedEffect(sourcesState) {
        if (sourcesState.value == null) {
            sourcesState.value = withContext(Dispatchers.Default) { entry.sourceReferences() }
        }
    }
    val sources = sourcesState.value
    val sourceLoading = sources == null
    val hasSource = (entry.sourceSessionId ?: 0L) > 0L && sources?.messageIds?.isNotEmpty() == true
    val sourceLabel = when {
        sourceLoading -> "来源：正在读取记录"
        !hasSource -> "来源：未记录原文"
        sources?.branchId == "main" -> "来源：主线对话 · ${sources?.messageIds?.size ?: 0} 条"
        sources?.branchId != null -> "来源：分支对话 · ${sources?.messageIds?.size ?: 0} 条"
        else -> "来源：对话原文 · ${sources?.messageIds?.size ?: 0} 条"
    }
    Text(sourceLabel, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onViewSource,
            enabled = hasSource && !busy,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = when {
                    sourceLoading -> "正在读取来源：${entry.title}"
                    hasSource -> "查看来源：${entry.title}"
                    else -> "无原文来源：${entry.title}"
                }
            },
        ) { Text(if (sourceLoading) "正在读取" else if (hasSource) "查看来源" else "无原文来源") }
        Button(
            onClick = onConfirm,
            enabled = entry.confidence != "confirmed" && !busy,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = "确认资料：${entry.title}"
            },
        ) { Text(if (entry.confidence == "confirmed") "已确认" else "确认资料") }
    }
}
