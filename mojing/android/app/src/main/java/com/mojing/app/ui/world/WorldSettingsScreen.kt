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
                OutlinedTextField(state.name, viewModel::updateName, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("世界名称") }, singleLine = true, isError = state.name.isBlank())
                OutlinedTextField(state.description, viewModel::updateDescription, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("简介") }, minLines = 2, maxLines = 4)
                MoJingLongTextField(state.worldPrompt, viewModel::updateWorldPrompt, "世界提示词", "描述世界背景、运行规则与叙事风格", Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(state.gameplayMode, viewModel::updateGameplayMode, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("玩法模式") }, singleLine = true)
                MoJingLongTextField(state.antiCheatPrompt, viewModel::updateAntiCheatPrompt, "防越界规则", "描述角色能力、信息范围与剧情约束", Modifier.fillMaxWidth(), enabled = !state.saving)
                state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.saved) Text("已保存", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("放弃未保存修改？") }, text = { Text("当前世界设置尚未保存。") }, confirmButton = { TextButton(onClick = onBack) { Text("放弃") } }, dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("继续编辑") } })
}
