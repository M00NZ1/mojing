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
import com.mojing.app.data.local.entity.WorldTemplateEntity
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
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val listLayout by viewModel.workbenchListLayout.collectAsStateWithLifecycle()
    val plainImportBusy by viewModel.plainImportBusy.collectAsStateWithLifecycle()
    val generateBusy by viewModel.generateBusy.collectAsStateWithLifecycle()
    val generateSaving by viewModel.generateSaving.collectAsStateWithLifecycle()
    val coverGeneratingTemplateIds by viewModel.coverGeneratingTemplateIds.collectAsStateWithLifecycle()
    val promotedTemplateIds by viewModel.promotedTemplateIds.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<WorldTemplateEntity?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var isImportingDocument by remember { mutableStateOf(false) }
    var isExportingDocument by rememberSaveable { mutableStateOf(false) }
    var isCoverTaskBusy by remember { mutableStateOf(false) }
    var mainTab by remember { mutableStateOf(WorkbenchMainTab.TEMPLATES) }
    var templateSearch by remember { mutableStateOf("") }
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

    fun requestCoverGeneration(template: WorldTemplateEntity) {
        val started = viewModel.generateTemplateCoverAi(template.id) { message ->
            scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.appendAndroidIfNeeded(message)) }
        }
        if (!started) {
            scope.launch { snackbarHostState.showSnackbar("这个模板的封面正在生成中") }
        }
    }

    @Composable
    fun GenerateCoverMenuItem(template: WorldTemplateEntity) {
        val isGenerating = template.id in coverGeneratingTemplateIds
        DropdownMenuItem(
            text = { Text(if (template.id in promotedTemplateIds) "封面请在世界百科维护" else if (isGenerating) "生成中…" else "生成封面") },
            enabled = template.id !in promotedTemplateIds && !isGenerating && !isCoverTaskBusy,
            onClick = { requestCoverGeneration(template) },
        )
    }

    val filteredTemplates = remember(templates, templateSearch) {
        val q = templateSearch.trim().lowercase()
        if (q.isEmpty()) templates
        else templates.filter { t ->
            t.label.lowercase().contains(q) ||
                t.summary.lowercase().contains(q) ||
                t.category.lowercase().contains(q)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !isImportingDocument && isExportingDocument) {
            scope.launch {
                try {
                    val json = viewModel.exportJson()
                    ContentDocumentWriter.writeUtf8Text(context, uri, json)
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

    var coverPickTemplate by remember { mutableStateOf<WorldTemplateEntity?>(null) }
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
            if (mainTab == WorkbenchMainTab.TEMPLATES && templates.isNotEmpty() && !isImeKeyboardOpen()) {
                ExtendedFloatingActionButton(
                    onClick = ::createTemplate,
                    icon = { Icon(Icons.Default.Add, "新建") },
                    text = { Text("新建模板") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
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
                    OutlinedTextField(
                        value = templateSearch,
                        onValueChange = { templateSearch = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MoJingListTokens.rowStart, vertical = 8.dp),
                        singleLine = true,
                        label = { Text("搜索模板") },
                        placeholder = { Text("名称 / 摘要 / 分类") }
                    )
                    if (filteredTemplates.isEmpty()) {
                        EmptyState(
                            icon = if (templates.isEmpty()) Icons.Default.Public else Icons.Default.SearchOff,
                            title = if (templates.isEmpty()) "还没有设定模板" else "没有匹配的模板",
                            message = if (templates.isEmpty()) {
                                "新建模板，或通过「文本导入」和「智能生成」建立世界设定。"
                            } else {
                                "试试其他关键词，或清除当前搜索。"
                            },
                            actionLabel = if (templates.isEmpty()) "新建模板" else "清除搜索",
                            onAction = if (templates.isEmpty()) {
                                ::createTemplate
                            } else {
                                { templateSearch = "" }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        if (listLayout == "grid") {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                lazyGridItems(filteredTemplates, key = { it.id }) { template ->
                                    SwipeRevealListRow(
                                        swipeEnabled = false,
                                        isPinned = template.pinnedAt > 0,
                                        onPinToggle = { viewModel.setTemplatePinned(template.id, template.pinnedAt == 0L) },
                                        onDelete = { if (template.id !in promotedTemplateIds) deleteTarget = template },
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
                                            onStartChat = { onStartChat(template.id) },
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 0.dp),
                            ) {
                                itemsIndexed(filteredTemplates, key = { _, t -> t.id }) { index, template ->
                                    Column(Modifier.fillMaxWidth()) {
                                        SwipeRevealListRow(
                                            swipeEnabled = true,
                                            isPinned = template.pinnedAt > 0,
                                            onPinToggle = { viewModel.setTemplatePinned(template.id, template.pinnedAt == 0L) },
                                            onDelete = { if (template.id !in promotedTemplateIds) deleteTarget = template },
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
                                                onStartChat = { onStartChat(template.id) },
                                            )
                                        }
                                        if (index < filteredTemplates.lastIndex) {
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${t.label}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val deletedLabel = t.label
                    viewModel.deleteTemplate(t.id)
                    deleteTarget = null
                    Toast.makeText(context, UserFacingStrings.itemDeleted(deletedLabel.ifBlank { "未命名模板" }), Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun WorkbenchTemplateListRowInner(
    template: WorldTemplateEntity,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
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
    template: WorldTemplateEntity,
    onStartChat: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
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
                Text(
                    template.label.ifEmpty { "未命名模板" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
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
                FilledTonalButton(
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
