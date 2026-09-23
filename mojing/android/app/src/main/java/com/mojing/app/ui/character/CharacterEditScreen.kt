package com.mojing.app.ui.character

import com.mojing.app.ui.common.MoJingLongTextField

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var routesOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var cardJsonOpen by rememberSaveable { mutableStateOf(false) }
    var pendingExit by rememberSaveable { mutableStateOf<String?>(null) }
    var showManualCharChatUrlModel by rememberSaveable { mutableStateOf(false) }
    var showManualCharImageUrlModel by rememberSaveable { mutableStateOf(false) }
    var showManualCharVoiceUrlModel by rememberSaveable { mutableStateOf(false) }

    data class PendingExport(val fileName: String, val bytes: ByteArray)
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var isWritingExport by remember { mutableStateOf(false) }

    val canSave = state.loadError == null && state.personaRefreshError == null && !state.isRefreshingPersona && (!state.isPersisted || state.isDirty || state.saveError != null)
    val pageBusy = state.isSaving || isAvatarImporting || isCardImageProcessing ||
        state.isGeneratingCardImage || state.isPreparingExport || pendingExport != null || isWritingExport

    fun leaveEditor(destination: String) {
        if (destination == "settings") onOpenSettings() else onBack()
    }

    fun requestExit(destination: String) {
        hideImeKeyboard(keyboardController, focusManager)
        when {
            pageBusy -> scope.launch { snackbarHostState.showSnackbar("正在保存、处理图片或导出，请稍候") }
            state.isDirty -> pendingExit = destination
            else -> leaveEditor(destination)
        }
    }

    BackHandler {
        if (isImeOpen) {
            hideImeKeyboard(keyboardController, focusManager)
        } else {
            requestExit("back")
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
        bottomBar = {
            if (state.isLoaded && state.loadError == null && !isImeOpen) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.saveError?.let { error ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(error, modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        Button(
                            onClick = { viewModel.save(characterId) },
                            enabled = canSave && !pageBusy && !state.isAiCompleting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp),
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
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(if (characterId == 0L && !state.isPersisted) "新建角色" else "编辑角色", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { requestExit("back") }, enabled = !pageBusy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (isImeOpen) TextButton(
                        onClick = { viewModel.save(characterId) },
                        enabled = state.isLoaded && canSave && !pageBusy && !state.isAiCompleting,
                    ) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(when {
                            state.isRefreshingPersona -> "读取中"
                            state.personaRefreshError != null -> "待重试"
                            canSave -> "保存"
                            else -> "已保存"
                        })
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
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isImeOpen) state.saveError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            CharacterEditorSectionTitle("基本资料", "名称与百科归属")

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
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            CharacterEditorSectionTitle("人设与表达", "决定角色如何理解和回应对话")
            if (state.personaRefreshError != null || state.isRefreshingPersona) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("更新角色人设", style = MaterialTheme.typography.titleSmall)
                        Text(state.personaRefreshError ?: "正在读取补全结果…",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = viewModel::retryPersonaRefresh,
                            enabled = !state.isRefreshingPersona && !state.isAiCompleting,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.isRefreshingPersona) "读取中…" else "重新读取")
                        }
                    }
                }
            }
            MoJingLongTextField(value = state.personaPrompt, onValueChange = { viewModel.updatePersonaPrompt(it) }, label = "人设提示词", placeholder = "描述角色的性格、背景、说话风格...", modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { focusManager.clearFocus(); showMacroSheet = true }, modifier = Modifier.align(Alignment.Start)) {
                Text("插入宏变量")
            }

            OutlinedButton(
                onClick = { viewModel.aiCompletePersona() },
                enabled = state.isPersisted && !state.isDirty && !state.isAiCompleting && !state.isRefreshingPersona && state.personaRefreshError == null && !pageBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                if (state.isAiCompleting) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
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
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { requestExit("settings") }) { Text("去设置") }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            CharacterEditorSectionTitle("朗读", "为这个角色选择引擎与音色")
            var showVoiceChoice by remember { mutableStateOf(false) }
            val characterVoice = com.mojing.app.data.resolveVoiceChoice(state.voiceProvider, state.voiceModel,
                com.mojing.app.data.VoiceChoice("inherit"))
            OutlinedButton(onClick = { showVoiceChoice = true }, modifier = Modifier.fillMaxWidth()) {
                Text(characterVoice.label())
            }
            Text("此角色的回复使用独立引擎与音色；未指定时跟随对话设置。", style = MaterialTheme.typography.bodySmall)
            if (showVoiceChoice) com.mojing.app.ui.common.VoiceChoicePicker(
                choice = characterVoice, allowInherit = true,
                onSelected = { choice ->
                    viewModel.updateVoiceProvider(choice.engineId)
                    viewModel.updateVoiceModel(choice.voiceId)
                    showVoiceChoice = false
                }, onDismiss = { showVoiceChoice = false },
            )

            HorizontalDivider()
            CharacterEditorSectionHeader(
                title = "角色形象",
                summary = "头像、封面与代表色",
                expanded = appearanceOpen,
                enabled = !isAvatarImporting && !isCardImageProcessing,
                onClick = { focusManager.clearFocus(); appearanceOpen = !appearanceOpen },
            )
            if (appearanceOpen) {
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

            }
            HorizontalDivider()
            CharacterEditorSectionHeader(
                title = "线路与模型",
                summary = "对话模型、连接配置与配图",
                expanded = routesOpen,
                onClick = { focusManager.clearFocus(); routesOpen = !routesOpen },
            )

            if (routesOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
            Text("对话线路", style = MaterialTheme.typography.titleSmall)
            if (!state.hasPublicTextKey && state.apiKey.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "未配置 API Key，不影响保存角色",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { requestExit("settings") }) { Text("去设置") }
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
            OutlinedTextField(value = state.apiKey, onValueChange = { viewModel.updateApiKey(it) }, label = { Text("角色专用 API Key") }, placeholder = { Text("sk-xxx") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("专用线路需填写配套的 Key、接口地址和模型；留空继承公共配置，也可在对话中直接选择平台。", style = MaterialTheme.typography.bodySmall)
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

            HorizontalDivider()
            CharacterEditorSectionHeader(
                title = "导出与高级设置",
                summary = "便携包、扩展设定与采样参数",
                expanded = advancedOpen,
                onClick = { focusManager.clearFocus(); advancedOpen = !advancedOpen },
            )

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
                CharacterEditorSectionHeader(
                    title = "扩展设定",
                    summary = "角色卡 JSON · ${if (state.characterCardJsonRaw.isEmpty()) "尚无内容" else "已填写"}",
                    expanded = cardJsonOpen,
                    onClick = { focusManager.clearFocus(); cardJsonOpen = !cardJsonOpen },
                )
                if (cardJsonOpen) {
                    OutlinedTextField(
                        value = state.characterCardJsonRaw,
                        onValueChange = { viewModel.updateCharacterCardJsonRaw(it) },
                        label = { Text("扩展设定 JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 18,
                    )
                }
            }

            HorizontalDivider()
            Text("采样参数", style = MaterialTheme.typography.titleMedium)
            Text("控制回复的变化、长度和重复程度", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 340.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SamplingParameterField(state.temperature, viewModel::updateTemperature, "温度", Modifier.fillMaxWidth(), description = "越低越稳定")
                        SamplingParameterField(state.topP, viewModel::updateTopP, "Top P", Modifier.fillMaxWidth(), description = "控制候选词范围")
                        SamplingParameterField(state.maxTokens, viewModel::updateMaxTokens, "最大 Token", Modifier.fillMaxWidth(), integer = true, description = "单次回复的输出上限")
                        SamplingParameterField(state.presencePenalty, viewModel::updatePresencePenalty, "存在惩罚", Modifier.fillMaxWidth(), signed = true, description = "减少重复主题")
                        SamplingParameterField(state.frequencyPenalty, viewModel::updateFrequencyPenalty, "频率惩罚", Modifier.fillMaxWidth(), signed = true, description = "减少重复用词")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SamplingParameterField(state.temperature, viewModel::updateTemperature, "温度", Modifier.weight(1f), description = "越低越稳定")
                            SamplingParameterField(state.topP, viewModel::updateTopP, "Top P", Modifier.weight(1f), description = "控制候选词范围")
                        }
                        SamplingParameterField(state.maxTokens, viewModel::updateMaxTokens, "最大 Token", Modifier.fillMaxWidth(), integer = true, description = "单次回复的输出上限")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SamplingParameterField(state.presencePenalty, viewModel::updatePresencePenalty, "存在惩罚", Modifier.weight(1f), signed = true, description = "减少重复主题")
                            SamplingParameterField(state.frequencyPenalty, viewModel::updateFrequencyPenalty, "频率惩罚", Modifier.weight(1f), signed = true, description = "减少重复用词")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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

    pendingExit?.let { destination ->
        AlertDialog(
            onDismissRequest = { pendingExit = null },
            title = { Text("保存角色修改？") },
            text = { Text("离开编辑页前，可以保存本次修改，也可以放弃修改。") },
            confirmButton = {
                TextButton(
                    enabled = state.isLoaded && canSave && !pageBusy && !state.isAiCompleting,
                    onClick = {
                        pendingExit = null
                        viewModel.save(characterId) { leaveEditor(destination) }
                    },
                ) { Text("保存并离开") }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { pendingExit = null }) { Text("继续编辑") }
                    TextButton(
                        enabled = !pageBusy,
                        onClick = { pendingExit = null; leaveEditor(destination) },
                    ) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

@Composable
private fun CharacterEditorSectionTitle(
    title: String,
    summary: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CharacterEditorSectionHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
    }
}
