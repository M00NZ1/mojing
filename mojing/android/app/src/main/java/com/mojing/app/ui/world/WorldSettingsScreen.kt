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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldSettingsScreen(
    worldId: Long,
    onBack: () -> Unit,
    viewModel: WorldSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(worldId) { viewModel.load(worldId) }
    BackHandler { if (!state.saving) { if (state.dirty) showDiscard = true else onBack() } }
    Scaffold(topBar = {
        TopAppBar(title = { Text(state.world?.name?.ifBlank { "世界设置" } ?: "世界设置") }, navigationIcon = {
            IconButton(onClick = { if (!state.saving) { if (state.dirty) showDiscard = true else onBack() } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = viewModel::save, enabled = state.dirty && !state.saving && state.name.isNotBlank()) { Text(if (state.saving) "保存中…" else "保存") }
        })
    }) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(state.error!!, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = viewModel::retry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重试") } }
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(state.name, viewModel::updateName, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("世界名称") }, singleLine = true, isError = state.name.isBlank())
                OutlinedTextField(state.description, viewModel::updateDescription, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("简介") }, minLines = 2, maxLines = 4)
                OutlinedTextField(state.worldPrompt, viewModel::updateWorldPrompt, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("世界提示词") }, minLines = 5, maxLines = 12)
                OutlinedTextField(state.gameplayMode, viewModel::updateGameplayMode, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("玩法模式") }, singleLine = true)
                OutlinedTextField(state.antiCheatPrompt, viewModel::updateAntiCheatPrompt, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("防越界规则") }, minLines = 4, maxLines = 10)
                state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.saved) Text("已保存", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("放弃未保存修改？") }, text = { Text("当前世界设置尚未保存。") }, confirmButton = { TextButton(onClick = onBack) { Text("放弃") } }, dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("继续编辑") } })
}
