package com.mojing.app.ui.workbench

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import com.mojing.app.ui.common.MoJingTonalButton as FilledTonalButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mojing.app.data.local.dao.WorldTemplateLibraryItem
import com.mojing.app.domain.usecase.DeleteWorldTemplateResult
import com.mojing.app.media.CharacterCardImageProcessor
import com.mojing.app.ui.character.components.CardCoverCropSheetHost
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToCreationHub
import com.mojing.app.ui.common.MoJingListTokens
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ContentDocumentReader
import com.mojing.app.util.ContentDocumentWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val WORKBENCH_WORLD_TYPES = listOf(
    "修仙", "玄幻", "剑与魔法", "DND", "战锤", "都市", "校园", "科幻", "赛博朋克", "克苏鲁", "其他"
)

private enum class WorkbenchMainTab {
    TEMPLATES,
    TEXT_IMPORT,
    AI_GENERATE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    navController: NavHostController,
    onEditTemplate: (Long) -> Unit,
    onStartChat: (Long) -> Unit,
    onOpenCanonical: (Long) -> Unit = {},
    onSettingsClick: () -> Unit,
    viewModel: WorkbenchViewModel = hiltViewModel()
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val templates = library.items
    val listLayout by viewModel.workbenchListLayout.collectAsStateWithLifecycle()
    val plainImportBusy by viewModel.plainImportBusy.collectAsStateWithLifecycle()
    val generateBusy by viewModel.generateBusy.collectAsStateWithLifecycle()
    val generateSaving by viewModel.generateSaving.collectAsStateWithLifecycle()
    val coverGeneratingTemplateIds by viewModel.coverGeneratingTemplateIds.collectAsStateWithLifecycle()
    val promotedTemplateIds by viewModel.promotedTemplateIds.collectAsStateWithLifecycle()
    val deleteTemplateState by viewModel.deleteTemplateState.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<WorldTemplateLibraryItem?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var isImportingDocument by remember { mutableStateOf(false) }
    var isExportingDocument by rememberSaveable { mutableStateOf(false) }
    var isCoverTaskBusy by remember { mutableStateOf(false) }
    var mainTab by remember { mutableStateOf(WorkbenchMainTab.TEMPLATES) }
    var templateSearch by rememberSaveable { mutableStateOf(viewModel.library.value.query) }
    LaunchedEffect(Unit) {
        if (viewModel.library.value.loaded) viewModel.refresh()
    }
    LaunchedEffect(templateSearch) {
        delay(200)
        viewModel.setSearchQuery(templateSearch)
    }
    var plainImportText by remember { mutableStateOf("") }
    var genWorldType by remember { mutableStateOf("修仙") }
    var genCoreTheme by remember { mutableStateOf("") }
    var genTone by remember { mutableStateOf("偏严谨、可长期推进") }
    var genExtra by remember { mutableStateOf("") }
    var genLabel by remember { mutableStateOf("") }
    var genTypeExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isImeOpen = isImeKeyboardOpen()
    var showStopAndContinueDialog by remember { mutableStateOf(false) }
    var showSavingDialog by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(deleteTemplateState) {
        val target = deleteTarget ?: return@LaunchedEffect
        if (target.id == deleteTemplateState.templateId &&
            deleteTemplateState.result is DeleteWorldTemplateResult.Deleted
        ) {
            deleteTarget = null
            viewModel.clearTemplateDeleteState()
            Toast.makeText(context, UserFacingStrings.itemDeleted(target.label.ifBlank { "未命名模板" }), Toast.LENGTH_SHORT).show()
        }
    }

    fun dismissStopDialog() {
        showStopAndContinueDialog = false
        pendingNavigation = null
    }

    fun createTemplate() {
        Toast.makeText(context, UserFacingStrings.templateDraftCreated(), Toast.LENGTH_SHORT).show()
        onEditTemplate(0L)
    }

    fun requestNavigation(action: () -> Unit) {
        if (generateSaving) {
            showSavingDialog = true
        } else if (generateBusy) {
            pendingNavigation = action
            showStopAndContinueDialog = true
        } else {
            action()
        }
    }

    fun selectMainTab(tab: WorkbenchMainTab) {
        if (tab == mainTab) return
        requestNavigation {
            focusManager.clearFocus()
            mainTab = tab
        }
    }

    LaunchedEffect(generateBusy) {
        if (!generateBusy) {
            dismissStopDialog()
            showSavingDialog = false
        }
    }

