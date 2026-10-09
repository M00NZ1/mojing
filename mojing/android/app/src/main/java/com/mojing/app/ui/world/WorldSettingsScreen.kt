package com.mojing.app.ui.world

import com.mojing.app.ui.common.MoJingTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
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
    onSaved: () -> Unit,
    viewModel: WorldSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDiscard by remember { mutableStateOf(false) }
    var discardStoredDraft by remember { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val editable = !state.saving && !state.draftFlushing && state.recoverableDraft == null
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeOpen = isImeKeyboardOpen()
    val canSave = state.dirty && editable && !state.loading && state.error == null && state.name.isNotBlank()
    fun requestBack() {
        if (state.saving || state.draftFlushing || leaving) return
        hideImeKeyboard(keyboardController, focusManager)
        when {
            state.dirty || state.draftError != null -> viewModel.flushDraft { ok ->
                if (ok && !leaving) {
                    if (state.dirty) showDiscard = true else { leaving = true; onBack() }
                }
            }
            else -> { leaving = true; onBack() }
        }
    }
    LaunchedEffect(worldId) { viewModel.load(worldId) }
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }
    BackHandler {
        if (imeOpen) hideImeKeyboard(keyboardController, focusManager) else requestBack()
    }
    Scaffold(topBar = {
        TopAppBar(
                        expandedHeight = 52.dp,title = { Text("世界设置", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
            IconButton(onClick = ::requestBack, enabled = !state.saving && !state.draftFlushing && !leaving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        }, actions = {
            if (imeOpen) TextButton(onClick = viewModel::save, enabled = canSave) { Text(if (state.saving) "保存中…" else "保存") }
        })
    }, bottomBar = {
        if (!imeOpen && !state.loading && state.error == null) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                com.mojing.app.ui.common.MoJingButton(onClick = viewModel::save, enabled = canSave,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.saving) "正在保存…" else if (state.saved && !state.dirty) "已保存" else "保存世界设置")
                }
            }
        }
    }) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(state.error!!, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = viewModel::retry) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重试") } }
            else -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(padding).consumeWindowInsets(padding).imePadding()) {
                state.saveError?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.draftError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                    if (state.recoverableDraft == null) TextButton(onClick = viewModel::retryDraft, enabled = !state.saving) { Text("重试草稿操作") }
                }
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
                Text("基本资料", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(state.name, viewModel::updateName, Modifier.fillMaxWidth(), enabled = editable, label = { Text("世界名称") }, singleLine = true, isError = state.name.isBlank())
                OutlinedTextField(state.description, viewModel::updateDescription, Modifier.fillMaxWidth(), enabled = editable, label = { Text("简介") }, minLines = 2, maxLines = 4)
                OutlinedTextField(state.gameplayMode, viewModel::updateGameplayMode, Modifier.fillMaxWidth(), enabled = editable, label = { Text("玩法模式") }, singleLine = true)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("世界与叙事", style = MaterialTheme.typography.titleMedium)
                MoJingLongTextField(state.worldPrompt, viewModel::updateWorldPrompt, "世界提示词", "描述世界背景、运行规则与叙事风格", Modifier.fillMaxWidth(), enabled = editable)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("角色与剧情边界", style = MaterialTheme.typography.titleMedium)
                MoJingLongTextField(state.antiCheatPrompt, viewModel::updateAntiCheatPrompt, "防越界规则", "描述角色能力、信息范围与剧情约束", Modifier.fillMaxWidth(), enabled = editable)
                }
            }
        }
    }
    if (showDiscard) AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),onDismissRequest = { if (!leaving) showDiscard = false }, title = { Text("离开世界编辑？") }, text = { Text("修改已保留为本地草稿，下次打开可以恢复。") }, confirmButton = { TextButton(enabled = !leaving, onClick = { leaving = true; onBack() }) { Text("保留草稿并离开") } }, dismissButton = { TextButton(enabled = !leaving, onClick = { showDiscard = false }) { Text("继续编辑") } })
    if (discardStoredDraft) AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),onDismissRequest = { if (!state.draftFlushing) discardStoredDraft = false }, title = { Text("丢弃世界草稿？") }, text = { Text("将移除未保存的修改，已保存的世界资料不变。") }, confirmButton = { TextButton(enabled = !state.draftFlushing, onClick = { viewModel.discardDraft(); discardStoredDraft = false }) { Text(if (state.draftFlushing) "正在丢弃…" else "丢弃") } }, dismissButton = { TextButton(enabled = !state.draftFlushing, onClick = { discardStoredDraft = false }) { Text("取消") } })
}
