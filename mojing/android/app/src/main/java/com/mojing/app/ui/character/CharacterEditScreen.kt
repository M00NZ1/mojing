package com.mojing.app.ui.character

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.media.BuiltinCharacterImages
import com.mojing.app.ui.character.components.AvatarEditor
import com.mojing.app.ui.character.components.BuiltinCharacterImagePickerSheet
import com.mojing.app.ui.character.components.CardImageEditor
import com.mojing.app.ui.character.components.ColorPickerField
import com.mojing.app.ui.chat.ChatMacroDefinitions
import com.mojing.app.ui.common.ApiProviderPresets
import com.mojing.app.ui.common.ApiVendorModelHint
import com.mojing.app.ui.common.ApiVendorPresetRow
import com.mojing.app.ui.common.CollapsiblePresetUrlModelBlock
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.common.hideImeKeyboard
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ContentDocumentWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(
    characterId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CharacterEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeOpen = isImeKeyboardOpen()
    val clipboardManager = LocalClipboardManager.current
    val hasBuiltinPresets = remember(context) {
        BuiltinCharacterImages.listLocalPresets(context).isNotEmpty()
    }
    var showBuiltinAvatarSheet by remember { mutableStateOf(false) }
    var showBuiltinCardSheet by remember { mutableStateOf(false) }
    var isAvatarImporting by remember { mutableStateOf(false) }
    var isCardImageProcessing by remember { mutableStateOf(false) }
    var showMacroSheet by remember { mutableStateOf(false) }
    var routesOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showManualCharChatUrlModel by rememberSaveable { mutableStateOf(false) }
    var showManualCharImageUrlModel by rememberSaveable { mutableStateOf(false) }
    var showManualCharVoiceUrlModel by rememberSaveable { mutableStateOf(false) }

    data class PendingExport(val fileName: String, val bytes: ByteArray)
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var isWritingExport by remember { mutableStateOf(false) }

    val canSave = state.loadError == null && (!state.isPersisted || state.isDirty)
    val pageBusy = state.isSaving || isAvatarImporting || isCardImageProcessing ||
        state.isGeneratingCardImage || state.isPreparingExport || pendingExport != null || isWritingExport

    fun requestBack() {
        focusManager.clearFocus()
        when {
            pageBusy -> scope.launch { snackbarHostState.showSnackbar("正在保存、处理图片或导出，请稍候") }
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

    val exportDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pendingExport ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            pendingExport = null
            return@rememberLauncherForActivityResult
        }
        if (isWritingExport) return@rememberLauncherForActivityResult
        isWritingExport = true
        scope.launch {
            var feedback: String? = null
            try {
                ContentDocumentWriter.writeBytes(context, uri, p.bytes)
                feedback = UserFacingStrings.exportDocumentSaved(p.fileName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                feedback = UserFacingStrings.documentWriteFailed(e.message)
            } finally {
                isWritingExport = false
                pendingExport = null
            }
            feedback?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    LaunchedEffect(state.exportMessage) {
        val m = state.exportMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.clearExportMessage()
    }

    LaunchedEffect(state.snackbar) {
        val m = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.consumeSnackbar()
    }

    LaunchedEffect(characterId) { viewModel.load(characterId) }

    LaunchedEffect(state.apiBaseUrl) {
        if (ApiProviderPresets.isExactSinglePresetBaseUrl(state.apiBaseUrl)) showManualCharChatUrlModel = false
    }
    LaunchedEffect(state.imageGenBaseUrl) {
        if (ApiProviderPresets.isExactSinglePresetBaseUrl(state.imageGenBaseUrl)) showManualCharImageUrlModel = false
    }
    LaunchedEffect(state.voiceApiBaseUrl) {
        if (ApiProviderPresets.isExactSinglePresetBaseUrl(state.voiceApiBaseUrl)) showManualCharVoiceUrlModel = false
    }

    var prevGeneratingCard by remember { mutableStateOf(false) }
    LaunchedEffect(state.isGeneratingCardImage) {
        if (prevGeneratingCard && !state.isGeneratingCardImage) {
            snackbarHostState.showSnackbar("若生成了新封面，请保存角色后再离开。")
        }
        prevGeneratingCard = state.isGeneratingCardImage
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (characterId == 0L && !state.isPersisted) "新建角色" else "编辑角色") },
                navigationIcon = {
                    IconButton(onClick = ::requestBack, enabled = !pageBusy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save(characterId) },
                        enabled = state.isLoaded && canSave && !pageBusy && !state.isAiCompleting,
                    ) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Save, if (canSave) "保存修改" else "已保存")
                    }
                }
            )
        },
    ) { padding ->
        if (state.loadError != null) {
            EmptyState(
                icon = Icons.Default.ErrorOutline,
                title = "无法打开角色",
                message = state.loadError.orEmpty(),
                actionLabel = "重新加载",
                onAction = { viewModel.load(characterId) },
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
                    Text("正在读取角色…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("角色设定", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(value = state.name, onValueChange = { viewModel.updateName(it) }, label = { Text("角色名") }, placeholder = { Text("如：林云") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            var encBindExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = encBindExpanded,
                onExpandedChange = { encBindExpanded = it },
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    readOnly = true,
                    value = when {
                        state.boundEncyclopediaId <= 0L -> "暂不绑定百科"
                        else -> state.encyclopediaOptions.find { it.id == state.boundEncyclopediaId }?.name?.ifBlank { null }
                            ?: "百科资料不可用"
                    },
                    onValueChange = {},
                    label = { Text("所属百科（可选）") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = encBindExpanded) },
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = encBindExpanded,
                    onDismissRequest = { encBindExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("暂不绑定百科") },
                        onClick = {
                            viewModel.updateBoundEncyclopediaId(0L)
                            encBindExpanded = false
                        },
                    )
                    state.encyclopediaOptions.forEach { enc ->
                        DropdownMenuItem(
                            text = { Text(enc.name.ifBlank { "未命名百科" }) },
                            onClick = {
                                viewModel.updateBoundEncyclopediaId(enc.id)
                                encBindExpanded = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(value = state.personaPrompt, onValueChange = { viewModel.updatePersonaPrompt(it) }, label = { Text("人设提示词") }, placeholder = { Text("描述角色的性格、背景、说话风格...") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            TextButton(onClick = { focusManager.clearFocus(); showMacroSheet = true }, modifier = Modifier.align(Alignment.Start)) {
                Text("插入宏变量")
            }

            Button(
                onClick = { viewModel.aiCompletePersona() },
                enabled = state.isPersisted && !state.isDirty && !state.isAiCompleting && !pageBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (state.isAiCompleting) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isAiCompleting) "人设任务进行中…" else "补全人设")
            }
            if (!state.isPersisted || state.isDirty) {
                Text(
                    if (!state.isPersisted) "保存角色后可使用补全。" else "先保存当前修改，再补全人设。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!state.hasPublicTextKey && state.apiKey.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "补全人设前需要配置 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }

            HorizontalDivider()
            Text("角色形象", style = MaterialTheme.typography.titleMedium)
            AvatarEditor(
                avatarImagePath = state.avatarImagePath,
                avatarColor = state.avatarColor,
                onImageSelected = { path -> viewModel.updateAvatarImagePath(path) },
                onColorChanged = { viewModel.updateAvatarColor(it) },
                onImportingChanged = { isAvatarImporting = it },
                onOpenBuiltinLibrary = if (hasBuiltinPresets) {
                    { focusManager.clearFocus(); showBuiltinAvatarSheet = true }
                } else {
                    null
                },
            )

            CardImageEditor(
                cardImagePath = state.cardImagePath,
                avatarImagePath = state.avatarImagePath,
                onCardImagePathChanged = { viewModel.updateCardImagePath(it) },
                onDuplicateFromAvatar = { viewModel.duplicateAvatarToCardImage() },
                onOpenBuiltinLibrary = if (hasBuiltinPresets) {
                    { focusManager.clearFocus(); showBuiltinCardSheet = true }
                } else {
                    null
                },
                isGeneratingCardImage = state.isGeneratingCardImage,
                onGenerateWithBackend = { viewModel.generateCardImageViaBackend() },
                onProcessingChanged = { isCardImageProcessing = it },
            )
            ColorPickerField(selectedColor = state.avatarColor, onColorSelected = { viewModel.updateAvatarColor(it) })

            OutlinedButton(
                onClick = { routesOpen = !routesOpen },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (routesOpen) "收起线路与模型" else "线路与模型")
            }

            if (routesOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
            HorizontalDivider()
            Text("线路与模型", style = MaterialTheme.typography.titleMedium)
            if (!state.hasPublicTextKey && state.apiKey.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "未配置 API Key，不影响保存角色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
            ApiVendorPresetRow(
                sectionLabel = "对话快捷线路",
                currentBaseUrl = state.apiBaseUrl,
                currentModel = state.modelName,
                onBaseUrlChange = { viewModel.updateApiBaseUrl(it) },
                onModelChange = { viewModel.updateModelName(it) },
                modelHint = ApiVendorModelHint.CHAT,
            )
            OutlinedTextField(value = state.apiKey, onValueChange = { viewModel.updateApiKey(it) }, label = { Text("API Key（空则用全局）") }, placeholder = { Text("sk-xxx") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            val chatCharCollapsed = ApiProviderPresets.isExactSinglePresetBaseUrl(state.apiBaseUrl)
            CollapsiblePresetUrlModelBlock(
                collapsedPreset = chatCharCollapsed,
                showManualFields = showManualCharChatUrlModel,
                onExpandManual = { showManualCharChatUrlModel = true },
                onCollapseManual = { showManualCharChatUrlModel = false },
                baseUrl = state.apiBaseUrl,
                model = state.modelName,
                onBaseChange = { viewModel.updateApiBaseUrl(it) },
                onModelChange = { viewModel.updateModelName(it) },
                baseLabel = "URL",
                basePlaceholder = "https://api.deepseek.com",
                modelLabel = "模型名称",
                modelPlaceholder = "deepseek-chat",
                baseMultiline = false,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("始终使用思考/Max", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = state.thinkMaxEnabled,
                    onCheckedChange = { viewModel.updateThinkMaxEnabled(it) }
                )
            }
            OutlinedTextField(
                value = state.thinkMaxModelName,
                onValueChange = { viewModel.updateThinkMaxModelName(it) },
                label = { Text("思考/Max 模型覆盖（可选）") },
                placeholder = { Text("留空则与上方主模型相同") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedButton(
                onClick = {
                    viewModel.probeTextApi { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                },
                enabled = state.probeBusyChannel == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("测试对话线路") }

            HorizontalDivider()
            Text("配图", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用角色生图", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.imageGenEnabled,
                    onCheckedChange = { viewModel.updateImageGenEnabled(it) }
                )
            }
            ApiVendorPresetRow(
                sectionLabel = "配图快捷线路",
                currentBaseUrl = state.imageGenBaseUrl,
                currentModel = state.imageGenModel,
                onBaseUrlChange = { viewModel.updateImageGenBaseUrl(it) },
                onModelChange = { viewModel.updateImageGenModel(it) },
                modelHint = ApiVendorModelHint.IMAGE,
                enabled = state.imageGenEnabled,
            )
            OutlinedTextField(
                value = state.imageGenApiKey,
                onValueChange = { viewModel.updateImageGenApiKey(it) },
                label = { Text("配图 API Key（空则用全局配图 Key；全局也为空则用公共 Key）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = state.imageGenEnabled,
            )
            val imageCharCollapsed = ApiProviderPresets.isExactSinglePresetBaseUrl(state.imageGenBaseUrl)
            CollapsiblePresetUrlModelBlock(
                collapsedPreset = imageCharCollapsed,
                showManualFields = showManualCharImageUrlModel,
                onExpandManual = { showManualCharImageUrlModel = true },
                onCollapseManual = { showManualCharImageUrlModel = false },
                baseUrl = state.imageGenBaseUrl,
                model = state.imageGenModel,
                onBaseChange = { viewModel.updateImageGenBaseUrl(it) },
                onModelChange = { viewModel.updateImageGenModel(it) },
                baseLabel = "配图 URL",
                basePlaceholder = "空则用对话地址或设置中的全局配图地址",
                modelLabel = "配图模型",
                modelPlaceholder = "dall-e-3",
                baseMultiline = false,
                fieldsEnabled = state.imageGenEnabled,
            )
            OutlinedButton(
                onClick = {
                    viewModel.probeImageApi { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                },
                enabled = state.probeBusyChannel == null && state.imageGenEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("测试配图线路") }

            HorizontalDivider()
            Text("朗读", style = MaterialTheme.typography.titleMedium)
            ApiVendorPresetRow(
                sectionLabel = "朗读快捷线路",
                currentBaseUrl = state.voiceApiBaseUrl,
                currentModel = state.voiceModel,
                onBaseUrlChange = { viewModel.updateVoiceApiBaseUrl(it) },
                onModelChange = { viewModel.updateVoiceModel(it) },
                modelHint = ApiVendorModelHint.VOICE_TTS,
            )
            OutlinedTextField(
                value = state.voiceApiKey,
                onValueChange = { viewModel.updateVoiceApiKey(it) },
                label = { Text("朗读 API Key（空则用对话 API Key / 全局）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            val voiceCharCollapsed = ApiProviderPresets.isExactSinglePresetBaseUrl(state.voiceApiBaseUrl)
            CollapsiblePresetUrlModelBlock(
                collapsedPreset = voiceCharCollapsed,
                showManualFields = showManualCharVoiceUrlModel,
                onExpandManual = { showManualCharVoiceUrlModel = true },
                onCollapseManual = { showManualCharVoiceUrlModel = false },
                baseUrl = state.voiceApiBaseUrl,
                model = state.voiceModel,
                onBaseChange = { viewModel.updateVoiceApiBaseUrl(it) },
                onModelChange = { viewModel.updateVoiceModel(it) },
                baseLabel = "朗读 URL",
                basePlaceholder = "空则用对话 URL",
                modelLabel = "朗读模型",
                modelPlaceholder = "system = 本机免费",
                baseMultiline = false,
            )
            Text(
                "保存角色后，在对话中点击消息旁的 🔊 试听这条朗读线路。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.probeBusyChannel != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                if (state.probeStreamHint.isNotBlank()) {
                    Text(
                        state.probeStreamHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                state.probeStreamLines.takeLast(6).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
                }
            }

            OutlinedButton(
                onClick = { advancedOpen = !advancedOpen },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (advancedOpen) "收起导出与高级设置" else "导出与高级设置")
            }

            if (advancedOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
            if (state.isPersisted) {
                HorizontalDivider()
                Text("便携包", style = MaterialTheme.typography.titleMedium)
                val exportBusy = state.isPreparingExport || pendingExport != null || isWritingExport
                val doExport: (PortableExportFormat, Boolean) -> Unit = { fmt, summary ->
                    if (!exportBusy) {
                        viewModel.buildPortableExport(characterId, fmt, summary) { pair ->
                            pair?.let {
                                pendingExport = PendingExport(it.first, it.second)
                                exportDocLauncher.launch(it.first)
                            }
                        }
                    }
                }
                Text("原文导出", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { doExport(PortableExportFormat.JSON, false) }, enabled = !exportBusy, modifier = Modifier.weight(1f)) { Text("JSON") }
                    Button(onClick = { doExport(PortableExportFormat.TXT, false) }, enabled = !exportBusy, modifier = Modifier.weight(1f)) { Text("TXT") }
                    Button(onClick = { doExport(PortableExportFormat.DOCX, false) }, enabled = !exportBusy, modifier = Modifier.weight(1f)) { Text("DOCX") }
                }
                OutlinedButton(
                    onClick = {
                        if (!exportBusy) {
                            viewModel.buildTavernPngExport(characterId) { pair ->
                                pair?.let {
                                    pendingExport = PendingExport(it.first, it.second)
                                    exportDocLauncher.launch(it.first)
                                }
                            }
                        }
                    },
                    enabled = !exportBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导出 PNG 形象卡") }
                Text("智能摘要导出", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { doExport(PortableExportFormat.JSON, true) },
                        enabled = state.hasPublicTextKey && !exportBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("摘要 JSON") }
                    Button(
                        onClick = { doExport(PortableExportFormat.TXT, true) },
                        enabled = state.hasPublicTextKey && !exportBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("摘要 TXT") }
                    Button(
                        onClick = { doExport(PortableExportFormat.DOCX, true) },
                        enabled = state.hasPublicTextKey && !exportBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("摘要 DOCX") }
                }
                if (exportBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                state.isExportingSummary -> "正在请求摘要…"
                                state.isPreparingExport -> "正在准备导出…"
                                isWritingExport -> "正在写入文件…"
                                else -> "请选择保存位置…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HorizontalDivider()
                Text("扩展设定（JSON）", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.characterCardJsonRaw,
                    onValueChange = { viewModel.updateCharacterCardJsonRaw(it) },
                    label = { Text("扩展设定 JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 18,
                )
            }

            HorizontalDivider()
            Text("采样参数", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = state.temperature.toString(), onValueChange = { v -> v.toFloatOrNull()?.let { viewModel.updateTemperature(it) } }, label = { Text("温度") }, placeholder = { Text("0.9") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = state.maxTokens.toString(), onValueChange = { v -> v.toIntOrNull()?.let { viewModel.updateMaxTokens(it) } }, label = { Text("最大Token") }, placeholder = { Text("1200") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = state.topP.toString(), onValueChange = { v -> v.toFloatOrNull()?.let { viewModel.updateTopP(it) } }, label = { Text("Top P") }, placeholder = { Text("1.0") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = state.presencePenalty.toString(), onValueChange = { v -> v.toFloatOrNull()?.let { viewModel.updatePresencePenalty(it) } }, label = { Text("存在惩罚") }, placeholder = { Text("0.0") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.frequencyPenalty.toString(),
                    onValueChange = { v -> v.toFloatOrNull()?.let { viewModel.updateFrequencyPenalty(it) } },
                    label = { Text("频率惩罚") },
                    placeholder = { Text("0.0") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Button(
                onClick = { viewModel.save(characterId) },
                enabled = canSave && !pageBusy && !state.isAiCompleting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
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
                            !state.isPersisted -> "保存角色"
                            state.isDirty -> "保存修改"
                            else -> "已保存"
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        }
    }
        BuiltinCharacterImagePickerSheet(
            visible = showBuiltinAvatarSheet,
            title = "选头像",
            verticalCardPreview = false,
            onDismiss = { showBuiltinAvatarSheet = false },
            onPickPreset = { viewModel.applyBuiltinAvatarFromPreset(it) },
        )
        BuiltinCharacterImagePickerSheet(
            visible = showBuiltinCardSheet,
            title = "选封面",
            verticalCardPreview = true,
            onDismiss = { showBuiltinCardSheet = false },
            onPickPreset = { viewModel.applyBuiltinCardFromPreset(it) },
        )
        if (showMacroSheet) {
            ModalBottomSheet(onDismissRequest = { showMacroSheet = false }) {
                Text(
                    "插入宏变量",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn {
                    items(ChatMacroDefinitions.ALL, key = { it.macro }) { item ->
                        ListItem(
                            headlineContent = { Text(item.label) },
                            supportingContent = {
                                Column {
                                    Text(
                                        item.macro,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                clipboardManager.setText(AnnotatedString(item.macro))
                                scope.launch {
                                    snackbarHostState.showSnackbar("已复制「${item.label}」到剪贴板，可在人设中粘贴")
                                }
                                showMacroSheet = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后，本次尚未保存的角色修改不会保留。") },
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
