package com.mojing.app.ui.settings

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.ModelPlatformCodec
import com.mojing.app.data.repository.BillingPreferences
import com.mojing.app.ui.common.ApiProviderPresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ModelPlatformsPanel(viewModel: SettingsViewModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val activeId by viewModel.activePlatformId.collectAsStateWithLifecycle()
    var platformLoad by remember { mutableStateOf(runCatching { viewModel.modelPlatforms() }) }
    var platforms by remember { mutableStateOf(platformLoad.getOrDefault(emptyList())) }
    var selectedPlatformId by rememberSaveable { mutableStateOf(activeId) }
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
    val selectedPlatform = platforms.firstOrNull { it.id == selectedPlatformId } ?: platforms.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (platformLoad.isFailure) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("平台配置暂时无法读取，原数据已保留。重试后再编辑。",
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val refreshed = withContext(Dispatchers.IO) { viewModel.modelPlatforms() }
                            platforms = refreshed
                            platformLoad = Result.success(refreshed)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { platformLoad = Result.failure(failure) }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "读取中…" else "重试读取") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("我的平台", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy && platformLoad.isSuccess, onClick = {
                draft = ModelPlatform(UUID.randomUUID().toString(), "", "", "", emptyList())
                originalDraft = draft; confirmDiscard = false
                modelsText = ""; error = null; discoveryNotice = null
            }) { Text("添加平台") }
        }
        com.mojing.app.ui.common.PlatformTabs(platforms, selectedPlatform?.id,
            onSelect = { selectedPlatformId = it }, modifier = Modifier.testTag("settings-platform-tabs"))
        if (platforms.isEmpty() && platformLoad.isSuccess) {
            Text("添加平台后，在这里管理连接、模型和价格。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        selectedPlatform?.let { p ->
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (p.id == activeId) "默认平台" else "已保存的平台",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(enabled = !busy, onClick = {
                            draft = p; originalDraft = p; confirmDiscard = false
                            modelsText = p.models.joinToString("\n"); error = null; discoveryNotice = null
                        }) { Text("编辑") }
                    }
                    Text(p.baseUrl, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("默认模型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.selectedModel.ifBlank { "待配置模型" }, style = MaterialTheme.typography.bodyMedium)
                    Text("${p.models.size} 个模型 · ${if (p.apiKey.isBlank()) "未填写 Key" else "Key 已保存"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.id != activeId && p.apiKey.isNotBlank() && p.models.isNotEmpty()) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try { viewModel.savePlatform(p, makeDefault = true); snackbar.showSnackbar("默认平台已切换") }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { snackbar.showSnackbar("切换未保存，请重试") }
                                finally { busy = false }
                            }
                        }) { Text("设为默认平台") }
                    }
                }
            }
            key(p.id) { ModelPricingPanel(platform = p) }
        }
    }
    draft?.let { p ->
        var showKey by remember(p.id) { mutableStateOf(false) }
        PlatformEditorDialog(
            onDismissRequest = ::requestClose,
            error = error,
            title = { Text(if (platforms.any { it.id == p.id }) "编辑平台" else "添加平台") },
            text = {
                LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Box {
                            OutlinedButton(onClick = { presetMenu = true }, enabled = !busy && !fetching, modifier = Modifier.fillMaxWidth()) {
                                Text("服务商 · ${ApiProviderPresets.labelForBaseUrl(p.baseUrl)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Outlined.ExpandMore, "选择服务商预设")
                            }
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
                    item { Text("连接配置", style = MaterialTheme.typography.titleMedium) }
                    item { OutlinedTextField(p.name, { draft = p.copy(name = it) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("平台名称") }, enabled = !busy && !fetching, singleLine = true) }
                    item { OutlinedTextField(p.baseUrl, { draft = p.copy(baseUrl = it, apiKey = "", models = emptyList(), selectedModel = ""); modelsText = ""; discoveryNotice = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("服务地址") }, enabled = !busy && !fetching, singleLine = true) }
                    item { OutlinedTextField(p.apiKey, { draft = p.copy(apiKey = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "隐藏 Key" else "显示 Key") } },
                        enabled = !busy && !fetching, singleLine = true) }
                    item { HorizontalDivider(); Spacer(Modifier.height(16.dp)); Text("模型目录", style = MaterialTheme.typography.titleMedium) }
                    item {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && p.apiKey.isNotBlank() && p.baseUrl.isNotBlank(), onClick = {
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
                        }) {
                            if (fetching) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(if (fetching) "取消获取" else "通过 Key 获取全部模型")
                        }
                    }
                    discoveryNotice?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
                    item { OutlinedTextField(modelsText, { modelsText = it; discoveryNotice = null }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称 · ${ModelPlatformCodec.modelNames(modelsText).size}") }, supportingText = { Text("每行一个，也可用逗号分隔。支持手动填写。") }, minLines = 3, maxLines = 5, enabled = !busy && !fetching) }
                    item {
                        val names = ModelPlatformCodec.modelNames(modelsText)
                        Box {
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = names.isNotEmpty() && !busy && !fetching, onClick = { modelMenu = true }) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("默认模型", style = MaterialTheme.typography.labelSmall)
                                    Text(p.selectedModel.takeIf { it in names } ?: names.firstOrNull() ?: "先添加模型", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Outlined.ExpandMore, "选择默认模型")
                            }
                            if (modelMenu) {
                                ModelNamePicker(names, p.selectedModel.takeIf { it in names } ?: names.firstOrNull().orEmpty(),
                                    onSelect = { name -> draft = p.copy(selectedModel = name); modelMenu = false },
                                    onDismiss = { modelMenu = false })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !busy && !fetching, onClick = {
                    val names = ModelPlatformCodec.modelNames(modelsText)
                    val url = p.baseUrl.trim().toHttpUrlOrNull()
                    if (p.name.isBlank() || url == null || p.apiKey.isBlank() || names.isEmpty()) {
                        error = "请填写平台名称、有效地址、Key 和至少一个模型"; return@Button
                    }
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            val isExisting = platforms.any { it.id == p.id }
                            val normalized = p.copy(name = p.name.trim(), baseUrl = p.baseUrl.trim(), apiKey = p.apiKey.trim(), models = names,
                                selectedModel = p.selectedModel.takeIf { it in names } ?: names.first())
                            // Editing an existing non-default platform must not silently switch chat credentials.
                            // New platforms retain the established creation flow and become the default.
                            viewModel.savePlatform(normalized, makeDefault = !isExisting)
                            platforms = viewModel.modelPlatforms(); selectedPlatformId = p.id; draft = null
                            snackbar.showSnackbar(if (isExisting) "平台已保存，可在聊天中选择模型" else "平台已保存并设为默认")
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "保存失败，填写的内容已保留，请重试" }
                        finally { busy = false }
                    }
            }) { Text(if (busy) "保存中…" else if (platforms.any { it.id == p.id }) "保存平台" else "保存并设为默认") }
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
