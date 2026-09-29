package com.mojing.app.ui.character

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingTonalButton as FilledTonalButton

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mojing.app.data.local.dao.CharacterListItem
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToCreationHub
import com.mojing.app.ui.common.MoJingListTokens
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ContentDocumentReader
import com.mojing.app.util.ContentDocumentWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListScreen(
    navController: NavHostController,
    onEdit: (Long) -> Unit,
    onChat: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: CharacterListViewModel = hiltViewModel()
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val characters = page.items
    val listLayout by viewModel.characterListLayout.collectAsStateWithLifecycle()
    val filterEnc by viewModel.filterEncyclopediaId.collectAsStateWithLifecycle()
    val selectedFilterName by viewModel.selectedFilterName.collectAsStateWithLifecycle()
    val startingCharacterId by viewModel.startingCharacterId.collectAsStateWithLifecycle()
    val creatingCharacter by viewModel.creatingCharacter.collectAsStateWithLifecycle()
    val deletingCharacterId by viewModel.deletingCharacterId.collectAsStateWithLifecycle()
    var sortOrder by rememberSaveable { mutableStateOf(CharacterLibrarySort.RECOMMENDED) }
    var deleteTarget by remember { mutableStateOf<CharacterListItem?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var filterPickerOpen by remember { mutableStateOf(false) }
    var isImportingDocument by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<CharacterImportResult?>(null) }
    var pendingExportPicker by rememberSaveable { mutableStateOf(false) }
    var exportWriteInterrupted by rememberSaveable { mutableStateOf(false) }
    var isExportingDocument by remember { mutableStateOf(false) }
    val exportBusy = pendingExportPicker || isExportingDocument
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(exportWriteInterrupted, isExportingDocument) {
        if (exportWriteInterrupted && !isExportingDocument) {
            exportWriteInterrupted = false
            Toast.makeText(context, "上次导出已中断，文件可能不完整，请重新导出", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSelectedFilterName()
        viewModel.setSortOrder(sortOrder)
        viewModel.refreshList(keepVisible = false)
    }

    fun launchCreateCharacter() {
        if (creatingCharacter) return
        viewModel.createNew(
            encyclopediaId = filterEnc ?: 0L,
            onCreated = onEdit,
            onFailed = { message ->
                scope.launch {
                    if (snackbarHostState.showSnackbar(message, actionLabel = "重试") == SnackbarResult.ActionPerformed) {
                        launchCreateCharacter()
                    }
                }
            },
        )
    }

    fun startChat(character: CharacterListItem) {
        viewModel.startChat(
            characterId = character.id,
            onCreated = onChat,
            onNeedsEncyclopedia = {
                // Keep this callback for older/migrated implementations of the use case.
                // The current session creator accepts a character without a world binding.
                Toast.makeText(context, "当前角色需要先选择一个百科", Toast.LENGTH_SHORT).show()
                onEdit(character.id)
            },
            onFailed = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val requested = pendingExportPicker
        pendingExportPicker = false
        if (uri != null && requested && !isImportingDocument && !isExportingDocument) {
            isExportingDocument = true
            exportWriteInterrupted = true
            scope.launch {
                val notice = try {
                    ContentDocumentWriter.writeStream(context, uri, viewModel::exportJson)
                    UserFacingStrings.exportSuccess()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    UserFacingStrings.exportWriteFailed()
                } finally {
                    exportWriteInterrupted = false
                    isExportingDocument = false
                }
                Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !isImportingDocument && !exportBusy) {
            isImportingDocument = true
            scope.launch {
                try {
                    val bytes = ContentDocumentReader.readBytes(
                        context,
                        uri,
                        ContentDocumentReader.CHARACTER_IMPORT_MAX_BYTES,
                    )
                    if (bytes.isEmpty()) {
                        importResult = CharacterImportResult(UserFacingStrings.importReadFailed(), hasFailure = true)
                        return@launch
                    }
                    val result = viewModel.importFromDocument(bytes, uri.lastPathSegment)
                    importResult = result
                    if (result.importedIds.isNotEmpty()) {
                        viewModel.setEncyclopediaFilter(null)
                        sortOrder = CharacterLibrarySort.RECENT
                        viewModel.setSortOrder(sortOrder)
                        viewModel.refreshList(keepVisible = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    importResult = CharacterImportResult(UserFacingStrings.importReadFailed(), hasFailure = true)
                } finally {
                    isImportingDocument = false
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("角色") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.returnToCreationHub() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回创作中心")
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            enabled = !isImportingDocument && !exportBusy,
                        ) {
                            if (isImportingDocument || exportBusy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MoreVert, "更多")
                            }
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (isImportingDocument) "正在导入…" else "导入（便携包 / JSON / TXT / Word / PNG）")
                                },
                                enabled = !isImportingDocument && !exportBusy,
                                onClick = {
                                    showMoreMenu = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/png", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (exportBusy) "正在导出…" else "导出角色") },
                                enabled = !isImportingDocument && !exportBusy,
                                onClick = {
                                    showMoreMenu = false
                                    pendingExportPicker = true
                                    try { exportLauncher.launch("mojing_characters.json") }
                                    catch (_: Exception) {
                                        pendingExportPicker = false
                                        Toast.makeText(context, UserFacingStrings.exportWriteFailed(), Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.toggleCharacterListLayout() }
                    ) {
                        if (listLayout == "grid") {
                            Icon(Icons.AutoMirrored.Filled.ViewList, "切换为列表")
                        } else {
                            Icon(Icons.Default.GridView, "切换为网格")
                        }
                    }
                },
            )
        },
        bottomBar = {
            MainAppBottomNavigation(navController)
        },
        floatingActionButton = {
            if (characters.isNotEmpty() || filterEnc != null || page.loading || page.error != null) {
                FloatingActionButton(onClick = { launchCreateCharacter() }) {
                    if (creatingCharacter) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Add, "新建角色")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = { filterPickerOpen = true },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Public, "按百科筛选", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (filterEnc == null) "全部角色" else selectedFilterName?.ifBlank { null } ?: "百科 $filterEnc",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.KeyboardArrowDown, "打开百科筛选", Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item { Text("排序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { FilterChip(sortOrder == CharacterLibrarySort.RECOMMENDED, { sortOrder = CharacterLibrarySort.RECOMMENDED; viewModel.setSortOrder(sortOrder) }, label = { Text("推荐") }) }
                item { FilterChip(sortOrder == CharacterLibrarySort.RECENT, { sortOrder = CharacterLibrarySort.RECENT; viewModel.setSortOrder(sortOrder) }, label = { Text("最近添加") }) }
                item { FilterChip(sortOrder == CharacterLibrarySort.NAME, { sortOrder = CharacterLibrarySort.NAME; viewModel.setSortOrder(sortOrder) }, label = { Text("名称") }) }
                item { Text("本页 ${characters.size} 条${if (page.hasNext) " · 后面还有" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            importResult?.let { result ->
                Surface(
                    color = if (result.hasFailure) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                result.message,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (result.hasFailure) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                            IconButton(onClick = { importResult = null }) {
                                Icon(Icons.Default.Close, "关闭导入结果")
                            }
                        }
                        result.importedIds.lastOrNull()?.let { importedId ->
                            TextButton(
                                onClick = { importResult = null; onEdit(importedId) },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(if (result.importedIds.size == 1) "编辑角色" else "编辑最近角色")
                            }
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (characters.isEmpty() && page.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (characters.isEmpty() && page.error != null) {
                    EmptyState(icon = Icons.Default.ErrorOutline, title = "角色加载失败", message = page.error.orEmpty(),
                        actionLabel = "重试", onAction = viewModel::retryPage, modifier = Modifier.fillMaxSize())
                } else if (characters.isEmpty()) {
                    val selectedEncyclopedia = selectedFilterName
                    EmptyState(
                        icon = Icons.Default.PersonAdd,
                        title = if (filterEnc == null) "还没有角色" else "这个百科还没有角色",
                        message = if (filterEnc == null) {
                            "创建角色档案后，可以在这里编辑人设、形象和对话参数。"
                        } else {
                            "新建角色会自动绑定到「${selectedEncyclopedia ?: "当前百科"}」，也可以查看全部角色。"
                        },
                        actionLabel = if (filterEnc == null) "新建角色" else "查看全部角色",
                        onAction = if (filterEnc == null) {
                            ::launchCreateCharacter
                        } else {
                            { viewModel.setEncyclopediaFilter(null) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (listLayout == "grid") {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(characters, key = { it.id }) { character ->
                            SwipeRevealListRow(
                                swipeEnabled = true,
                                isPinned = character.pinnedAt > 0,
                                onPinToggle = { viewModel.setCharacterPinned(character.id, character.pinnedAt == 0L) { message -> scope.launch { snackbarHostState.showSnackbar(message) } } },
                                onDelete = { deleteError = null; deleteTarget = character },
                                onClick = { onEdit(character.id) },
                                menuExtras = {
                                    DropdownMenuItem(
                                        text = { Text(if (character.favorite) "取消收藏" else "收藏") },
                                        onClick = { viewModel.toggleFavorite(character.id) { message -> scope.launch { snackbarHostState.showSnackbar(message) } } },
                                    )
                                },
                            ) {
                                CharacterGridCard(
                                    character = character,
                                    encyclopediaLabel = character.encyclopediaName,
                                    startEnabled = startingCharacterId == null,
                                    isStarting = startingCharacterId == character.id,
                                    onStartChat = { startChat(character) },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 0.dp),
                    ) {
                        itemsIndexed(characters, key = { _, c -> c.id }) { index, character ->
                            Column(Modifier.fillMaxWidth()) {
                                SwipeRevealListRow(
                                    swipeEnabled = true,
                                    isPinned = character.pinnedAt > 0,
                                    onPinToggle = { viewModel.setCharacterPinned(character.id, character.pinnedAt == 0L) { message -> scope.launch { snackbarHostState.showSnackbar(message) } } },
                                    onDelete = { deleteError = null; deleteTarget = character },
                                    onClick = { onEdit(character.id) },
                                    menuExtras = {
                                        DropdownMenuItem(
                                            text = { Text(if (character.favorite) "取消收藏" else "收藏") },
                                            onClick = { viewModel.toggleFavorite(character.id) { message -> scope.launch { snackbarHostState.showSnackbar(message) } } },
                                        )
                                    },
                                ) {
                                    CharacterListRowInner(
                                        character = character,
                                        encyclopediaLabel = character.encyclopediaName,
                                        startEnabled = startingCharacterId == null,
                                        isStarting = startingCharacterId == character.id,
                                        onStartChat = { startChat(character) },
                                    )
                                }
                                if (index < characters.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = MoJingListTokens.dividerInset),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (characters.isNotEmpty()) {
                page.error?.let { message ->
                    TextButton(onClick = viewModel::retryPage, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("$message · 重试")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = viewModel::previousPage, enabled = !page.loading && page.error == null && page.pageIndex > 0) { Text("上一页") }
                    Text("${page.pageIndex + 1}", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = viewModel::nextPage, enabled = !page.loading && page.error == null && page.hasNext) { Text("下一页") }
                }
            }
        }
    }

    if (filterPickerOpen) CharacterFilterPicker(
        selectedId = filterEnc,
        loadPage = viewModel::loadEncyclopediaFilterPage,
        onSelect = { option ->
            viewModel.setEncyclopediaFilter(option?.id, option?.name)
            filterPickerOpen = false
        },
        onDismiss = { filterPickerOpen = false },
    )

    deleteTarget?.let { c ->
        val deleting = deletingCharacterId == c.id
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("确认删除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "确定要删除「${c.name.ifBlank { "未命名角色" }}」吗？\n\n" +
                            "该角色的本地配置和角色档案会一并删除；已产生的聊天记录不会自动删除。此操作不可撤销。",
                    )
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting && deletingCharacterId == null,
                    onClick = {
                        viewModel.delete(
                            c.id,
                            onDeleted = {
                                deleteTarget = null
                                deleteError = null
                                Toast.makeText(context, UserFacingStrings.itemDeleted(c.name.ifBlank { "未命名角色" }), Toast.LENGTH_SHORT).show()
                            },
                            onFailed = { deleteError = it },
                        )
                    },
                ) { Text(if (deleting) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !deleting) { Text("取消") } }
        )
    }
}

@Composable
private fun CharacterListRowInner(
    character: CharacterListItem,
    encyclopediaLabel: String? = null,
    startEnabled: Boolean,
    isStarting: Boolean,
    onStartChat: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            if (character.avatarImagePath.isNotBlank()) {
                AsyncImage(
                    model = avatarImageModel(context, character.avatarImagePath),
                    contentDescription = "头像",
                    modifier = Modifier
                        .size(MoJingListTokens.avatar)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                val circleBg = try {
                    androidx.compose.ui.graphics.Color(AndroidColor.parseColor(character.avatarColor))
                } catch (_: Exception) {
                    MaterialTheme.colorScheme.primaryContainer
                }
                Surface(
                    modifier = Modifier.size(MoJingListTokens.avatar),
                    shape = CircleShape,
                    color = circleBg,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            character.name.take(1).ifBlank { "?" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.contentColorFor(circleBg),
                        )
                    }
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    character.name.ifBlank { "未命名角色" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (character.favorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Star, "收藏", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        },
        supportingContent = {
            Column {
                if (character.boundEncyclopediaId > 0L) {
                    Text(
                        "所属百科：${encyclopediaLabel ?: "百科 ${character.boundEncyclopediaId}"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "未绑定百科 · 可直接开始对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    character.personaPreview.ifEmpty { "未设定人设" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onStartChat, enabled = startEnabled) {
                if (isStarting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "开始对话",
                    )
                }
            }
        },
    )
}

@Composable
private fun CharacterGridCard(
    character: CharacterListItem,
    encyclopediaLabel: String? = null,
    startEnabled: Boolean,
    isStarting: Boolean,
    onStartChat: () -> Unit,
) {
    val context = LocalContext.current
    val mainPath = character.cardImagePath.ifBlank { character.avatarImagePath }
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            ) {
                when {
                    mainPath.isNotEmpty() -> AsyncImage(
                        model = avatarImageModel(context, mainPath),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = try { androidx.compose.ui.graphics.Color(AndroidColor.parseColor(character.avatarColor)) } catch (_: Exception) { MaterialTheme.colorScheme.primary }
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text((character.name.ifBlank { "?" }).take(1), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
                if (character.favorite) {
                    Icon(
                        Icons.Default.Star,
                        "收藏",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(22.dp)
                    )
                }
                if (character.cardImagePath.isNotBlank() && character.avatarImagePath.isNotBlank() && character.cardImagePath != character.avatarImagePath) {
                    AsyncImage(
                        model = avatarImageModel(context, character.avatarImagePath),
                        contentDescription = "头像",
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    character.name.ifBlank { "未命名角色" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (character.boundEncyclopediaId > 0L) {
                    Text(
                        encyclopediaLabel ?: "百科 ${character.boundEncyclopediaId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        "未绑定百科 · 可直接开始对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
                Text(
                    character.personaPreview.take(42).ifEmpty { "未设定人设" },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onStartChat,
                    enabled = startEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("创建中…")
                    } else {
                        Text("开始对话")
                    }
                }
            }
        }
    }
}