    BackHandler {
        when {
            isImeOpen -> com.mojing.app.ui.common.hideImeKeyboard(keyboardController, focusManager)
            showStopAndContinueDialog -> dismissStopDialog()
            showSavingDialog -> showSavingDialog = false
            else -> requestNavigation { navController.returnToCreationHub() }
        }
    }

    fun requestCoverGeneration(template: WorldTemplateLibraryItem) {
        val started = viewModel.generateTemplateCoverAi(template.id) { message ->
            scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.appendAndroidIfNeeded(message)) }
        }
        if (!started) {
            scope.launch { snackbarHostState.showSnackbar("这个模板的封面正在生成中") }
        }
    }

    @Composable
    fun GenerateCoverMenuItem(template: WorldTemplateLibraryItem) {
        val isGenerating = template.id in coverGeneratingTemplateIds
        DropdownMenuItem(
            text = { Text(if (template.id in promotedTemplateIds) "封面请在世界百科维护" else if (isGenerating) "生成中…" else "生成封面") },
            enabled = template.id !in promotedTemplateIds && !isGenerating && !isCoverTaskBusy,
            onClick = { requestCoverGeneration(template) },
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !isImportingDocument && isExportingDocument) {
            scope.launch {
                try {
                    ContentDocumentWriter.writeStream(context, uri, viewModel::exportJson)
                    Toast.makeText(context, UserFacingStrings.exportSuccess(), Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, UserFacingStrings.exportWriteFailed(), Toast.LENGTH_SHORT).show()
                } finally {
                    isExportingDocument = false
                }
            }
        } else {
            isExportingDocument = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !isImportingDocument && !isExportingDocument) {
            isImportingDocument = true
            scope.launch {
                try {
                    val text = ContentDocumentReader.readUtf8Text(
                        context,
                        uri,
                        ContentDocumentReader.STRUCTURED_TEXT_IMPORT_MAX_BYTES,
                    )
                    if (text.isBlank()) {
                        Toast.makeText(context, UserFacingStrings.importReadFailed(), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val msg = viewModel.importJson(text)
                    Toast.makeText(context, UserFacingStrings.appendAndroidIfNeeded(msg), Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        e.message ?: UserFacingStrings.importReadFailed(),
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    isImportingDocument = false
                }
            }
        }
    }

    var coverPickTemplate by remember { mutableStateOf<WorldTemplateLibraryItem?>(null) }
    var coverCropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var coverPendingTemplateId by remember { mutableStateOf<Long?>(null) }
    val coverPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val t = coverPickTemplate
        coverPickTemplate = null
        if (t == null || uri == null || isCoverTaskBusy) return@rememberLauncherForActivityResult
        val templateId = t.id
        isCoverTaskBusy = true
        scope.launch {
            var keepBusyForCrop = false
            var decodedBitmap: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    decodedBitmap = CharacterCardImageProcessor.decodeBitmapFromUri(context, uri)
                }
                if (decodedBitmap != null) {
                    coverPendingTemplateId = templateId
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设定工坊") },
                navigationIcon = {
                    IconButton(onClick = {
                        com.mojing.app.ui.common.hideImeKeyboard(keyboardController, focusManager)
                        requestNavigation { navController.returnToCreationHub() }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回创作中心")
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            enabled = !isImportingDocument && !isExportingDocument && !generateBusy,
                        ) {
                            if (isImportingDocument || isExportingDocument) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MoreVert, "更多")
                            }
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (isImportingDocument) "正在导入…" else "导入模板") },
                                enabled = !isImportingDocument && !isExportingDocument,
                                onClick = {
                                    showMoreMenu = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isExportingDocument) "正在导出…" else "导出模板") },
                                enabled = !isImportingDocument && !isExportingDocument,
                                onClick = {
                                    showMoreMenu = false
                                    isExportingDocument = true
                                    exportLauncher.launch("mojing_templates.json")
                                },
                            )
                            if (viewModel.isCompanionBackendConfigured()) {
                                DropdownMenuItem(
                                    text = { Text("导出模板包（仅自定义，浏览器）") },
                                    onClick = {
                                        showMoreMenu = false
                                        val url = viewModel.backendExportBundleUrlCustomOnly()
                                        if (url.isNullOrBlank()) {
                                            Toast.makeText(context, "无法生成下载地址", Toast.LENGTH_SHORT).show()
                                        } else {
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                                .onFailure {
                                                    Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                                                }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("导出模板包（含内置，浏览器）") },
                                    onClick = {
                                        showMoreMenu = false
                                        val url = viewModel.backendExportBundleUrlWithBuiltin()
                                        if (url.isNullOrBlank()) {
                                            Toast.makeText(context, "无法生成下载地址", Toast.LENGTH_SHORT).show()
                                        } else {
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                                .onFailure {
                                                    Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                                                }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { viewModel.toggleWorkbenchListLayout() },
                        enabled = !generateBusy,
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
            MainAppBottomNavigation(
                navController = navController,
                onNavigateRequest = { action -> requestNavigation(action) },
            )
        },
        floatingActionButton = {
            if (mainTab == WorkbenchMainTab.TEMPLATES && library.loaded && templates.isNotEmpty() && !isImeKeyboardOpen()) {
                ExtendedFloatingActionButton(
                    onClick = ::createTemplate,
                    icon = { Icon(Icons.Default.Add, "新建") },
                    text = { Text("新建模板") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScrollableTabRow(
                selectedTabIndex = mainTab.ordinal,
                edgePadding = MoJingListTokens.rowStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = mainTab == WorkbenchMainTab.TEMPLATES,
                    onClick = { selectMainTab(WorkbenchMainTab.TEMPLATES) },
                    text = { Text("模板") }
                )
                Tab(
                    selected = mainTab == WorkbenchMainTab.TEXT_IMPORT,
                    onClick = { selectMainTab(WorkbenchMainTab.TEXT_IMPORT) },
                    text = { Text("文本导入") }
                )
                Tab(
                    selected = mainTab == WorkbenchMainTab.AI_GENERATE,
                    onClick = { selectMainTab(WorkbenchMainTab.AI_GENERATE) },
                    text = { Text("智能生成") }
                )
            }
            when (mainTab) {
                WorkbenchMainTab.TEMPLATES -> {
                    com.mojing.app.ui.common.SearchBar(
                        query = templateSearch,
                        onQueryChange = { templateSearch = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MoJingListTokens.rowStart, vertical = 8.dp),
                        searchDescription = "搜索模板", clearDescription = "清除搜索",
                        placeholder = "搜索名称、摘要或分类",
                    )
                    if (library.loaded) {
                        Text("本页 ${templates.size} 个设定模板", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (library.loaded && (library.loading || library.error != null)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = MoJingListTokens.rowStart),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (library.loading) "正在读取模板…" else library.error.orEmpty(),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (library.error != null) TextButton(onClick = viewModel::retryPage) { Text("重试") }
                        }
                    }
                    if (!library.loaded && library.loading) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (!library.loaded && library.error != null) {
                        EmptyState(
                            icon = Icons.Default.Public,
                            title = "模板列表暂时无法读取",
                            message = library.error.orEmpty(),
                            actionLabel = "重试",
                            onAction = viewModel::retryPage,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else if (templates.isEmpty()) {
                        EmptyState(
                            icon = if (library.query.isBlank()) Icons.Default.Public else Icons.Default.SearchOff,
                            title = if (library.query.isBlank()) "还没有设定模板" else "没有匹配的模板",
                            message = if (library.query.isBlank()) {
                                "新建模板，或通过「文本导入」和「智能生成」建立世界设定。"
                            } else {
                                "试试其他关键词，或清除当前搜索。"
                            },
                            actionLabel = if (library.query.isBlank()) "新建模板" else "清除搜索",
                            onAction = if (library.query.isBlank()) {
                                ::createTemplate
                            } else {
                                { templateSearch = "" }
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        if (listLayout == "grid") {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(156.dp),
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                lazyGridItems(templates, key = { it.id }) { template ->
                                    SwipeRevealListRow(
                                        swipeEnabled = false,
                                        isPinned = template.pinnedAt > 0,
                                        onPinToggle = { viewModel.setTemplatePinned(template.id, template.pinnedAt == 0L) },
                                        onDelete = { viewModel.clearTemplateDeleteState(); deleteTarget = template },
                                        onClick = { promotedTemplateIds[template.id]?.let(onOpenCanonical) ?: onEditTemplate(template.id) },
                                        menuExtras = {
                                            DropdownMenuItem(
                                                text = { Text(if (promotedTemplateIds[template.id] != null) "已归入世界，打开百科" else "归入世界") },
                                                onClick = {
                                                    val canonicalId = promotedTemplateIds[template.id]
                                                    if (canonicalId != null) onOpenCanonical(canonicalId) else viewModel.promoteTemplate(template.id) { id, message ->
                                                        scope.launch { snackbarHostState.showSnackbar(message) }
                                                        if (id != null) onOpenCanonical(id)
                                                    }
                                                },
                                            )
                                            if (template.id !in promotedTemplateIds) {
                                            DropdownMenuItem(
                                                text = { Text(if (isCoverTaskBusy) "正在处理封面…" else "从相册设置封面") },
                                                enabled = !isCoverTaskBusy && template.id !in coverGeneratingTemplateIds,
                                                onClick = {
                                                    coverPickTemplate = template
                                                    coverPickLauncher.launch("image/*")
                                                },
                                            )
                                            }
                                            GenerateCoverMenuItem(template)
                                            if (viewModel.isCompanionBackendConfigured()) {
                                                DropdownMenuItem(
                                                    text = { Text("浏览器导出此模板") },
                                                    onClick = {
                                                        val url = viewModel.backendExportSingleTemplateUrl(template.templateId)
                                                        if (url.isNullOrBlank()) {
                                                            Toast.makeText(context, "无法生成下载地址", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                                        }
                                                    },
                                                )
                                            }
                                        },
                                    ) {
                                        WorkbenchTemplateGridCard(
                                            template = template,
                                            canonical = template.id in promotedTemplateIds,
                                            onStartChat = { onStartChat(template.id) },
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(bottom = 96.dp),
                            ) {
                                itemsIndexed(templates, key = { _, t -> t.id }) { index, template ->
                                    Column(Modifier.fillMaxWidth()) {
                                        SwipeRevealListRow(
                                            swipeEnabled = true,
                                            isPinned = template.pinnedAt > 0,
                                            onPinToggle = { viewModel.setTemplatePinned(template.id, template.pinnedAt == 0L) },
                                            onDelete = { viewModel.clearTemplateDeleteState(); deleteTarget = template },
                                            onClick = { promotedTemplateIds[template.id]?.let(onOpenCanonical) ?: onEditTemplate(template.id) },
                                            menuExtras = {
                                                DropdownMenuItem(
                                                    text = { Text(if (promotedTemplateIds[template.id] != null) "已归入世界，打开百科" else "归入世界") },
                                                    onClick = {
                                                        val canonicalId = promotedTemplateIds[template.id]
                                                        if (canonicalId != null) onOpenCanonical(canonicalId) else viewModel.promoteTemplate(template.id) { id, message ->
                                                            scope.launch { snackbarHostState.showSnackbar(message) }
                                                            if (id != null) onOpenCanonical(id)
                                                        }
                                                    },
                                                )
                                                if (template.id !in promotedTemplateIds) {
                                                DropdownMenuItem(
                                                    text = { Text(if (isCoverTaskBusy) "正在处理封面…" else "从相册设置封面") },
                                                    enabled = !isCoverTaskBusy && template.id !in coverGeneratingTemplateIds,
                                                    onClick = {
                                                        coverPickTemplate = template
                                                        coverPickLauncher.launch("image/*")
                                                    },
                                                )
                                                }
                                                GenerateCoverMenuItem(template)
                                                if (viewModel.isCompanionBackendConfigured()) {
                                                    DropdownMenuItem(
                                                        text = { Text("浏览器导出此模板") },
                                                        onClick = {
                                                            val url = viewModel.backendExportSingleTemplateUrl(template.templateId)
                                                            if (url.isNullOrBlank()) {
                                                                Toast.makeText(context, "无法生成下载地址", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                                            }
                                                        },
                                                    )
                                                }
                                            },
                                        ) {
                                            WorkbenchTemplateListRowInner(
                                                template = template,
                                                canonical = template.id in promotedTemplateIds,
                                                onStartChat = { onStartChat(template.id) },
                                            )
                                        }
                                        if (index < templates.lastIndex) {
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
                    if (library.loaded && templates.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = MoJingListTokens.rowStart, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("第 ${library.pageIndex + 1} 页", style = MaterialTheme.typography.labelMedium)
                            Row {
                                TextButton(onClick = viewModel::previousPage, enabled = !library.loading && library.pageIndex > 0) { Text("上一页") }
                                TextButton(onClick = viewModel::nextPage, enabled = !library.loading && library.error == null && library.hasNext) { Text("下一页") }
                            }
                        }
                    }
                }

                WorkbenchMainTab.TEXT_IMPORT -> {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = plainImportText,
                            onValueChange = { plainImportText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                            label = { Text("世界观文本或 JSON") },
                            minLines = 6
                        )
                        Button(
                            onClick = {
                                viewModel.importPlainWorldText(plainImportText) { msg ->
                                    Toast.makeText(context, UserFacingStrings.appendAndroidIfNeeded(msg), Toast.LENGTH_LONG).show()
                                    if (msg.contains("成功导入")) plainImportText = ""
                                }
                            },
                            enabled = !plainImportBusy && plainImportText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (plainImportBusy) {
                                    CircularProgressIndicator(
                                        Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (plainImportBusy) "解析中…" else "解析并导入模板")
                            }
                        }
                    }
                }

                WorkbenchMainTab.AI_GENERATE -> {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!viewModel.hasLlmForWorldGenerate()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
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
                                        "请先在设置填写 API Key",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = onSettingsClick, enabled = !generateBusy) { Text("去设置") }
                                }
                            }
                        }
                        ExposedDropdownMenuBox(
                            expanded = genTypeExpanded,
                            onExpandedChange = {
                                if (!generateBusy) {
                                    if (it) focusManager.clearFocus()
                                    genTypeExpanded = it
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = genWorldType,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("世界类型") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genTypeExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                enabled = !generateBusy,
                            )
                            ExposedDropdownMenu(
                                expanded = genTypeExpanded,
                                onDismissRequest = { genTypeExpanded = false },
                            ) {
                                WORKBENCH_WORLD_TYPES.forEach { w ->
                                    DropdownMenuItem(
                                        text = { Text(w) },
                                        onClick = {
                                            genWorldType = w
                                            genTypeExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = genLabel,
                            onValueChange = { genLabel = it },
                            label = { Text("世界名称（可选）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !generateBusy,
                        )
                        OutlinedTextField(
                            value = genCoreTheme,
                            onValueChange = { genCoreTheme = it },
                            label = { Text("核心主题（必填）") },
                            placeholder = { Text("一句话描述世界核心冲突…") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            enabled = !generateBusy,
                        )
                        OutlinedTextField(
                            value = genTone,
                            onValueChange = { genTone = it },
                            label = { Text("基调") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !generateBusy,
                        )
                        OutlinedTextField(
                            value = genExtra,
                            onValueChange = { genExtra = it },
                            label = { Text("详细要求（可选）") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp),
                            minLines = 3,
                            enabled = !generateBusy,
                        )
                        if (generateBusy && !generateSaving) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                                    CircularProgressIndicator(
                                        Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("正在生成世界…")
                                }
                                OutlinedButton(onClick = {
                                    if (viewModel.cancelWorldGeneration()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("已停止生成，当前填写的设定仍保留")
                                        }
                                    } else if (generateBusy) {
                                        scope.launch { snackbarHostState.showSnackbar("世界正在写入本地，请稍候") }
                                    }
                                }) {
                                    Text("停止")
                                }
                            }
                            Text(
                                "正在生成完整世界设定。可以继续等待，也可以停止；停止后不会保存未完成的新模板。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else if (generateSaving) {
                            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在保存到本地…")
                            }
                            Text(
                                "世界设定已经生成，正在保存模板与设定条目。完成前请稍候。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Button(
                                onClick = {
                                    viewModel.generateWorldRemote(
                                        worldType = genWorldType,
                                        coreTheme = genCoreTheme.trim(),
                                        tone = genTone.trim(),
                                        extra = genExtra.trim(),
                                        label = genLabel.trim(),
                                    ) { msg ->
                                        Toast.makeText(
                                            context,
                                            UserFacingStrings.appendAndroidIfNeeded(msg),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                enabled = genCoreTheme.isNotBlank() && viewModel.hasLlmForWorldGenerate(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("生成并保存世界")
                            }
                            Text(
                                "生成完成后会保存到本地模板库，可继续编辑、开始故事或导出。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStopAndContinueDialog) {
        AlertDialog(
            onDismissRequest = { dismissStopDialog() },
            title = { Text("停止当前生成？") },
            text = { Text("当前世界仍在生成。停止后不会保存未完成的新模板，你填写的内容会继续保留。") },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingNavigation
                    if (viewModel.cancelWorldGeneration()) {
                        dismissStopDialog()
                        action?.invoke()
                    } else if (!generateBusy) {
                        dismissStopDialog()
                        action?.invoke()
                    } else {
                        dismissStopDialog()
                        scope.launch { snackbarHostState.showSnackbar("世界正在写入本地，请稍候") }
                    }
                }) { Text("停止并继续操作") }
            },
            dismissButton = {
                TextButton(onClick = { dismissStopDialog() }) { Text("继续生成") }
            },
        )
    }

    if (showSavingDialog) {
        AlertDialog(
            onDismissRequest = { showSavingDialog = false },
            title = { Text("正在保存世界") },
            text = { Text("生成已经完成，正在把模板和设定条目写入本地。保存结束后即可继续操作。") },
            confirmButton = {
                TextButton(onClick = { showSavingDialog = false }) { Text("继续等待") }
            },
        )
    }

    CardCoverCropSheetHost(
        bitmap = coverCropBitmap,
        requestToken = coverPendingTemplateId,
        onBitmapDisposed = { coverCropBitmap = null },
        onCropCancelled = { coverPendingTemplateId = null },
        onProcessingChanged = { isCoverTaskBusy = it },
        onCroppedPath = { id, path ->
            coverPendingTemplateId = null
            if (id != null) {
                Toast.makeText(context, viewModel.updateTemplateCover(id, path), Toast.LENGTH_SHORT).show()
            } else {
                withContext(Dispatchers.IO) { File(path).delete() }
                Toast.makeText(context, "封面目标已失效，请重试", Toast.LENGTH_SHORT).show()
            }
        },
        onCroppedFailed = { ctx ->
            coverPendingTemplateId = null
            Toast.makeText(ctx, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
        },
    )

    deleteTarget?.let { t ->
        val canonicalId = promotedTemplateIds[t.id]
        val currentDelete = deleteTemplateState.takeIf { it.templateId == t.id }
        val deleting = currentDelete?.isDeleting == true
        val deleteError = when (currentDelete?.result) {
            DeleteWorldTemplateResult.Protected -> "该模板已归入百科，请到对应世界中管理。"
            DeleteWorldTemplateResult.NotFound -> "模板已不存在，请关闭后刷新列表。"
            is DeleteWorldTemplateResult.Failed -> "删除失败，模板及设定条目已保留，请重试。"
            else -> null
        }
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            title = { Text("删除工坊模板", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (canonicalId != null) "「${t.label.ifBlank { "未命名模板" }}」已归入百科，请在对应世界中管理。"
                        else "将删除「${t.label.ifBlank { "未命名模板" }}」及其工坊设定条目，操作不可撤销。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (deleting) Text("正在删除…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (deleteError != null && canonicalId == null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(deleteError, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            },
            confirmButton = {
                if (canonicalId != null) {
                    Button(onClick = { deleteTarget = null; onOpenCanonical(canonicalId) }, enabled = !deleting) { Text("打开百科") }
                } else {
                    Button(
                        onClick = { viewModel.deleteTemplate(t.id) },
                        enabled = !deleting && currentDelete?.result !is DeleteWorldTemplateResult.Protected && currentDelete?.result !is DeleteWorldTemplateResult.NotFound,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) {
                        Text(if (currentDelete?.result is DeleteWorldTemplateResult.Failed) "重试删除" else if (deleting) "删除中" else "删除")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !deleting) { Text(if (deleteError != null) "关闭" else "取消") } }
        )
    }
}

@Composable
private fun WorkbenchTemplateListRowInner(
    template: WorldTemplateLibraryItem,
    canonical: Boolean,
    onStartChat: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            if (template.coverImagePath.isNotBlank()) {
                AsyncImage(
                    model = avatarImageModel(context, template.coverImagePath),
                    contentDescription = null,
                    modifier = Modifier
                        .size(MoJingListTokens.avatar)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.size(MoJingListTokens.avatar),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Construction,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        },
        headlineContent = {
            Text(
                template.label.ifEmpty { "未命名模板" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (canonical) Text("已归入世界百科", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Text(
                    template.summary.take(96).ifEmpty { "暂无摘要" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (template.category.isNotBlank()) {
                    Text(
                        template.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onStartChat) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "用此世界开始对话")
            }
        },
    )
}

@Composable
private fun WorkbenchTemplateGridCard(
    template: WorldTemplateLibraryItem,
    canonical: Boolean,
    onStartChat: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f),
            ) {
                key(template.id, template.coverImagePath, template.updatedAt) {
                    if (template.coverImagePath.isNotBlank()) {
                        AsyncImage(
                            model = avatarImageModel(context, template.coverImagePath),
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
                                        Icons.Default.Construction,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(if (canonical) "已归入世界百科" else template.category.ifBlank { "设定模板" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    template.label.ifEmpty { "未命名模板" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    template.summary.take(42).ifEmpty { "暂无摘要" },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onStartChat,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("用此世界开始")
                }
            }
        }
    }
}
