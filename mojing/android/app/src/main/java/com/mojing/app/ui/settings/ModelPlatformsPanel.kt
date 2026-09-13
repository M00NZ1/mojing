package com.mojing.app.ui.settings

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.ModelPlatformCodec
import com.mojing.app.data.repository.BillingPreferences
import com.mojing.app.ui.common.ApiProviderPresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ModelPlatformsPanel(viewModel: SettingsViewModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val activeId by viewModel.activePlatformId.collectAsStateWithLifecycle()
    val initialPlatforms = remember { runCatching { viewModel.modelPlatforms() } }
    var platforms by remember { mutableStateOf(initialPlatforms.getOrDefault(emptyList())) }
    var draft by remember { mutableStateOf<ModelPlatform?>(null) }
    var originalDraft by remember { mutableStateOf<ModelPlatform?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var modelsText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var discoveryNotice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    fun requestClose() {
        if (busy || fetching) return
        val original = originalDraft
        val current = draft
        if (original != null && current != null && ModelPlatformCodec.hasDraftChanges(original, current, modelsText)) {
            confirmDiscard = true
        } else draft = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (initialPlatforms.isFailure) {
            Text("平台配置暂时无法读取，原数据已保留。请恢复可用配置后重试。", color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("我的平台", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                draft = ModelPlatform(UUID.randomUUID().toString(), "", "", "", emptyList())
                originalDraft = draft; confirmDiscard = false
                modelsText = ""; error = null; discoveryNotice = null
            }) { Text("添加平台") }
        }
        Text("每个平台独立保存 Key。保存后作为默认线路；聊天中可随时选择已配置的模型。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        platforms.forEach { p ->
            OutlinedCard(onClick = { draft = p; originalDraft = p; confirmDiscard = false; modelsText = p.models.joinToString("\n"); error = null; discoveryNotice = null },
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name + if (p.id == activeId) " · 默认" else "", style = MaterialTheme.typography.titleMedium)
                    Text(p.selectedModel.ifBlank { "待配置模型" }, style = MaterialTheme.typography.bodyMedium)
                    Text("${p.models.size} 个模型 · ${if (p.apiKey.isBlank()) "未填写 Key" else "Key 已保存"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.id != activeId && p.apiKey.isNotBlank() && p.models.isNotEmpty()) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try { viewModel.savePlatform(p); snackbar.showSnackbar("默认平台已切换") }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { snackbar.showSnackbar("切换未保存，请重试") }
                                finally { busy = false }
                            }
                        }) { Text("设为默认平台") }
                    }
                }
            }
        }
    }
    draft?.let { p ->
        AlertDialog(
            onDismissRequest = ::requestClose,
            title = { Text(if (platforms.any { it.id == p.id }) "编辑平台" else "添加平台") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Box {
                            TextButton(onClick = { presetMenu = true }, enabled = !busy && !fetching) { Text("选择服务商预设") }
                            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                                ApiProviderPresets.LINES.filter { it.supportsChat }.forEach { preset ->
                                    DropdownMenuItem(text = { Text(preset.label) }, onClick = {
                                        // A new endpoint always starts without the old provider's credential.
                                        draft = p.copy(id = if (p.baseUrl != preset.baseUrl) UUID.randomUUID().toString() else p.id,
                                            name = preset.label, baseUrl = preset.baseUrl, apiKey = if (p.baseUrl == preset.baseUrl) p.apiKey else "",
                                            models = emptyList(), selectedModel = "")
                                        modelsText = ""; presetMenu = false; error = null; discoveryNotice = null
                                    })
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(p.name, { draft = p.copy(name = it) }, label = { Text("平台名称") }, enabled = !busy && !fetching, singleLine = true) }
                    item { OutlinedTextField(p.baseUrl, { draft = p.copy(baseUrl = it, apiKey = "", models = emptyList(), selectedModel = ""); modelsText = ""; discoveryNotice = null }, label = { Text("服务地址") }, enabled = !busy && !fetching, singleLine = true) }
                    item { OutlinedTextField(p.apiKey, { draft = p.copy(apiKey = it) }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy && !fetching, singleLine = true) }
                    item {
                        OutlinedButton(enabled = !busy && p.apiKey.isNotBlank() && p.baseUrl.isNotBlank(), onClick = {
                            if (fetching) { fetchJob?.cancel(); return@OutlinedButton }
                            fetching = true; error = null; discoveryNotice = null
                            fetchJob = scope.launch {
                                try {
                                    val names = viewModel.fetchPlatformModels(p.baseUrl, p.apiKey)
                                    val existing = ModelPlatformCodec.modelNames(modelsText)
                                    val merged = ModelPlatformCodec.mergeDiscovered(existing, names)
                                    modelsText = merged.joinToString("\n")
                                    discoveryNotice = "已补充 ${merged.size - existing.size} 个模型，共 ${merged.size} 个。"
                                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { error = "获取超时，可重试或手动填写模型名称" }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "未能获取模型。请检查地址与 Key，或在下方手动填写；已有模型已保留。" }
                                finally { fetching = false }
                            }
                        }) { Text(if (fetching) "取消获取" else "通过 Key 获取全部模型") }
                    }
                    discoveryNotice?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
                    item { OutlinedTextField(modelsText, { modelsText = it; discoveryNotice = null }, label = { Text("模型名称") }, supportingText = { Text("每行一个，也可用逗号分隔。默认模型可在下方选择。") }, minLines = 3, maxLines = 8, enabled = !busy && !fetching) }
                    item {
                        val names = ModelPlatformCodec.modelNames(modelsText)
                        Box {
                            OutlinedButton(enabled = names.isNotEmpty() && !busy && !fetching, onClick = { modelMenu = true }) {
                                Text("默认模型：${p.selectedModel.takeIf { it in names } ?: names.firstOrNull().orEmpty()} ▾")
                            }
                            if (modelMenu) {
                                ModelNamePicker(names, p.selectedModel.takeIf { it in names } ?: names.firstOrNull().orEmpty(),
                                    onSelect = { name -> draft = p.copy(selectedModel = name); modelMenu = false },
                                    onDismiss = { modelMenu = false })
                            }
                        }
                    }
                    item {
                        ModelPricingPanel(platform = p)
                    }
                    error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && !fetching, onClick = {
                    val names = ModelPlatformCodec.modelNames(modelsText)
                    val url = p.baseUrl.trim().toHttpUrlOrNull()
                    if (p.name.isBlank() || url == null || p.apiKey.isBlank() || names.isEmpty()) {
                        error = "请填写平台名称、有效地址、Key 和至少一个模型"; return@TextButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            viewModel.savePlatform(p.copy(name = p.name.trim(), baseUrl = p.baseUrl.trim(), apiKey = p.apiKey.trim(), models = names,
                                selectedModel = p.selectedModel.takeIf { it in names } ?: names.first()))
                            platforms = viewModel.modelPlatforms(); draft = null
                            snackbar.showSnackbar("平台已保存，可在聊天中选择模型")
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "保存失败，填写的内容已保留，请重试" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "保存中…" else "保存并设为默认") }
            },
            dismissButton = { TextButton(enabled = !busy && !fetching, onClick = ::requestClose) { Text("取消") } },
        )
    }
    if (confirmDiscard && draft != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃平台修改？") },
            text = { Text("当前填写的配置尚未保存。") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; draft = null }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }
}
