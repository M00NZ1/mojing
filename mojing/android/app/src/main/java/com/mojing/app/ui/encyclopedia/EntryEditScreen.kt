package com.mojing.app.ui.encyclopedia

import com.mojing.app.ui.common.MoJingLongTextField

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mojing.app.media.CharacterCardImageProcessor
import com.mojing.app.ui.common.avatarImageModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.entity.EntryVersionEntity
import com.mojing.app.ui.common.EntryAiCompleteSkeletonBlock
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.common.LlmKeySetupHintCard
import com.mojing.app.ui.common.hideImeKeyboard
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.encyclopedia.meta.EntryEditMetaSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val ENTRY_TYPE_LABELS = mapOf(
    "world" to "世界", "character" to "角色", "faction" to "阵营",
    "location" to "地点", "item" to "物品", "event" to "事件",
    "skill" to "技能", "creature" to "生物", "profession" to "职业", "concept" to "概念",
    "timeline" to "时间线",
)

private enum class EntryEditSubTab {
    EDIT,
    VERSIONS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    encyclopediaId: Long,
    entryId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSource: (EntrySourceTarget) -> Unit = {},
    viewModel: EntryEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.closeSourcePreview() } }
    if (state.sourcePreviewOpen) {
        EntrySourcePreview(state.sourceLoading, state.sourceContent, state.sourceError,
            onClose = viewModel::closeSourcePreview, onRetry = viewModel::openSourcePreview,
            sourceIndex = state.sourceIndex, sourceCount = state.sourceMessageIds.size,
            onSourceChange = viewModel::showSourceMessage,
            onOpenConversation = state.sourceTarget?.let { target -> { onOpenSource(target) } })
    }
    var subTab by remember { mutableStateOf(EntryEditSubTab.EDIT) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeOpen = isImeKeyboardOpen()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isCoverImporting by remember { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val canSave = state.loadError == null && (!state.isPersisted || state.isDirty)
    val blockingBusy = state.isSaving || state.isGeneratingCover || isCoverImporting
    val pageBusy = blockingBusy || state.isAiCompleting
    val saveEnabled = state.isLoaded && canSave && !pageBusy && !state.isLoadingVersions
    val saveLabel = when {
        state.isSaving -> "正在保存…"
        !state.isPersisted -> "保存条目"
        state.isDirty -> "保存修改"
        else -> "已保存"
    }

    fun requestBack() {
        focusManager.clearFocus()
        when {
            blockingBusy -> scope.launch { snackbarHostState.showSnackbar("正在保存或处理封面，请稍候") }
            state.isDirty -> showDiscardDialog = true
            else -> onBack()
        }
    }

    BackHandler {
        if (isImeOpen) hideImeKeyboard(keyboardController, focusManager) else requestBack()
    }
    val coverPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || isCoverImporting) return@rememberLauncherForActivityResult
        isCoverImporting = true
        scope.launch {
            var path: String? = null
            var handedOff = false
            try {
                withContext(Dispatchers.IO) {
                    path = CharacterCardImageProcessor.processAndSave(context, uri)
                }
                if (path != null) {
                    viewModel.updateCoverImagePath(path!!)
                    handedOff = true
                } else {
                    snackbarHostState.showSnackbar("无法处理所选图片")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "无法处理所选图片")
            } finally {
                if (!handedOff) {
                    path?.let { unclaimed ->
                        withContext(NonCancellable + Dispatchers.IO) { File(unclaimed).delete() }
                    }
                }
                isCoverImporting = false
            }
        }
    }
    LaunchedEffect(encyclopediaId, entryId) {
        subTab = EntryEditSubTab.EDIT
        if (!viewModel.state.value.isLoaded) viewModel.load(encyclopediaId, entryId)
    }

    LaunchedEffect(state.snackbar) {
        val m = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.consumeSnackbar()
    }

    val editScroll = rememberScrollState()
    val versionScroll = rememberScrollState()
    LaunchedEffect(state.versions.firstOrNull()?.id) { versionScroll.scrollTo(0) }
    var typeExpanded by remember { mutableStateOf(false) }

    val typeOptions = remember(state.encyclopediaHint) { filteredEntryTypeOptions(state.encyclopediaHint) }
    val versionTimeFmt = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
    fun formatVersionTime(v: EntryVersionEntity): String =
        versionTimeFmt.format(Instant.ofEpochMilli(v.createdAt))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (entryId == 0L && !state.isPersisted) "新建条目" else "编辑条目", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = ::requestBack, enabled = !blockingBusy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (isImeOpen) {
                        TextButton(onClick = { viewModel.save() }, enabled = saveEnabled) {
                            Text(saveLabel)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!isImeOpen && state.isLoaded && state.loadError == null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Button(
                            onClick = { viewModel.save() },
                            enabled = saveEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(saveLabel) }
                    }
                }
            }
        }
    ) { padding ->
        if (state.loadError != null) {
            EmptyState(
                icon = Icons.Default.ErrorOutline,
                title = "无法打开词条",
                message = state.loadError.orEmpty(),
                actionLabel = "重新加载",
                onAction = { viewModel.load(encyclopediaId, entryId) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else if (!state.isLoaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("正在读取词条…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScrollableTabRow(
                selectedTabIndex = subTab.ordinal,
                edgePadding = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = subTab == EntryEditSubTab.EDIT,
                    onClick = { focusManager.clearFocus(); subTab = EntryEditSubTab.EDIT },
                    text = { Text("编辑") }
                )
                Tab(
                    selected = subTab == EntryEditSubTab.VERSIONS,
                    onClick = { focusManager.clearFocus(); subTab = EntryEditSubTab.VERSIONS },
                    text = { Text("版本") }
                )
            }

            when (subTab) {
                EntryEditSubTab.EDIT -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(editScroll)
                        .padding(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("基本资料", style = MaterialTheme.typography.titleMedium)
                    EntryConfidenceSelector(state.confidence, viewModel::updateConfidence, enabled = !pageBusy)
                    if (state.hasSourceMessage) {
                        OutlinedButton(onClick = viewModel::openSourcePreview) { Text("查看对话原文") }
                    }
                    if (state.isConversationNote) {
                        Text(
                            "对话资料 · 在此编辑正文与确认状态；角色设定在角色页管理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 内置名称生成器（generate-names）：与 Web 一致见 [EncyclopediaUiConfig]；当前关闭且 Android 无独立入口。
                    OutlinedTextField(
                        value = state.title, onValueChange = { viewModel.updateTitle(it) },
                        label = { Text("标题") }, placeholder = { Text("如：云霄剑派") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )

                    ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { if (it) focusManager.clearFocus(); typeExpanded = it }) {
                        OutlinedTextField(
                            value = ENTRY_TYPE_LABELS[state.entryType] ?: state.entryType,
                            onValueChange = {},
                            readOnly = true, label = { Text("类型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                            typeOptions.forEach { (label, type) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { viewModel.updateEntryType(type); typeExpanded = false }
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("正文与摘要", style = MaterialTheme.typography.titleMedium)
                    if (state.isAiCompleting) {
                        EntryAiCompleteSkeletonBlock()
                    } else {
                        OutlinedTextField(
                            value = state.summary, onValueChange = { viewModel.updateSummary(it) },
                            label = { Text("摘要") }, placeholder = { Text("一句话描述...") }, modifier = Modifier.fillMaxWidth(), maxLines = 2
                        )

                        MoJingLongTextField(
                            value = state.content, onValueChange = { viewModel.updateContent(it) },
                            label = "详细内容", placeholder = "详细的百科条目内容...", modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = state.tags, onValueChange = { viewModel.updateTags(it) },
                            label = { Text("标签 (逗号分隔)") }, placeholder = { Text("如：门派, 正道, 剑法") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.aiComplete() },
                        enabled = state.title.isNotBlank() && !pageBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (state.isAiCompleting) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.isAiCompleting) "正在补全…" else "补全空白内容")
                    }
                    if (!state.hasPublicLlmKey) {
                        LlmKeySetupHintCard(
                            message = "补全内容或生成封面前，请先在设置填写 API Key；手动编辑和保存不受影响",
                            onOpenSettings = onOpenSettings,
                        )
                    }

                    HorizontalDivider()
                    Text("条目封面", style = MaterialTheme.typography.titleMedium)
                    if (state.coverImagePath.isNotBlank()) {
                        AsyncImage(
                            model = avatarImageModel(LocalContext.current, state.coverImagePath),
                            contentDescription = "条目封面预览",
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .aspectRatio(2f / 3f),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { coverPickLauncher.launch("image/*") },
                            enabled = !isCoverImporting && !state.isGeneratingCover,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (isCoverImporting) "正在处理封面…" else "从相册选择封面") }
                        if (state.coverImagePath.isNotBlank()) {
                            TextButton(
                                onClick = { viewModel.updateCoverImagePath("") },
                                enabled = !pageBusy,
                            ) { Text("移除封面") }
                        }
                    }
                    OutlinedTextField(
                        value = state.coverPromptHint,
                        onValueChange = { viewModel.updateCoverPromptHint(it) },
                        label = { Text("生图补充说明（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { viewModel.generateEntryCoverViaBackend() },
                            enabled = !state.isGeneratingCover && !isCoverImporting,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.isGeneratingCover) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text("生成封面")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { advancedOpen = !advancedOpen },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(if (advancedOpen) "收起高级设置" else "高级设置")
                    }

                    if (advancedOpen) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {

                    EntryEditMetaSection(
                        entryType = state.entryType,
                        metaJson = state.metaJson,
                        onMetaJsonChange = { viewModel.updateMetaJson(it) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = state.metaJson, onValueChange = { viewModel.updateMetaJson(it) },
                        label = { Text("Meta 原始 JSON（可直接改）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 12
                    )

                    Row {
                        Checkbox(checked = state.isFeatured, onCheckedChange = { viewModel.updateFeatured(it) })
                        Text("精选条目", modifier = Modifier.padding(start = 4.dp))
                    }
                    }
                    }


                }

                EntryEditSubTab.VERSIONS -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(versionScroll)
                        .padding(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { viewModel.loadVersionPage(false) },
                            enabled = state.isOlderVersionPage && !state.isLoadingVersions && !state.isSaving) {
                            Text("最新版本")
                        }
                        TextButton(onClick = { viewModel.loadVersionPage(true) },
                            enabled = state.hasOlderVersions && !state.isLoadingVersions && !state.isSaving) {
                            Text(if (state.isLoadingVersions) "读取中…" else "更早版本")
                        }
                    }
                    if (!state.isPersisted) {
                        Text(
                            "保存后开始记录版本",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    } else if (state.versions.isEmpty()) {
                        Text("暂无历史快照", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    } else {
                        state.versions.forEach { v ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("v${v.version} · ${formatVersionTime(v)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(v.title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (v.summary.isNotBlank()) {
                                            Text(v.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    TextButton(onClick = {
                                        if (viewModel.applyVersionToForm(v)) subTab = EntryEditSubTab.EDIT
                                    }, enabled = !pageBusy && !state.isLoadingVersions) { Text("载入") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        }
    }

    state.pendingVersion?.let { version ->
        AlertDialog(
            onDismissRequest = viewModel::dismissVersionReplacement,
            title = { Text("载入 v${version.version}？") },
            text = { Text("当前未保存的修改将被替换。载入后可继续编辑，再保存为新版本。") },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.applyVersionToForm(version, replaceDraft = true)) subTab = EntryEditSubTab.EDIT
                }, enabled = !pageBusy && !state.isLoadingVersions) { Text("替换并载入") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissVersionReplacement) { Text("保留当前修改") }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后，本次尚未保存的词条修改不会保留。") },
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
