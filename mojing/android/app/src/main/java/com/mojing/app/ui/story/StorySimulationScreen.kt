package com.mojing.app.ui.story

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToCreationHub
import kotlinx.coroutines.launch

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
    val isImeOpen = com.mojing.app.ui.common.isImeKeyboardOpen()
    var templateExpanded by remember { mutableStateOf(false) }
    var encyclopediaExpanded by remember { mutableStateOf(false) }
    var charactersExpanded by remember { mutableStateOf(false) }
    var showStopAndLeaveDialog by remember { mutableStateOf(false) }
    var showSavingDialog by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isBusy = state.isGenerating || state.isSaving

    fun dismissStopDialog() {
        showStopAndLeaveDialog = false
        pendingNavigation = null
    }

    fun requestNavigation(action: () -> Unit) {
        when {
            state.isSaving -> showSavingDialog = true
            state.isGenerating -> {
                pendingNavigation = action
                showStopAndLeaveDialog = true
            }
            else -> action()
        }
    }

    LaunchedEffect(state.isGenerating, state.isSaving) {
        if (!state.isGenerating && !state.isSaving) {
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
                title = { Text("小说创作") },
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
            MainAppBottomNavigation(
                navController = navController,
                onNavigateRequest = { action -> requestNavigation(action) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("写下大致故事背景和开篇走向，AI 会结合已选人物直接生成小说正文。完成后自动进入创作会话，可继续输入后续走向或连续续写。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.premise,
                onValueChange = viewModel::updatePremise,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("故事背景与大致设定 *") },
                placeholder = { Text("例如：普通现代都市，只有主角知道自己觉醒了系统，其他人不得知情。再写清主角处境和系统规则。") },
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
                placeholder = { Text("例如：先写觉醒当天，以及系统发布第一个任务") },
                minLines = 2,
                maxLines = 5,
                enabled = !isBusy,
            )
            OutlinedTextField(
                value = state.tone,
                onValueChange = viewModel::updateTone,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("风格与节奏") },
                singleLine = true,
                enabled = !isBusy,
            )

            ExposedDropdownMenuBox(
                expanded = templateExpanded,
                onExpandedChange = { if (!isBusy) templateExpanded = it },
            ) {
                val selected = state.templates.items.firstOrNull { it.id == state.selectedTemplateId }
                OutlinedTextField(
                    value = selected?.label?.ifBlank { selected.templateId } ?: "不绑定世界模板",
                    onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("世界模板（可选）") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(templateExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    enabled = !isBusy,
                )
                ExposedDropdownMenu(expanded = templateExpanded, onDismissRequest = { templateExpanded = false }) {
                    DropdownMenuItem(text = { Text("不绑定") }, onClick = { viewModel.selectTemplate(null); templateExpanded = false })
                    state.templates.items.forEach { template ->
                        DropdownMenuItem(text = { Text(template.label.ifBlank { template.templateId }) }, onClick = { viewModel.selectTemplate(template.id); templateExpanded = false })
                    }
                }
            }
            StoryOptionLoadStatus(
                state = state.templates,
                loadingText = "正在加载世界模板…",
                emptyText = "暂无世界模板，可继续使用不绑定模式",
                onRetry = viewModel::retryTemplates,
            )

            ExposedDropdownMenuBox(
                expanded = encyclopediaExpanded,
                onExpandedChange = { if (!isBusy) encyclopediaExpanded = it },
            ) {
                val selected = state.encyclopedias.items.firstOrNull { it.id == state.selectedEncyclopediaId }
                OutlinedTextField(
                    value = selected?.name?.ifBlank { "百科 ${selected.id}" } ?: "不绑定百科",
                    onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("百科（可选）") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(encyclopediaExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    enabled = !isBusy,
                )
                ExposedDropdownMenu(expanded = encyclopediaExpanded, onDismissRequest = { encyclopediaExpanded = false }) {
                    DropdownMenuItem(text = { Text("不绑定") }, onClick = { viewModel.selectEncyclopedia(null); encyclopediaExpanded = false })
                    state.encyclopedias.items.forEach { encyclopedia ->
                        DropdownMenuItem(text = { Text(encyclopedia.name.ifBlank { "百科 ${encyclopedia.id}" }) }, onClick = { viewModel.selectEncyclopedia(encyclopedia.id); encyclopediaExpanded = false })
                    }
                }
            }
            StoryOptionLoadStatus(
                state = state.encyclopedias,
                loadingText = "正在加载百科…",
                emptyText = "暂无百科，可继续使用不绑定模式",
                onRetry = viewModel::retryEncyclopedias,
            )

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
                    OutlinedButton(
                        onClick = { viewModel.updateChapterCount(count) },
                        enabled = !isBusy && state.chapterCount != count,
                    ) {
                        Text("$count 章")
                    }
                }
            }
            Text(
                "生成后直接进入会话；之后在输入框写下一段走向即可继续，也可以使用“生成旁白”连续续写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                state.isGenerating -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp).height(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在创作 ${state.chapterCount} 章…")
                    }
                    OutlinedButton(onClick = {
                        if (viewModel.stopGeneration()) {
                            scope.launch { snackbarHostState.showSnackbar("已停止生成，填写的内容仍保留") }
                        }
                    }) {
                        Text("停止")
                    }
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
                else -> Button(
                    onClick = { focusManager.clearFocus(); viewModel.createStory(onOpenSession) },
                    enabled = state.premise.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("生成小说并开始创作")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    state.error?.let { message ->
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
            title = { Text("停止生成并离开？") },
            text = { Text("当前小说还在生成。停止后不会保存这次未完成的结果，你填写的故事设定会继续保留。") },
            confirmButton = {
                TextButton(onClick = {
                    if (state.isSaving) {
                        dismissStopDialog()
                        showSavingDialog = true
                        return@TextButton
                    }
                    val action = pendingNavigation
                    dismissStopDialog()
                    if (viewModel.stopGeneration()) action?.invoke()
                }) { Text("停止并离开") }
            },
            dismissButton = {
                TextButton(onClick = { dismissStopDialog() }) { Text("继续生成") }
            },
        )
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
