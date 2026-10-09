package com.mojing.app.ui.settings

import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Delete
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
    var capacityDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showCapacities by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var discoveryNotice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ModelPlatform?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    fun requestClose() {
        if (busy || fetching) return
        val original = originalDraft
        val current = draft
        val capacityChanges = capacityDrafts.filter { (model, value) ->
            model in ModelPlatformCodec.modelNames(modelsText) && value.isNotBlank()
        } != original?.modelContextWindows?.mapValues { it.value.toString() }.orEmpty()
        if (original != null && current != null && (ModelPlatformCodec.hasDraftChanges(original, current, modelsText) || capacityChanges)) {
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
                capacityDrafts = emptyMap(); showCapacities = false
            }) { Text("添加平台") }
        }
        Column(Modifier.fillMaxWidth().testTag("settings-platform-tabs"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            platforms.forEach { platform ->
                val selected = platform.id == selectedPlatform?.id
                Surface(onClick = { selectedPlatformId = platform.id }, shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        com.mojing.app.ui.common.ProviderLogo(platform.baseUrl)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(platform.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${platform.models.size} 个模型 · ${if (platform.apiKey.isBlank()) "待配置" else "已配置"}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(enabled = !busy, onClick = {
                            draft = platform; originalDraft = platform; confirmDiscard = false
                            modelsText = platform.models.joinToString("\n"); error = null; discoveryNotice = null
                            capacityDrafts = platform.modelContextWindows.mapValues { it.value.toString() }; showCapacities = false
                        }) { Text("配置") }
                        IconButton(enabled = !busy, onClick = { deleteTarget = platform; deleteError = null }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除平台${platform.name}")
                        }
                    }
                }
            }
        }
        if (platforms.isEmpty() && platformLoad.isSuccess) {
            Text("添加平台后，在这里管理连接、模型和价格。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        selectedPlatform?.let { p ->
            Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("默认模型", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p.selectedModel.ifBlank { "待配置模型" }, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(enabled = !busy, onClick = {
                            draft = p; originalDraft = p; confirmDiscard = false
                            modelsText = p.models.joinToString("\n"); error = null; discoveryNotice = null
                            capacityDrafts = p.modelContextWindows.mapValues { it.value.toString() }; showCapacities = false
                        }) { Text("编辑") }
                    }
                    if (p.id != activeId && p.apiKey.isNotBlank() && p.models.isNotEmpty()) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try { viewModel.savePlatform(p, makeDefault = true); snackbar.showSnackbar("默认平台已切换") }
                                catch (e: CancellationException) { throw e }
                                catch (failure: Exception) {
                                    snackbar.showSnackbar(if (failure.message == "平台保存失败，且原配置恢复失败，请重启后核对") failure.message!! else "切换未保存，请重试")
                                }
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
                                com.mojing.app.ui.common.ProviderLogo(p.baseUrl)
                                Spacer(Modifier.width(8.dp))
                                Text("服务商 · ${ApiProviderPresets.labelForBaseUrl(p.baseUrl)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Outlined.ExpandMore, "选择服务商预设")
                            }
                            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                                ApiProviderPresets.LINES.filter { it.supportsChat }.forEach { preset ->
                                    DropdownMenuItem(text = { Text(preset.label) },
                                        leadingIcon = { com.mojing.app.ui.common.ProviderLogo(preset.baseUrl) }, onClick = {
                                        // A new endpoint always starts without the old provider's credential.
                                        draft = p.copy(id = if (p.baseUrl != preset.baseUrl) UUID.randomUUID().toString() else p.id,
                                            name = preset.label, baseUrl = preset.baseUrl, apiKey = if (p.baseUrl == preset.baseUrl) p.apiKey else "",
                                            models = emptyList(), selectedModel = "", modelContextWindows = emptyMap())
                                        modelsText = ""; presetMenu = false; error = null; discoveryNotice = null
                                        capacityDrafts = emptyMap(); showCapacities = false
                                    })
                                }
                            }
                        }
                    }
                    item { Text("连接配置", style = MaterialTheme.typography.titleMedium) }
                    item { OutlinedTextField(p.name, { draft = p.copy(name = it) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("平台名称") }, placeholder = { Text("请输入平台名") }, enabled = !busy && !fetching, singleLine = true) }
                    item { OutlinedTextField(p.baseUrl, { draft = p.copy(baseUrl = it, apiKey = "", models = emptyList(), selectedModel = "", modelContextWindows = emptyMap()); modelsText = ""; capacityDrafts = emptyMap(); discoveryNotice = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("服务地址") }, placeholder = { Text("https://api.example.com/v1") }, enabled = !busy && !fetching, singleLine = true) }
                    item { OutlinedTextField(p.apiKey, { draft = p.copy(apiKey = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, placeholder = { Text("请输入 API Key") },
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
                            Text(if (fetching) "取消获取" else "获取模型列表")
                        }
                    }
                    discoveryNotice?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
                    item { OutlinedTextField(modelsText, { modelsText = it; discoveryNotice = null }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称 · ${ModelPlatformCodec.modelNames(modelsText).size}") }, placeholder = { Text("每行输入一个模型名称") }, minLines = 3, maxLines = 5, enabled = !busy && !fetching) }
                    item {
                        TextButton(onClick = { showCapacities = !showCapacities }, enabled = !busy && !fetching) {
                            Text(if (showCapacities) "收起上下文容量" else "设置上下文容量（可选）")
                        }
                        if (showCapacities) Text("按平台说明填写输入加输出的总容量（Token），仅用于聊天中选定的此平台模型。留空或跟随角色与世界连接时仍按软窗口发送；已设置时发送前估算检查，放不下完整本轮内容会提示调整。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showCapacities) items(ModelPlatformCodec.modelNames(modelsText), key = { "capacity:$it" }) { model ->
                        val raw = capacityDrafts[model].orEmpty()
                        OutlinedTextField(raw, { capacityDrafts = capacityDrafts + (model to it); error = null },
                            modifier = Modifier.fillMaxWidth().testTag("context-capacity-$model"),
                            label = { Text("$model · 上下文总容量") }, placeholder = { Text("未设置") },
                            isError = runCatching { ModelPlatformCodec.parseContextWindow(raw) }.isFailure,
                            enabled = !busy && !fetching, singleLine = true)
                    }
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
                    val capacities = try { ModelPlatformCodec.contextWindows(names, capacityDrafts) }
                    catch (invalid: IllegalArgumentException) { error = invalid.message; return@Button }
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            val isExisting = platforms.any { it.id == p.id }
                            val normalized = p.copy(name = p.name.trim(), baseUrl = p.baseUrl.trim(), apiKey = p.apiKey.trim(), models = names,
                                selectedModel = p.selectedModel.takeIf { it in names } ?: names.first(), modelContextWindows = capacities)
                            // Editing an existing non-default platform must not silently switch chat credentials.
                            // New platforms retain the established creation flow and become the default.
                            viewModel.savePlatform(normalized, makeDefault = !isExisting)
                            platforms = viewModel.modelPlatforms(); selectedPlatformId = p.id; draft = null
                            snackbar.showSnackbar(if (isExisting) "平台已保存，可在聊天中选择模型" else "平台已保存并设为默认")
                        } catch (e: CancellationException) { throw e }
                        catch (failure: Exception) {
                            error = if (failure.message == "平台保存失败，且原配置恢复失败，请重启后核对") failure.message
                                else "保存失败，填写的内容已保留，请重试"
                        }
                        finally { busy = false }
                    }
            }) { Text(if (busy) "保存中…" else if (platforms.any { it.id == p.id }) "保存平台" else "保存并设为默认") }
            },
            dismissButton = { TextButton(enabled = !busy && !fetching, onClick = ::requestClose) { Text("取消") } },
        )
    }
    if (confirmDiscard && draft != null) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
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
    deleteTarget?.let { target ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除平台？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("删除“${target.name}”及其模型配置。使用此平台的对话需重新选择模型。")
                    if (target.id == activeId) {
                        Text("这是默认平台，删除后需设置新的默认平台。",
                            color = MaterialTheme.colorScheme.error)
                    }
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            platforms = viewModel.deletePlatform(target.id)
                            if (platforms.none { it.id == selectedPlatformId }) {
                                selectedPlatformId = platforms.firstOrNull { it.id == activeId }?.id
                                    ?: platforms.firstOrNull()?.id.orEmpty()
                            }
                            deleteTarget = null
                            scope.launch { snackbar.showSnackbar("平台已删除") }
                        } catch (e: CancellationException) { throw e }
                        catch (failure: Exception) {
                            deleteError = if (failure.message == "平台删除失败，且原配置恢复失败，请重启后核对") {
                                failure.message
                            } else "删除未保存，请重试"
                        }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "删除中…" else "删除") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleteTarget = null; deleteError = null }) { Text("取消") } },
        )
    }
}
