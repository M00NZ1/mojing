package com.mojing.app.ui.encyclopedia

import com.mojing.app.ui.common.MoJingTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mojing.app.data.local.dao.EncyclopediaLibraryItem
import com.mojing.app.media.CharacterCardImageProcessor
import com.mojing.app.ui.character.components.CardCoverCropSheetHost
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.common.LlmKeySetupHintCard
import com.mojing.app.ui.common.hideImeKeyboard
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.common.rememberExportNavigationGuard
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToCreationHub
import com.mojing.app.ui.navigation.Routes
import com.mojing.app.ui.common.MoJingListTokens
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ContentDocumentWriter
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncyclopediaScreen(
    navController: NavHostController,
    onDetail: (Long) -> Unit,
    onWorldSettings: (Long) -> Unit = {},
    onSettingsClick: () -> Unit,
    onGenerationTasksClick: () -> Unit,
    viewModel: EncyclopediaListViewModel = hiltViewModel()
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val encyclopedias = library.items
    val listLayout by viewModel.encyclopediaListLayout.collectAsStateWithLifecycle()
    val hasPublicLlmKey by viewModel.hasPublicLlmKey.collectAsStateWithLifecycle()
    val pendingGenTasks by viewModel.pendingGenerationTaskCount.collectAsStateWithLifecycle()
    val coverGeneratingEncyclopediaIds by viewModel.coverGeneratingEncyclopediaIds.collectAsStateWithLifecycle()
    val documentImport by viewModel.documentImport.collectAsStateWithLifecycle()
    val isImportingDocument = documentImport.running
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncPublicLlmKeyFromStorage()
                if (viewModel.library.value.loaded) viewModel.refreshCurrentPage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.syncPublicLlmKeyFromStorage()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var renameTarget by remember { mutableStateOf<EncyclopediaLibraryItem?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EncyclopediaLibraryItem?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var pendingExportPicker by rememberSaveable { mutableStateOf(false) }
    var exportWriteInterrupted by rememberSaveable { mutableStateOf(false) }
    var isExportingDocument by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val exportBusy = pendingExportPicker || isExportingDocument
    val requestExportNavigation = rememberExportNavigationGuard(
        exporting = { isExportingDocument },
        onStopExport = { exportJob?.cancelAndJoin() },
    )
    var coverPickTarget by remember { mutableStateOf<EncyclopediaLibraryItem?>(null) }
    var coverCropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var coverPendingEncId by remember { mutableStateOf<Long?>(null) }
    var isCoverTaskBusy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    val libraryActionBusy = isCreating || isImportingDocument || exportBusy
    var showTransferMenu by remember { mutableStateOf(false) }
    var createError by rememberSaveable { mutableStateOf<String?>(null) }
    var searchDraft by rememberSaveable { mutableStateOf(library.query) }
    var showSortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeOpen = isImeKeyboardOpen()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val currentDestination by navController.currentBackStackEntryAsState()
    BackHandler(enabled = currentDestination?.destination?.route == Routes.ENCYCLOPEDIA_LIST) {
        if (isImeOpen) hideImeKeyboard(keyboardController, focusManager)
        else requestExportNavigation { navController.returnToCreationHub() }
    }

    LaunchedEffect(exportWriteInterrupted, isExportingDocument) {
        if (exportWriteInterrupted && !isExportingDocument) {
            exportWriteInterrupted = false
            Toast.makeText(context, "上次导出已中断，文件可能不完整，请重新导出", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(searchDraft) {
        delay(200)
        viewModel.setSearchQuery(searchDraft)
    }

    fun requestCoverGeneration(encyclopedia: EncyclopediaLibraryItem) {
        val started = viewModel.generateEncyclopediaCoverAi(encyclopedia.id) { message ->
            scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.appendAndroidIfNeeded(message)) }
        }
        if (!started) {
            scope.launch { snackbarHostState.showSnackbar("这个百科库的封面正在生成中") }
        }
    }

    @Composable
    fun GenerateCoverMenuItem(encyclopedia: EncyclopediaLibraryItem) {
        val isGenerating = encyclopedia.id in coverGeneratingEncyclopediaIds
        DropdownMenuItem(
            text = { Text(if (isGenerating) "生成中…" else "生成封面") },
            enabled = !isGenerating && !isCoverTaskBusy,
            onClick = { requestCoverGeneration(encyclopedia) },
        )
    }

    val coverPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val enc = coverPickTarget
        coverPickTarget = null
        if (enc == null || uri == null || isCoverTaskBusy) return@rememberLauncherForActivityResult
        val encId = enc.id
        isCoverTaskBusy = true
        scope.launch {
            var keepBusyForCrop = false
            var decodedBitmap: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    decodedBitmap = CharacterCardImageProcessor.decodeBitmapFromUri(context, uri)
                }
                if (decodedBitmap != null) {
                    coverPendingEncId = encId
                    coverCropBitmap = decodedBitmap
                    keepBusyForCrop = true
                } else {
                    Toast.makeText(context, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    e.message ?: UserFacingStrings.cardImageProcessFailed(),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                if (!keepBusyForCrop) {
                    runCatching { decodedBitmap?.recycle() }
                    isCoverTaskBusy = false
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val requested = pendingExportPicker
        pendingExportPicker = false
        if (uri != null && requested && !isImportingDocument && !isExportingDocument) {
            isExportingDocument = true
            exportWriteInterrupted = true
            exportJob = scope.launch {
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
        if (uri != null && !libraryActionBusy) {
            viewModel.startDocumentImport(context, uri)
        }
    }

    fun createEncyclopedia() {
        if (libraryActionBusy) return
        isCreating = true
        createError = null
        scope.launch {
            val result = viewModel.createNew()
            isCreating = false
            result.onSuccess { id ->
                Toast.makeText(context, UserFacingStrings.encyclopediaCreated(), Toast.LENGTH_SHORT).show()
                requestExportNavigation { onDetail(id) }
            }.onFailure { error ->
                val message = error.message ?: "百科创建失败，请重试"
                createError = message
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    fun requestImport() {
        if (libraryActionBusy) return
        showMoreMenu = false
        showTransferMenu = false
        hideImeKeyboard(keyboardController, focusManager)
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    fun requestExport() {
        if (libraryActionBusy) return
        showMoreMenu = false
        showTransferMenu = false
        hideImeKeyboard(keyboardController, focusManager)
        pendingExportPicker = true
        try { exportLauncher.launch("mojing_encyclopedias.json") }
        catch (_: Exception) {
            pendingExportPicker = false
            Toast.makeText(context, UserFacingStrings.exportWriteFailed(), Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    fun TransferButton() {
        Box {
            OutlinedButton(onClick = { showTransferMenu = true }, enabled = !libraryActionBusy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("导入/导出") }
            DropdownMenu(expanded = showTransferMenu, onDismissRequest = { showTransferMenu = false }) {
                DropdownMenuItem(text = { Text("导入百科") }, enabled = !libraryActionBusy, onClick = ::requestImport)
                DropdownMenuItem(text = { Text("导出百科") }, enabled = !libraryActionBusy, onClick = ::requestExport)
            }
        }
    }

    if (documentImport.running) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = viewModel::cancelDocumentImport,
            title = { Text(if (documentImport.cancelling) "正在取消导入" else "正在导入百科") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        when {
                            documentImport.cancelling -> "正在撤销本次导入，请稍候。"
                            documentImport.worlds == 0 && documentImport.entries == 0 -> "正在读取和解析文件…"
                            else -> "已处理 ${documentImport.worlds} 个百科、${documentImport.entries} 条词条"
                        },
                    )
                    Text(
                        "提交前取消会撤销本次导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::cancelDocumentImport, enabled = !documentImport.cancelling) {
                    Text("取消导入")
                }
            },
        )
    } else if (documentImport.result != null) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = viewModel::dismissDocumentImportResult,
            title = {
                Text(when (documentImport.outcome) {
                    EncyclopediaImportOutcome.SUCCESS -> "导入完成"
                    EncyclopediaImportOutcome.CANCELLED -> "已取消导入"
                    else -> "导入未完成"
                })
            },
            text = { Text(documentImport.result.orEmpty()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDocumentImportResult) { Text("关闭") }
            },
        )
    }

    @Composable
    fun LibraryHeader(inList: Boolean = false) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = if (inList) 0.dp else MoJingListTokens.rowStart, vertical = 8.dp)) {
                val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = searchDraft,
                            onValueChange = { searchDraft = it },
                            label = { Text("搜索世界名称") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchDraft.isNotEmpty()) {
                                    IconButton(onClick = { searchDraft = "" }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (!stacked) TransferButton()
                    }
                    if (stacked) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TransferButton() }
                    @Composable
                    fun Filters() {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !library.onlyPinned, onClick = { viewModel.setOnlyPinned(false) },
                                label = { Text("全部") }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(selected = library.onlyPinned, onClick = { viewModel.setOnlyPinned(true) },
                                label = { Text("置顶") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    @Composable
                    fun SortButton() {
                        Box {
                            TextButton(onClick = { showSortMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(library.sort.label)
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "世界排序")
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                WorldLibrarySort.entries.forEach { sort ->
                                    DropdownMenuItem(text = { Text(sort.label) }, onClick = {
                                        showSortMenu = false
                                        viewModel.setSort(sort)
                                    })
                                }
                            }
                        }
                    }
                    if (stacked) {
                        Filters()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SortButton() }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) { Filters(); SortButton() }
                    }
                }
            }
            if (createError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (inList) 0.dp else MoJingListTokens.rowStart, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            createError.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = ::createEncyclopedia, enabled = !libraryActionBusy) { Text("重试") }
                    }
                }
            }
        }
    }

    @Composable
    fun LibraryNotices(inList: Boolean = false) {
        Column {
            if (library.loading || library.error != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (inList) 0.dp else MoJingListTokens.rowStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (library.loading) "正在读取世界…" else library.error.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (library.error != null) TextButton(onClick = viewModel::retryPage) { Text("重试") }
                }
            }
            if (!hasPublicLlmKey) {
                LlmKeySetupHintCard(
                    message = "请先在设置填写 API Key",
                    onOpenSettings = { requestExportNavigation { onSettingsClick() } },
                    modifier = Modifier.padding(horizontal = if (inList) 0.dp else MoJingListTokens.rowStart, vertical = 8.dp),
                )
            }
            if (pendingGenTasks > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (inList) 0.dp else MoJingListTokens.rowStart, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "$pendingGenTasks 个任务进行中",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { requestExportNavigation(onGenerationTasksClick) }) {
                            Text("查看任务")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun LibraryEmpty(modifier: Modifier) {
        if (library.loading && !library.loaded) {
            Box(modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (library.error != null && !library.loaded) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = "世界列表暂时无法读取",
                message = library.error.orEmpty(),
                actionLabel = "重试",
                onAction = viewModel::retryPage,
                modifier = modifier,
            )
        } else if (encyclopedias.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = if (library.onlyPinned) "没有找到置顶世界" else if (library.query.isBlank()) "还没有世界百科" else "没有找到世界",
                message = if (library.onlyPinned) "可以切回全部世界，或调整搜索名称。" else if (library.query.isBlank()) "创建百科后，可以整理世界规则、地点、势力、事件和人物关系。" else "试试其他名称，或清除搜索条件。",
                actionLabel = if (library.onlyPinned) "查看全部世界" else if (library.query.isBlank()) (if (isCreating) "正在创建…" else "新建百科") else "清除搜索",
                onAction = if (library.onlyPinned) ({ viewModel.setOnlyPinned(false) }) else if (library.query.isBlank()) ::createEncyclopedia else ({ searchDraft = "" }),
                modifier = modifier,
            )
        }
    }

    @Composable
    fun LibraryPager(inList: Boolean = false) {
        if (library.loaded && encyclopedias.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (inList) 0.dp else MoJingListTokens.rowStart, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("第 ${library.pageIndex + 1} 页", style = MaterialTheme.typography.labelMedium)
                Row {
                    TextButton(onClick = viewModel::previousPage, enabled = !library.loading && library.pageIndex > 0) { Text("上一页") }
                    TextButton(onClick = viewModel::nextPage, enabled = !library.loading && library.hasNext) { Text("下一页") }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                        expandedHeight = 52.dp,
                title = { Text("世界") },
                navigationIcon = {
                    IconButton(onClick = { requestExportNavigation { navController.returnToCreationHub() } }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回创作中心")
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            enabled = !libraryActionBusy,
                        ) {
                            if (isImportingDocument || exportBusy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.MoreVert, "更多")
                            }
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(text = { Text("世界工坊") }, onClick = {
                                showMoreMenu = false; requestExportNavigation { navController.navigate(com.mojing.app.ui.navigation.Routes.WORKBENCH) }
                            })
                            DropdownMenuItem(text = { Text("生成记录 · $pendingGenTasks 项进行中") }, onClick = {
                                showMoreMenu = false; requestExportNavigation { onGenerationTasksClick() }
                            })
                            DropdownMenuItem(text = { Text(if (listLayout == "grid") "切换为列表" else "切换为网格") }, onClick = {
                                showMoreMenu = false; viewModel.toggleEncyclopediaListLayout()
                            })
                            DropdownMenuItem(
                                text = { Text(if (isImportingDocument) "正在导入…" else "导入百科") },
                                enabled = !libraryActionBusy,
                                onClick = ::requestImport,
                            )
                            DropdownMenuItem(
                                text = { Text(if (exportBusy) "正在导出…" else "导出百科") },
                                enabled = !libraryActionBusy,
                                onClick = ::requestExport,
                            )
                        }
                    }
                    Button(onClick = { requestExportNavigation { createEncyclopedia() } }, enabled = !libraryActionBusy,
                        modifier = Modifier.padding(end = 12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text(if (isCreating) "创建中" else "新建世界", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
        bottomBar = {
            MainAppBottomNavigation(navController, onNavigateRequest = requestExportNavigation)
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Short windows scroll auxiliary content with the bounded page instead of starving its viewport.
            val shortContent = maxHeight < 360.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                if (!shortContent) LibraryHeader()
                if (encyclopedias.isEmpty()) {
                    if (shortContent) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            item(key = "library-header") { LibraryHeader(inList = true) }
                            item(key = "library-empty") { LibraryEmpty(Modifier.fillMaxWidth().heightIn(min = 120.dp)) }
                        }
                    } else {
                        LibraryEmpty(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                    }
                } else {
                    if (!shortContent) LibraryNotices()
                    if (listLayout == "grid") {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 156.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                        if (shortContent) {
                            item(key = "library-header", span = { GridItemSpan(maxLineSpan) }) { LibraryHeader(inList = true) }
                            item(key = "library-notices", span = { GridItemSpan(maxLineSpan) }) { LibraryNotices(inList = true) }
                        }
                        lazyGridItems(encyclopedias, key = { it.id }) { enc ->
                            SwipeRevealListRow(
                                swipeEnabled = false,
                                isPinned = enc.pinnedAt > 0,
                                onPinToggle = { viewModel.setEncyclopediaPinned(enc.id, enc.pinnedAt == 0L) },
                                onDelete = { deleteTarget = enc },
                                onClick = { requestExportNavigation { onDetail(enc.id) } },
                                menuExtras = {
                                    DropdownMenuItem(text = { Text("世界设置") }, onClick = { requestExportNavigation { onWorldSettings(enc.id) } })
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                        renameTarget = enc; renameDraft = enc.name; renameError = null
                                    })
                                    DropdownMenuItem(
                                        text = { Text(if (isCoverTaskBusy) "正在处理封面…" else "从相册设置封面") },
                                        enabled = !isCoverTaskBusy && enc.id !in coverGeneratingEncyclopediaIds,
                                        onClick = {
                                            coverPickTarget = enc
                                            coverPickLauncher.launch("image/*")
                                        },
                                    )
                                    GenerateCoverMenuItem(enc)
                                },
                            ) {
                                EncyclopediaGridCard(enc = enc)
                            }
                        }
                        if (shortContent) item(key = "library-pager", span = { GridItemSpan(maxLineSpan) }) { LibraryPager(inList = true) }
                    }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                        if (shortContent) {
                            item(key = "library-header") { LibraryHeader(inList = true) }
                            item(key = "library-notices") { LibraryNotices(inList = true) }
                        }
                        itemsIndexed(encyclopedias, key = { _, e -> e.id }) { index, enc ->
                            Column(Modifier.fillMaxWidth()) {
                                SwipeRevealListRow(
                                    swipeEnabled = true,
                                    showMenuButton = true,
                                    isPinned = enc.pinnedAt > 0,
                                    onPinToggle = { viewModel.setEncyclopediaPinned(enc.id, enc.pinnedAt == 0L) },
                                    onDelete = { deleteTarget = enc },
                                onClick = { requestExportNavigation { onDetail(enc.id) } },
                                    menuExtras = {
                                        DropdownMenuItem(text = { Text("世界设置") }, onClick = { requestExportNavigation { onWorldSettings(enc.id) } })
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                        renameTarget = enc; renameDraft = enc.name; renameError = null
                                    })
                                        DropdownMenuItem(
                                            text = { Text(if (isCoverTaskBusy) "正在处理封面…" else "从相册设置封面") },
                                            enabled = !isCoverTaskBusy && enc.id !in coverGeneratingEncyclopediaIds,
                                            onClick = {
                                                coverPickTarget = enc
                                                coverPickLauncher.launch("image/*")
                                            },
                                        )
                                        GenerateCoverMenuItem(enc)
                                    },
                                ) {
                                    EncyclopediaListRowInner(enc = enc)
                                }
                            }
                        }
                        if (shortContent) item(key = "library-pager") { LibraryPager(inList = true) }
                    }
                    }
                }
                if (!shortContent) LibraryPager()
            }
        }
    }

    CardCoverCropSheetHost(
        bitmap = coverCropBitmap,
        requestToken = coverPendingEncId,
        onBitmapDisposed = { coverCropBitmap = null },
        onCropCancelled = { coverPendingEncId = null },
        onProcessingChanged = { isCoverTaskBusy = it },
        onCroppedPath = { id, path ->
            coverPendingEncId = null
            if (id != null) {
                Toast.makeText(context, viewModel.updateEncyclopediaCover(id, path), Toast.LENGTH_SHORT).show()
            } else {
                withContext(Dispatchers.IO) { File(path).delete() }
                Toast.makeText(context, "封面目标已失效，请重试", Toast.LENGTH_SHORT).show()
            }
        },
        onCroppedFailed = { ctx ->
            coverPendingEncId = null
            Toast.makeText(ctx, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
        },
    )

    renameTarget?.let { enc ->
        com.mojing.app.ui.common.MoJingFormDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (!renaming) renameTarget = null },
            title = { Text("重命名百科") },
            text = {
                OutlinedTextField(value = renameDraft,
                    onValueChange = { renameDraft = it; renameError = null },
                    label = { Text("百科名称") }, singleLine = true,
                    enabled = !renaming, isError = renameError != null,
                    supportingText = { renameError?.let { Text(it) } })
            },
            confirmButton = {
                TextButton(enabled = !renaming && renameDraft.isNotBlank(), onClick = {
                    renaming = true
                    scope.launch {
                        try {
                            renameError = viewModel.rename(enc.id, renameDraft)
                            if (renameError == null) renameTarget = null
                        } finally { renaming = false }
                    }
                }) { Text(if (renaming) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(enabled = !renaming, onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { enc ->
        var deleteError by remember(enc.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("确认删除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "删除「${enc.name.ifBlank { "未命名百科库" }}」及其条目、时间线、关系和绑定角色，无法撤销。",
                    )
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                deleting = true
                deleteError = null
                scope.launch {
                    try {
                        val error = viewModel.delete(enc.id)
                        if (error == null) {
                            deleteTarget = null
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, UserFacingStrings.itemDeleted(enc.name.ifBlank { "未命名百科库" }), Toast.LENGTH_SHORT).show()
                            }
                        } else deleteError = error
                    } finally { deleting = false }
                }
            }) { Text(if (deleting) "删除中…" else "删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun EncyclopediaListRowInner(enc: EncyclopediaLibraryItem) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            if (enc.coverImagePath.isNotBlank()) {
                AsyncImage(
                    model = avatarImageModel(context, enc.coverImagePath),
                    contentDescription = null,
                    modifier = Modifier
                        .size(MoJingListTokens.worldCover)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.size(MoJingListTokens.worldCover),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        },
        headlineContent = {
            Text(
                enc.name.ifBlank { "未命名百科库" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                val preview = enc.preview
                if (preview.isNotBlank()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (enc.genreTags.isNotBlank()) {
                    Text(
                        enc.genreTags,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WorldLibraryStatistics(enc)
            }
        },
    )
}

@Composable
private fun EncyclopediaGridCard(enc: EncyclopediaLibraryItem) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f),
            ) {
                if (enc.coverImagePath.isNotBlank()) {
                    AsyncImage(
                        model = avatarImageModel(context, enc.coverImagePath),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.MenuBook,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                )
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    enc.name.ifBlank { "未命名百科库" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val gridPreview = enc.preview
                if (gridPreview.isNotBlank()) {
                    Text(
                        gridPreview,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (enc.genreTags.isNotBlank()) {
                    Text(enc.genreTags, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                WorldLibraryStatistics(enc)
            }
        }
    }
}

@Composable
private fun WorldLibraryStatistics(enc: EncyclopediaLibraryItem) {
    Text("${enc.entryCount} 条目 · ${enc.characterCount} 人物 · ${enc.locationCount} 地点",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
