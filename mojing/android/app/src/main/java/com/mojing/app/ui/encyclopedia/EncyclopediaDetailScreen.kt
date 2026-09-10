package com.mojing.app.ui.encyclopedia

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.common.avatarImageModel

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncyclopediaDetailScreen(
    encyclopediaId: Long,
    onEditEntry: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenGenerationTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: EncyclopediaDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entryTypeTabs = remember(state.encyclopedia) { filteredEntryTypeTabs(state.encyclopedia) }
    var sedimentFilter by rememberSaveable(encyclopediaId) { mutableStateOf("all") }
    val confirmedSedimentCount = remember(state.sedimentEntries) {
        state.sedimentEntries.count { it.confidence == "confirmed" }
    }
    val filteredSediment = remember(state.sedimentEntries, sedimentFilter) {
        when (sedimentFilter) {
            "pending" -> state.sedimentEntries.filter { it.confidence != "confirmed" }
            "confirmed" -> state.sedimentEntries.filter { it.confidence == "confirmed" }
            else -> state.sedimentEntries
        }
    }

    LaunchedEffect(encyclopediaId) { viewModel.load(encyclopediaId) }

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
    var tlTime by remember { mutableStateOf("") }
    var tlOrder by remember { mutableStateOf("0") }
    LaunchedEffect(state.mainTab) {
        if (state.mainTab == EncyclopediaMainTab.TIMELINE) {
            val next = (state.timelineEvents.maxOfOrNull { it.sortOrder } ?: -1) + 1
            tlOrder = next.coerceAtLeast(0).toString()
        }
    }
    var showRelDialog by remember { mutableStateOf(false) }
    var relFrom by remember { mutableStateOf<Long?>(null) }
    var relTo by remember { mutableStateOf<Long?>(null) }
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
    var deleteEntryTarget by remember { mutableStateOf<EncyclopediaEntryEntity?>(null) }
    var timelineDetailTarget by remember { mutableStateOf<TimelineEventEntity?>(null) }
    var timelineDeleteTarget by remember { mutableStateOf<TimelineEventEntity?>(null) }
    var pinnedTimelineIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

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
                title = {
                    Text(
                        text = state.encyclopedia?.name ?: "百科详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
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
                                Icons.Filled.CloudSync,
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
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                        }
                        DropdownMenu(
                            expanded = showEncyclopediaMenu,
                            onDismissRequest = { showEncyclopediaMenu = false },
                        ) {
                            if (!state.hasPublicLlmKey) {
                                DropdownMenuItem(
                                    text = { Text("配置 AI 线路") },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
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
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
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
                                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                enabled = !state.worldInfoImportBusy && !isReadingWorldInfoDocument,
                                onClick = {
                                    showEncyclopediaMenu = false
                                    worldInfoOpenDocLauncher.launch(WorldInfoImportMimeTypes)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("粘贴导入设定") },
                                leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
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
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
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
                    if (state.pickerEntries.size < 2) {
                        scope.launch {
                            snackbarHostState.showSnackbar("请至少添加 2 条条目后再建立关系")
                        }
                    } else {
                        relFrom = null
                        relTo = null
                        showRelDialog = true
                    }
                }) { Icon(Icons.Default.Link, "添加关系") }
                EncyclopediaMainTab.ENTRIES -> FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, "新建条目")
                }
                EncyclopediaMainTab.TIMELINE,
                EncyclopediaMainTab.SEDIMENT -> Unit
            }
        }
    ) { padding ->
        if (state.loadError != null) {
            EmptyState(
                icon = Icons.Default.ErrorOutline,
                title = "无法打开百科",
                message = state.loadError.orEmpty(),
                actionLabel = "重新加载",
                onAction = { viewModel.load(encyclopediaId) },
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
            ScrollableTabRow(
                selectedTabIndex = state.mainTab.ordinal,
                edgePadding = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = state.mainTab == EncyclopediaMainTab.ENTRIES,
                    onClick = { viewModel.setMainTab(EncyclopediaMainTab.ENTRIES) },
                    text = { Text("条目") }
                )
                Tab(
                    selected = state.mainTab == EncyclopediaMainTab.TIMELINE,
                    onClick = { viewModel.setMainTab(EncyclopediaMainTab.TIMELINE) },
                    text = { Text("时间线") }
                )
                Tab(
                    selected = state.mainTab == EncyclopediaMainTab.GRAPH,
                    onClick = { viewModel.setMainTab(EncyclopediaMainTab.GRAPH) },
                    text = { Text("关系图") }
                )
                Tab(
                    selected = state.mainTab == EncyclopediaMainTab.SEDIMENT,
                    onClick = { viewModel.setMainTab(EncyclopediaMainTab.SEDIMENT) },
                    text = { Text("沉积") }
                )
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
                    if (state.entries.isEmpty()) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Filled.Article,
                            title = if (state.selectedType.isBlank()) "还没有百科条目" else "当前分类没有条目",
                            message = if (state.selectedType.isBlank()) {
                                "先创建角色、地点或世界规则，让故事有可以持续引用的设定。"
                            } else {
                                "可以切换分类查看，或直接创建一个新条目。"
                            },
                            actionLabel = "新建条目",
                            onAction = { showCreateDialog = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val wide = maxWidth >= 720.dp
                            if (wide) {
                                Row(Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        modifier = Modifier
                                            .weight(0.42f)
                                            .fillMaxHeight()
                                    ) {
                                        items(state.entries, key = { it.id }) { entry ->
                                            EncyclopediaSwipeableEntryRow(
                                                entry = entry,
                                                isWideLayout = true,
                                                selected = state.previewEntryId == entry.id,
                                                onRowClick = { viewModel.setPreviewEntry(entry.id) },
                                                onToggleFeatured = { viewModel.toggleEntryFeatured(entry.id) },
                                                onDelete = { deleteEntryTarget = entry },
                                            )
                                        }
                                    }
                                    VerticalDivider(Modifier.width(1.dp).fillMaxHeight())
                                    val preview = state.entries.find { it.id == state.previewEntryId }
                                    Column(
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .fillMaxHeight()
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text("预览", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        if (preview == null) {
                                            Text(
                                                "选择条目预览",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
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
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(state.entries, key = { it.id }) { entry ->
                                        EncyclopediaSwipeableEntryRow(
                                            entry = entry,
                                            isWideLayout = false,
                                            selected = false,
                                            onRowClick = { onEditEntry(entry.id) },
                                            onToggleFeatured = { viewModel.toggleEntryFeatured(entry.id) },
                                            onDelete = { deleteEntryTarget = entry },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                EncyclopediaMainTab.TIMELINE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        OutlinedTextField(value = tlTitle, onValueChange = { tlTitle = it }, label = { Text("事件标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = tlTime, onValueChange = { tlTime = it }, label = { Text("时间标签") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = tlOrder, onValueChange = { tlOrder = it.filter { ch -> ch.isDigit() || ch == '-' } }, label = { Text("排序") }, modifier = Modifier.width(100.dp), singleLine = true)
                        }
                        Button(onClick = {
                            val order = tlOrder.toIntOrNull() ?: 0
                            val prevMax = state.timelineEvents.maxOfOrNull { it.sortOrder } ?: -1
                            viewModel.addTimelineEvent(tlTitle, tlTime, order)
                            tlTitle = ""
                            tlTime = ""
                            tlOrder = (kotlin.math.max(prevMax, order) + 1).toString()
                        }, enabled = tlTitle.isNotBlank()) { Text("添加事件") }
                        Spacer(Modifier.height(12.dp))
                        if (state.timelineEvents.isEmpty()) {
                            Text(
                                "还没有时间线事件，在上方填写标题后即可添加。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                                if (wasPinned) "已取消置顶" else "已置顶",
                                            )
                                        }
                                    },
                                    onDelete = { timelineDeleteTarget = ev },
                                    onClick = {
                                        ev.entryId?.let { entryId ->
                                            onEditEntry(entryId)
                                        } ?: run {
                                            timelineDetailTarget = ev
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(ev.eventTime.ifBlank { "未标注" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(ev.title, style = MaterialTheme.typography.titleSmall)
                                                    if (ev.id in pinnedTimelineIds) {
                                                        Icon(
                                                            Icons.Default.Star,
                                                            contentDescription = "置顶",
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
                EncyclopediaMainTab.GRAPH -> {
                    val nodeIds = buildSet {
                        state.relations.forEach { add(it.fromEntryId); add(it.toEntryId) }
                    }
                    val nodes = nodeIds.map { GraphNode(it, state.entryTitles[it] ?: "#$it") }
                    val edges = state.relations.map { GraphEdge(it.fromEntryId, it.toEntryId, it.relationType) }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (nodes.isEmpty()) {
                            EmptyState(
                                icon = Icons.Default.Link,
                                title = "还没有条目关系",
                                message = if (state.pickerEntries.size < 2) {
                                    "至少创建两个条目后，才能建立角色、地点或事件之间的关系。"
                                } else {
                                    "建立条目之间的联系，关系图会在这里呈现。"
                                },
                                actionLabel = if (state.pickerEntries.size >= 2) "添加关系" else null,
                                onAction = if (state.pickerEntries.size >= 2) {
                                    {
                                        relFrom = null
                                        relTo = null
                                        showRelDialog = true
                                    }
                                } else null,
                            )
                        } else {
                            EncyclopediaRelationCanvas(
                                nodes = nodes,
                                edges = edges,
                                highlightId = null,
                                onNodeTap = { onEditEntry(it) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        state.relations.forEach { r ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${state.entryTitles[r.fromEntryId] ?: "资料不可用"} —[${r.relationType}]→ ${state.entryTitles[r.toEntryId] ?: "资料不可用"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { viewModel.deleteRelation(r.id) }) {
                                        Icon(Icons.Default.Close, "删除关系", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
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
                            "打开条目查看原文、编辑内容或更新确认状态",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = sedimentFilter == "all",
                                    onClick = { sedimentFilter = "all" },
                                    label = { Text("全部 ${state.sedimentEntries.size}") },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = sedimentFilter == "pending",
                                    onClick = { sedimentFilter = "pending" },
                                    label = { Text("待核对 ${state.sedimentEntries.size - confirmedSedimentCount}") },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = sedimentFilter == "confirmed",
                                    onClick = { sedimentFilter = "confirmed" },
                                    label = { Text("已确认 $confirmedSedimentCount") },
                                )
                            }
                        }
                        if (filteredSediment.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when {
                                        state.sedimentEntries.isEmpty() -> "对话整理出的资料会显示在这里"
                                        sedimentFilter == "pending" -> "所有资料均已确认"
                                        else -> "暂无已确认资料"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            key(sedimentFilter) {
                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filteredSediment, key = { it.id }) { entry ->
                                        SwipeRevealListRow(
                                            swipeEnabled = true,
                                            isPinned = entry.isFeatured,
                                            onPinToggle = { viewModel.toggleEntryFeatured(entry.id) },
                                            onDelete = { deleteEntryTarget = entry },
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
                                                    Text(
                                                        entry.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        maxLines = 1,
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
                                                        DateUtils.getRelativeTimeSpanString(
                                                            entry.updatedAt,
                                                            System.currentTimeMillis(),
                                                            DateUtils.MINUTE_IN_MILLIS,
                                                        ).toString(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(top = 4.dp),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

    if (showWorldInfoDialog) {
        AlertDialog(
            onDismissRequest = { showWorldInfoDialog = false },
            title = { Text("粘贴导入设定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        AlertDialog(
            onDismissRequest = { showRelDialog = false },
            title = { Text("添加条目关系") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = relFrom?.let { id -> state.pickerEntries.find { it.id == id }?.title } ?: "请选择",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("从条目") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { relMenuFrom = true }
                        )
                        DropdownMenu(expanded = relMenuFrom, onDismissRequest = { relMenuFrom = false }) {
                            state.pickerEntries.forEach { e ->
                                DropdownMenuItem(
                                    text = { Text(e.title) },
                                    onClick = {
                                        relFrom = e.id
                                        relMenuFrom = false
                                    }
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = relTo?.let { id -> state.pickerEntries.find { it.id == id }?.title } ?: "请选择",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("到条目") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { relMenuTo = true }
                        )
                        DropdownMenu(expanded = relMenuTo, onDismissRequest = { relMenuTo = false }) {
                            state.pickerEntries.forEach { e ->
                                DropdownMenuItem(
                                    text = { Text(e.title) },
                                    onClick = {
                                        relTo = e.id
                                        relMenuTo = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(value = relType, onValueChange = { relType = it }, label = { Text("关系类型") }, singleLine = true)
                    OutlinedTextField(value = relLabel, onValueChange = { relLabel = it }, label = { Text("备注（可选）") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = relFrom
                    val t = relTo
                    if (f != null && t != null && f != t) {
                        viewModel.addRelation(f, t, relType, relLabel)
                        showRelDialog = false
                        relLabel = ""
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRelDialog = false }) { Text("取消") } }
        )
    }

    if (showBatchMetaConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchMetaConfirm = false },
            title = { Text("批量补全扩展字段（文本）") },
            text = {
                Text(
                    "将对当前列表中 ${state.entries.size} 条条目依次补全缺失的扩展字段（不覆盖已有内容），任务在后台队列执行，完成后自动保存。条目较多时请耐心等待并保持网络畅通。",
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
            onDismissRequest = { timelineDeleteTarget = null },
            title = { Text("确认删除时间线事件") },
            text = { Text("确定删除「${event.title.ifBlank { "未命名事件" }}」吗？删除后将无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTimelineEvent(event.id)
                    pinnedTimelineIds = pinnedTimelineIds - event.id
                    timelineDeleteTarget = null
                    scope.launch { snackbarHostState.showSnackbar("时间线事件已删除") }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { timelineDeleteTarget = null }) { Text("取消") } },
        )
    }

    timelineDetailTarget?.let { event ->
        AlertDialog(
            onDismissRequest = { timelineDetailTarget = null },
            title = { Text(event.title.ifBlank { "时间线事件" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                event.entryId?.let { entryId ->
                    TextButton(onClick = {
                        timelineDetailTarget = null
                        onEditEntry(entryId)
                    }) { Text("编辑条目") }
                }
            },
            dismissButton = {
                TextButton(onClick = { timelineDetailTarget = null }) { Text("关闭") }
            },
        )
    }

    deleteEntryTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntryTarget = null },
            title = { Text("确认删除条目") },
            text = {
                Text(
                    "确定删除条目「${entry.title.ifBlank { "未命名条目" }}」吗？\n\n" +
                        if (entry.entryType == "character") {
                            "该条目可能与角色或其他资料关联，删除后将无法恢复。"
                        } else {
                            "删除后将无法恢复。"
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry.id)
                    deleteEntryTarget = null
                    scope.launch { snackbarHostState.showSnackbar("条目已删除") }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntryTarget = null }) { Text("取消") }
            },
        )
    }

    state.renameDraft?.let { draft ->
        AlertDialog(
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
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建条目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, label = { Text("标题") }, singleLine = true)
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
                TextButton(onClick = {
                    viewModel.createEntry(newTitle, newType)
                    newTitle = ""
                    showCreateDialog = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun EncyclopediaSwipeableEntryRow(
    entry: EncyclopediaEntryEntity,
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isWideLayout && selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.isFeatured) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "精选",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Text("类型: ${entry.entryType}", style = MaterialTheme.typography.labelSmall)
                    if (entry.summary.isNotBlank()) {
                        Text(
                            entry.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
        }
    }
}
