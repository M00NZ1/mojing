package com.mojing.app.ui.story

import kotlinx.coroutines.launch

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToCreationHub

private val storyTonePresets = listOf(
    "温暖日常",
    "悬疑紧凑",
    "轻松幽默",
    "细腻抒情",
    "史诗冒险",
    "冷峻克制",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorySimulationScreen(
    navController: NavHostController,
    onOpenSession: (Long) -> Unit,
    viewModel: StorySimulationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current
    val isImeOpen = com.mojing.app.ui.common.isImeKeyboardOpen()
    var templateExpanded by remember { mutableStateOf(false) }
    var encyclopediaExpanded by remember { mutableStateOf(false) }
    var charactersExpanded by remember { mutableStateOf(false) }
    var showStopAndLeaveDialog by remember { mutableStateOf(false) }
    var showSavingDialog by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDiscardUnreadableDialog by remember { mutableStateOf(false) }
    var showClearInputDialog by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }
    val isBusy = isLeaving || state.isGenerating || state.isSaving || state.isRestoring || state.recoveryError != null || state.hasPendingStory || state.savedSessionId != null

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        scope.launch { viewModel.flushInputDraftBeforeLeaving() }
    }

    fun dismissStopDialog() {
        showStopAndLeaveDialog = false
        pendingNavigation = null
    }

    fun requestNavigation(action: () -> Unit) {
        if (isLeaving) return
        when {
            state.isSaving -> showSavingDialog = true
            state.isGenerating || (state.hasPendingStory && !state.draftPersisted) -> {
                pendingNavigation = action
                showStopAndLeaveDialog = true
            }
            state.isRestoring || state.recoveryError != null -> action()
            else -> {
                isLeaving = true
                scope.launch {
                    try { if (viewModel.flushInputDraftBeforeLeaving()) action() }
                    finally { isLeaving = false }
                }
            }
        }
    }

    LaunchedEffect(state.isGenerating, state.isSaving, state.hasPendingStory) {
        if (!state.isGenerating && !state.isSaving && !state.hasPendingStory) {
            dismissStopDialog()
            showSavingDialog = false
        }
    }

    BackHandler {
        when {
            isImeOpen -> com.mojing.app.ui.common.hideImeKeyboard(keyboardController, focusManager)
            showStopAndLeaveDialog -> dismissStopDialog()
            showSavingDialog -> showSavingDialog = false
            else -> requestNavigation { navController.returnToCreationHub() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("小说创作", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (isImeOpen && !isBusy && !state.hasPendingStory && state.savedSessionId == null && !state.isRestoring && state.recoveryError == null) {
                        TextButton(enabled = state.premise.isNotBlank(), onClick = { focusManager.clearFocus(); viewModel.createStory(onOpenSession) }) { Text("开始创作") }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            com.mojing.app.ui.common.hideImeKeyboard(keyboardController, focusManager)
                            requestNavigation { navController.returnToCreationHub() }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回创作中心")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (!isImeOpen && !state.hasPendingStory && state.savedSessionId == null && !state.isRestoring && state.recoveryError == null) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                                when {
                                    state.isGenerating -> OutlinedButton(onClick = { viewModel.stopGeneration() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("停止生成")
                                    }
                                    state.isSaving -> Button(
                                        onClick = {},
                                        enabled = false,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.width(20.dp).height(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("正在保存到本地…")
                                    }
                                    state.hasPendingStory -> TextButton(onClick = { requestNavigation {} }) { Text("放弃本次正文") }
                                    state.savedSessionId != null -> Unit
                                    else -> Button(
                                        onClick = { focusManager.clearFocus(); viewModel.createStory(onOpenSession) },
                                        enabled = !isBusy && state.premise.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("生成小说并开始创作")
                                    }
                                }
                            }
                        }
                    }
                }
                MainAppBottomNavigation(
                    navController = navController,
                    onNavigateRequest = { action -> requestNavigation(action) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StoryRecoveryCard(state,
                onSaveOrOpen = { viewModel.createStory(onOpenSession) },
                onCopy = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(viewModel.pendingStoryText())) },
                onDiscard = { pendingNavigation = {}; showStopAndLeaveDialog = true },
                onNewStory = viewModel::startNewStory, onRetryRecovery = viewModel::retryRecovery,
                onCopyRecovery = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(viewModel.recoveryDataText())) },
                onDiscardUnreadable = { showDiscardUnreadableDialog = true },
                onRetryInterrupted = { viewModel.retryInterruptedGeneration(onOpenSession) },
                onDiscardInterrupted = viewModel::discardInterruptedGeneration)
            if (!state.hasPendingStory && state.savedSessionId == null && !state.isRestoring && state.recoveryError == null) {
            Text("写下大致故事背景和开篇走向，AI 会结合已选人物直接生成小说正文。完成后自动进入创作会话，可继续输入后续走向或连续续写。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasInputDraft) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (state.recoveredInputDraft) "已恢复上次填写的创作设定" else "创作设定已在本机暂存",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showClearInputDialog = true }, enabled = !isBusy) { Text("清空草稿") }
                }
                if (!state.templates.isLoading && !state.encyclopedias.isLoading && !state.characters.isLoading &&
                    state.hasUnavailableSelections()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("草稿选择的世界或角色当前不可用，请重新选择或重试加载。",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::clearUnavailableSelections, enabled = !isBusy) { Text("移除失效选择") }
                    }
                }
            }
            StoryGenerationProgressCard(state, onStop = { viewModel.stopGeneration() }, onCopy = clipboardManager::setText,
                onRetry = { viewModel.createStory(onOpenSession) })
            OutlinedTextField(
                value = state.premise,
                onValueChange = viewModel::updatePremise,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("故事背景与大致设定 *") },
                placeholder = { Text("例如：海边小城每逢大雾就会收到来自未来的信。一名修钟师发现，信中提到的人正逐一失踪。") },
                supportingText = { Text("否定设定和人物知情范围会作为持续规则，请尽量明确写出。") },
                minLines = 5,
                maxLines = 10,
                enabled = !isBusy,
            )
            OutlinedTextField(
                value = state.direction,
                onValueChange = viewModel::updateDirection,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("开篇剧情走向（可选）") },
                placeholder = { Text("例如：修钟师先找到第一封信，顺着收信日期调查失踪者。") },
                minLines = 2,
                maxLines = 5,
                enabled = !isBusy,
            )
            OutlinedTextField(
                value = state.tone,
                onValueChange = viewModel::updateTone,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("风格与节奏") },
                placeholder = { Text("例如：温暖克制，节奏舒缓，在关键处逐步加深悬念。") },
                singleLine = true,
                enabled = !isBusy,
            )
            Text("常用风格", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                storyTonePresets.forEach { preset ->
                    FilterChip(
                        selected = state.tone == preset,
                        onClick = { viewModel.updateTone(preset) },
                        enabled = !isBusy,
                        label = { Text(preset) },
                    )
                }
            }

            ExposedDropdownMenuBox(expanded = templateExpanded, onExpandedChange = { if (!isBusy) templateExpanded = it }) {
                val selectedTemplate = state.templates.items.firstOrNull { it.id == state.selectedTemplateId }
                val selectedWorld = state.encyclopedias.items.firstOrNull { it.id == state.selectedEncyclopediaId }
                OutlinedTextField(
                    value = listOfNotNull(selectedTemplate?.label, selectedWorld?.name).joinToString(" + ").ifBlank { "不绑定世界" },
                    onValueChange = {}, readOnly = true, singleLine = true, enabled = !isBusy,
                    label = { Text("世界") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(templateExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = templateExpanded, onDismissRequest = { templateExpanded = false }) {
                    DropdownMenuItem(text = { Text("不绑定世界") }, onClick = { viewModel.selectWorld(null, null); templateExpanded = false })
                    state.encyclopedias.items.forEach { world ->
                        DropdownMenuItem(text = { Text(world.name) }, onClick = { viewModel.selectWorld(null, world.id); templateExpanded = false })
                    }
                    state.templates.items.filter { it.id !in state.worldMappings && it.templateId != "custom" }.forEach { template ->
                        DropdownMenuItem(text = { Text("${template.label} · 旧资料") }, onClick = { viewModel.selectWorld(template.id, null); templateExpanded = false })
                    }
                }
            }
            StoryOptionLoadStatus(state.templates, "正在加载旧资料…", "", viewModel::retryTemplates)
            StoryOptionLoadStatus(state.encyclopedias, "正在加载世界…", "暂无世界，可直接开始创作", viewModel::retryEncyclopedias)

            val selectedTemplate = state.templates.items.firstOrNull { it.id == state.selectedTemplateId }
            val selectedEncyclopedia = state.encyclopedias.items.firstOrNull { it.id == state.selectedEncyclopediaId }
            if (selectedTemplate != null || selectedEncyclopedia != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("本次使用的设定", style = MaterialTheme.typography.titleSmall)
                        selectedTemplate?.let { template ->
                            StorySettingPreview("玩法 · ${template.label.ifBlank { template.templateId }}",
                                listOf(template.summary, template.worldPrompt).filter(String::isNotBlank).distinct().joinToString("\n"))
                        }
                        selectedEncyclopedia?.let { encyclopedia ->
                            StorySettingPreview("百科 · ${encyclopedia.name}",
                                listOf(encyclopedia.description, encyclopedia.worldPrompt).filter(String::isNotBlank).distinct().joinToString("\n"))
                        }
                    }
                }
            }

            val availableCharacters = state.characters.items.filter { character ->
                state.selectedEncyclopediaId == null || character.boundEncyclopediaId <= 0L || character.boundEncyclopediaId == state.selectedEncyclopediaId
            }
            ExposedDropdownMenuBox(
                expanded = charactersExpanded,
                onExpandedChange = { if (!isBusy) charactersExpanded = it },
            ) {
                OutlinedTextField(
                    value = if (state.selectedCharacterIds.isEmpty()) "不指定角色" else "已选择 ${state.selectedCharacterIds.size} 个角色",
                    onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("参与角色（可选）") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(charactersExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    enabled = !isBusy,
                )
                ExposedDropdownMenu(expanded = charactersExpanded, onDismissRequest = { charactersExpanded = false }) {
                    if (availableCharacters.isEmpty()) {
                        val hint = when {
                            state.characters.isLoading -> "正在加载角色…"
                            state.characters.error != null -> "角色加载失败，请在下方重试"
                            else -> "当前没有可选角色"
                        }
                        DropdownMenuItem(text = { Text(hint) }, onClick = { charactersExpanded = false })
                    }
                    availableCharacters.forEach { character ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = character.id in state.selectedCharacterIds, onCheckedChange = null)
                                    Text(character.name.ifBlank { "未命名角色" })
                                }
                            },
                            onClick = { viewModel.toggleCharacter(character.id) },
                        )
                    }
                }
            }
            StoryOptionLoadStatus(
                state = state.characters,
                loadingText = "正在加载角色…",
                emptyText = "暂无已绑定角色，可先不指定角色推演",
                onRetry = viewModel::retryCharacters,
            )

            Text("首次连续生成", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { count ->
                    FilterChip(
                        selected = state.chapterCount == count,
                        onClick = { viewModel.updateChapterCount(count) },
                        enabled = !isBusy,
                        label = { Text("$count 章") },
                    )
                }
            }
            Text(
                "生成后直接进入会话；之后在输入框写下一段走向即可继续，也可以使用“生成旁白”连续续写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            }
            Spacer(Modifier.height(24.dp))
        }
    }

    state.error?.takeIf { state.generationModel == null && !state.hasPendingStory && state.savedSessionId == null }?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("操作未完成") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } },
        )
    }

    if (showStopAndLeaveDialog) {
        AlertDialog(
            onDismissRequest = { dismissStopDialog() },
            title = { Text(if (state.hasPendingStory) "放弃尚未保存的正文？" else "停止生成并离开？") },
            text = { Text(if (state.hasPendingStory) "放弃后将清除这篇待保存正文。可以先返回保存或复制全文。" else "当前小说还在生成。停止后不会保存这次未完成的结果，你填写的故事设定会继续保留。") },
            confirmButton = {
                TextButton(onClick = {
                    if (state.isSaving) {
                        dismissStopDialog()
                        showSavingDialog = true
                        return@TextButton
                    }
                    val action = pendingNavigation
                    dismissStopDialog()
                    scope.launch {
                        if (if (state.hasPendingStory) viewModel.discardPendingStory() else viewModel.stopGeneration()) action?.invoke()
                    }
                }) { Text(if (state.hasPendingStory) "放弃正文" else "停止并离开") }
            },
            dismissButton = {
                TextButton(onClick = { dismissStopDialog() }) { Text(if (state.hasPendingStory) "留在此页" else "继续生成") }
            },
        )
    }

    if (showDiscardUnreadableDialog) {
        AlertDialog(onDismissRequest = { showDiscardUnreadableDialog = false },
            title = { Text("清除无法读取的草稿？") },
            text = { Text("清除后无法继续恢复这篇草稿。可以先返回复制恢复数据。") },
            confirmButton = { TextButton(enabled = !state.isSaving, onClick = { scope.launch {
                if (viewModel.discardUnreadableDraft()) showDiscardUnreadableDialog = false
            } }) { Text("清除草稿") } },
            dismissButton = { TextButton(onClick = { showDiscardUnreadableDialog = false }) { Text("保留草稿") } })
    }

    if (showClearInputDialog) {
        AlertDialog(onDismissRequest = { showClearInputDialog = false },
            title = { Text("清空创作设定？") },
            text = { Text("背景、走向、风格及本次选择的世界和角色会从草稿中移除。已保存的会话不受影响。") },
            confirmButton = { TextButton(enabled = !state.isSaving, onClick = { scope.launch {
                if (viewModel.clearInputDraft()) showClearInputDialog = false
            } }) { Text("清空草稿") } },
            dismissButton = { TextButton(onClick = { showClearInputDialog = false }) { Text("继续编辑") } })
    }

    if (showSavingDialog) {
        AlertDialog(
            onDismissRequest = { showSavingDialog = false },
            title = { Text("正在保存故事") },
            text = { Text("生成已经完成，正在写入本地会话。保存结束后会自动进入故事，请稍候。") },
            confirmButton = {
                TextButton(onClick = { showSavingDialog = false }) { Text("继续等待") }
            },
        )
    }
}

@Composable
private fun StoryOptionLoadStatus(
    state: StoryOptionLoadState<*>,
    loadingText: String,
    emptyText: String,
    onRetry: () -> Unit,
) {
    when {
        state.isLoading -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
            Text(loadingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error != null -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                    Text("重试")
                }
            }
        }
        state.items.isEmpty() -> Text(
            emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

}

@Composable
private fun StorySettingPreview(title: String, content: String) {
    var expanded by remember(title, content) { mutableStateOf(false) }
    var hasOverflow by remember(title, content) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(content.ifBlank { "尚未填写背景设定" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) hasOverflow = it.hasVisualOverflow },
        )
        if (expanded || hasOverflow) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "展开设定")
        }
    }
}
