package com.mojing.app.ui.workbench

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import com.mojing.app.ui.common.MoJingTonalButton as FilledTonalButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.domain.workbench.LocalWorldTemplateQuality
import com.mojing.app.ui.common.MoJingLongTextField
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.common.LlmKeySetupHintCard
import com.mojing.app.ui.common.TemplateWorldPromptSkeletonBlock
import com.mojing.app.ui.common.hideImeKeyboard
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.workbench.components.BackendQualityReportCard
import com.mojing.app.ui.workbench.components.CoverImagePicker
import com.mojing.app.ui.workbench.components.TemplateQualityOverviewCard
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch

val CATEGORIES = listOf("玄幻", "武侠", "科幻", "奇幻", "都市", "历史", "悬疑", "末世", "其他")
val GAMEPLAY_MODES = listOf("自由剧情", "多人角色扮演", "密室脱逃", "战斗冒险", "养成模拟", "解谜探案", "恋爱模拟")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditScreen(
    templateId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TemplateEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeOpen = isImeKeyboardOpen()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncPublicLlmKeyFromStorage()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.syncPublicLlmKeyFromStorage()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(templateId) { viewModel.load(templateId) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.snackbar) {
        val m = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.consumeSnackbar()
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.saveError) {
        if (state.saveError != null) scrollState.animateScrollTo(0)
    }
    var categoryExpanded by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }
    var isCoverImporting by remember { mutableStateOf(false) }
    var moreToolsOpen by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val canSave = state.loadError == null && state.completionRefreshError == null && !state.isRefreshingCompletion && (!state.isPersisted || state.isDirty)
    val saveBusy = state.isSaving || isCoverImporting

    fun requestBack() {
        focusManager.clearFocus()
        when {
            saveBusy -> scope.launch { snackbarHostState.showSnackbar("正在保存或读取封面，请稍候") }
            state.isDirty -> showDiscardDialog = true
            else -> onBack()
        }
    }

    BackHandler {
        if (isImeOpen) {
            hideImeKeyboard(keyboardController, focusManager)
        } else {
            requestBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (templateId == 0L && !state.isPersisted) "新建设定模板" else "编辑设定模板")
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack, enabled = !saveBusy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save() },
                        enabled = state.isLoaded && canSave && !saveBusy && !state.isAiCompleting,
                    ) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Save, if (state.completionRefreshError != null || state.isRefreshingCompletion) "等待读取补全结果" else if (canSave) "保存修改" else "已保存")
                    }
                }
            )
        }
    ) { padding ->
        if (state.loadError != null) {
            EmptyState(
                icon = Icons.Default.ErrorOutline,
                title = "无法打开模板",
                message = state.loadError.orEmpty(),
                actionLabel = if (state.loadErrorCanReturn) "返回工坊" else "重新加载",
                onAction = if (state.loadErrorCanReturn) onBack else ({ viewModel.load(templateId) }),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else if (!state.isLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("正在读取模板…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.hasPublicLlmKey) {
                    LlmKeySetupHintCard(
                        message = "补全世界设定前，请先在设置填写 API Key",
                        onOpenSettings = onOpenSettings,
                    )
                }

                if (state.completionRefreshError != null || state.isRefreshingCompletion) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("更新世界设定", style = MaterialTheme.typography.titleSmall)
                            Text(
                                state.completionRefreshError ?: "正在读取补全结果…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = viewModel::retryCompletionRefresh,
                                enabled = !state.isRefreshingCompletion && !state.isAiCompleting,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (state.isRefreshingCompletion) "读取中…" else "重新读取") }
                        }
                    }
                }

                state.saveError?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("保存未完成", style = MaterialTheme.typography.titleSmall)
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(
                                onClick = if (state.saveErrorCanReturn) ::requestBack else viewModel::retrySave,
                                enabled = !state.isSaving && !state.isAiCompleting,
                            ) {
                                Icon(
                                    if (state.saveErrorCanReturn) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Refresh,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.saveErrorCanReturn) "返回工坊" else if (state.isSaving) "保存中…" else "重试保存")
                            }
                        }
                    }
                }

                Text("基本信息", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.label,
                    onValueChange = { viewModel.updateLabel(it) },
                    label = { Text("模板名称") },
                    placeholder = { Text("如：修仙大世界") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = {
                        if (it) focusManager.clearFocus()
                        categoryExpanded = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = state.category, onValueChange = {}, readOnly = true,
                        label = { Text("分类") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        CATEGORIES.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = { viewModel.updateCategory(cat); categoryExpanded = false })
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = {
                        if (it) focusManager.clearFocus()
                        modeExpanded = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = state.gameplayMode, onValueChange = {}, readOnly = true,
                        label = { Text("故事模式") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        GAMEPLAY_MODES.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode) }, onClick = { viewModel.updateGameplayMode(mode); modeExpanded = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = state.summary, onValueChange = { viewModel.updateSummary(it) },
                    label = { Text("世界摘要") }, placeholder = { Text("用一两句话说明这个世界的背景与核心冲突") }, modifier = Modifier.fillMaxWidth(), maxLines = 3
                )

                HorizontalDivider()
                Text("世界设定", style = MaterialTheme.typography.titleMedium)

                if (state.isAiCompleting) {
                    TemplateWorldPromptSkeletonBlock()
                } else {
                    MoJingLongTextField(
                        value = state.worldPrompt,
                        onValueChange = viewModel::updateWorldPrompt,
                        label = "世界设定正文",
                        placeholder = "说明世界背景、势力、地点、规则与当前局势",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MoJingLongTextField(
                        value = state.antiCheatPrompt,
                        onValueChange = viewModel::updateAntiCheatPrompt,
                        label = "固定规则",
                        placeholder = "写下不能被剧情临时改写的规则、代价与边界",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("例如：身份、情报和资源不能凭一句话获得", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                }

                FilledTonalButton(
                    onClick = { viewModel.aiCompleteWorldPrompt() },
                    enabled = state.isPersisted && !state.isDirty && !state.isAiCompleting && !saveBusy && !state.isRefreshingCompletion && state.completionRefreshError == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (state.isAiCompleting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在补全世界设定…")
                    } else {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("补全世界设定")
                    }
                }
                if (!state.isPersisted || state.isDirty) {
                    Text(
                        if (!state.isPersisted) "保存模板后可使用补全。" else "先保存当前修改，再补全世界设定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()
                CoverImagePicker(
                    imagePath = state.coverImagePath,
                    onImageSelected = { viewModel.updateCoverImage(it) },
                    onImportingChanged = { isCoverImporting = it },
                )

                OutlinedButton(
                    onClick = { moreToolsOpen = !moreToolsOpen },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(
                        if (moreToolsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (moreToolsOpen) "收起更多工具" else "检查与更多工具")
                }

                if (moreToolsOpen) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("设定检查", style = MaterialTheme.typography.titleMedium)
                        val qualityOverview = LocalWorldTemplateQuality.compute(
                            label = state.label,
                            summary = state.summary,
                            worldPrompt = state.worldPrompt,
                            antiCheatPrompt = state.antiCheatPrompt,
                        )
                        TemplateQualityOverviewCard(qualityOverview)

                        Button(
                            onClick = { viewModel.fetchFullBackendQualityReport() },
                            enabled = !state.isBackendQualityLoading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            if (state.isBackendQualityLoading) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在检查…")
                            } else {
                                Text("检查完整设定")
                            }
                        }
                        state.backendQualityReport?.let {
                            BackendQualityReportCard(it)
                            TextButton(
                                onClick = { viewModel.clearBackendQualityReport() },
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("清除报告") }
                        }

                        HorizontalDivider()
                        Text("内置封面素材", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "按名称或标签搜索可用封面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = state.coverBuiltinSearchQuery,
                            onValueChange = { viewModel.updateCoverBuiltinSearchQuery(it) },
                            label = { Text("名称或标签") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { viewModel.searchBuiltinCoverAssets() },
                            enabled = !state.isCoverBuiltinLoading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            if (state.isCoverBuiltinLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在搜索…")
                            } else {
                                Text("搜索封面")
                            }
                        }
                        if (state.coverBuiltinAssets.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.coverBuiltinAssets.forEach { asset ->
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(asset.label, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                asset.licenseName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Button(
                                                onClick = { viewModel.applyBuiltinCoverStoragePath(asset.storagePath) },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text("使用此封面") }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider()
                        OutlinedButton(
                            onClick = {
                                val url = viewModel.exportThisTemplateDownloadUrl()
                                if (url.isNullOrBlank()) {
                                    viewModel.showExportTemplateUrlMissing()
                                } else {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            },
                            enabled = state.isPersisted,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("在浏览器中导出此模板") }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.save() },
                    enabled = canSave && !saveBusy && !state.isAiCompleting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在保存…")
                    } else {
                        Text(
                            when {
                                !state.isPersisted -> "保存模板"
                                state.isDirty -> "保存修改"
                                else -> "已保存"
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后，本次尚未保存的模板修改不会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                ) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }
}
