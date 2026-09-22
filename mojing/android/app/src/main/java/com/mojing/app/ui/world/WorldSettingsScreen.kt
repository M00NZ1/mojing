package com.mojing.app.ui.world

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.hideImeKeyboard
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.common.MoJingLongTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldSettingsScreen(
    worldId: Long,
    onBack: () -> Unit,
    viewModel: WorldSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDiscard by remember { mutableStateOf(false) }
    var discardStoredDraft by remember { mutableStateOf(false) }
    val editable = !state.saving && state.recoverableDraft == null
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeOpen = isImeKeyboardOpen()
    fun requestBack() {
        if (state.saving) return
        hideImeKeyboard(keyboardController, focusManager)
        if (state.dirty) showDiscard = true else onBack()
    }
    LaunchedEffect(worldId) { viewModel.load(worldId) }
    BackHandler {
        if (imeOpen) hideImeKeyboard(keyboardController, focusManager) else requestBack()
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(state.world?.name?.ifBlank { "世界设置" } ?: "世界设置") }, navigationIcon = {
            IconButton(onClick = ::requestBack, enabled = !state.saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = viewModel::save, enabled = state.dirty && !state.saving && state.name.isNotBlank()) { Text(if (state.saving) "保存中…" else "保存") }
        })
    }) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(state.error!!, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = viewModel::retry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重试") } }
            else -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.recoverableDraft != null) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("发现未保存的世界草稿", style = MaterialTheme.typography.titleMedium)
                            Text("恢复后可继续编辑，点击保存后更新世界资料。", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = viewModel::restoreDraft) { Text("恢复草稿") }
                                TextButton(onClick = { discardStoredDraft = true }) { Text("丢弃草稿") }
                            }
                        }
                    }
                }
                OutlinedTextField(state.name, viewModel::updateName, Modifier.fillMaxWidth(), enabled = editable, label = { Text("世界名称") }, singleLine = true, isError = state.name.isBlank())
                OutlinedTextField(state.description, viewModel::updateDescription, Modifier.fillMaxWidth(), enabled = editable, label = { Text("简介") }, minLines = 2, maxLines = 4)
                MoJingLongTextField(state.worldPrompt, viewModel::updateWorldPrompt, "世界提示词", "描述世界背景、运行规则与叙事风格", Modifier.fillMaxWidth(), enabled = editable)
                OutlinedTextField(state.gameplayMode, viewModel::updateGameplayMode, Modifier.fillMaxWidth(), enabled = editable, label = { Text("玩法模式") }, singleLine = true)
                MoJingLongTextField(state.antiCheatPrompt, viewModel::updateAntiCheatPrompt, "防越界规则", "描述角色能力、信息范围与剧情约束", Modifier.fillMaxWidth(), enabled = editable)
                state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.saved) Text("已保存", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("离开世界编辑？") }, text = { Text("修改已保留为本地草稿，下次打开可以恢复。") }, confirmButton = { TextButton(onClick = onBack) { Text("保留草稿并离开") } }, dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("继续编辑") } })
    if (discardStoredDraft) AlertDialog(onDismissRequest = { discardStoredDraft = false }, title = { Text("丢弃世界草稿？") }, text = { Text("将移除未保存的修改，已保存的世界资料不变。") }, confirmButton = { TextButton(onClick = { viewModel.discardDraft(); discardStoredDraft = false }) { Text("丢弃") } }, dismissButton = { TextButton(onClick = { discardStoredDraft = false }) { Text("取消") } })
}
