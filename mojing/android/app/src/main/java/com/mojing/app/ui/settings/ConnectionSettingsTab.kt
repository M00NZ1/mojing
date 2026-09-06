package com.mojing.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.ui.common.ApiProviderPresets
import com.mojing.app.ui.common.ApiVendorModelHint
import com.mojing.app.ui.common.ApiVendorPresetRow
import com.mojing.app.ui.common.CollapsiblePresetUrlModelBlock
import com.mojing.app.ui.common.LlmKeySetupHintCard
import com.mojing.app.util.LogExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ConnectionChannel(val label: String) {
    TEXT("对话"),
    IMAGE("配图"),
    VOICE("朗读"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsTab(
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedChannelIndex by remember { mutableIntStateOf(0) }
    var showManualChat by remember { mutableStateOf(false) }
    var showManualImage by remember { mutableStateOf(false) }
    var showManualVoice by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val imageApiKey by viewModel.imageApiKey.collectAsStateWithLifecycle()
    val imageBaseUrl by viewModel.imageBaseUrl.collectAsStateWithLifecycle()
    val imageModel by viewModel.imageModel.collectAsStateWithLifecycle()
    val voiceApiKey by viewModel.voiceApiKey.collectAsStateWithLifecycle()
    val voiceBaseUrl by viewModel.voiceBaseUrl.collectAsStateWithLifecycle()
    val voiceModel by viewModel.voiceModel.collectAsStateWithLifecycle()
    val voiceSpeechVoice by viewModel.voiceSpeechVoice.collectAsStateWithLifecycle()
    val voicePresetPrefixModel by viewModel.voicePresetPrefixModel.collectAsStateWithLifecycle()
    val probeBusy by viewModel.probeBusyChannel.collectAsStateWithLifecycle()
    val probeHint by viewModel.probeStreamHint.collectAsStateWithLifecycle()
    val probeLines by viewModel.probeStreamLines.collectAsStateWithLifecycle()

    LaunchedEffect(baseUrl) {
        if (ApiProviderPresets.isExactSinglePresetBaseUrl(baseUrl)) showManualChat = false
    }
    LaunchedEffect(imageBaseUrl) {
        if (ApiProviderPresets.isExactSinglePresetBaseUrl(imageBaseUrl)) showManualImage = false
    }
    LaunchedEffect(voiceBaseUrl) {
        if (ApiProviderPresets.isExactSinglePresetBaseUrl(voiceBaseUrl)) showManualVoice = false
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        LogExportManager.exportZip(
                            output,
                            LogExportManager.resolveLogDir(context),
                            LogExportManager.appInfoJson(context),
                        )
                    } ?: error("无法打开保存位置")
                }
                "日志已导出"
            }.getOrElse { "导出失败，请换一个保存位置后重试" }
            snackbarHostState.showSnackbar(message)
        }
    }

    val selectedChannel = ConnectionChannel.entries[selectedChannelIndex]
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("模型与联网", style = MaterialTheme.typography.titleLarge)
        Text(
            "先配置日常使用的对话线路；配图和朗读可按需单独覆盖，不填专用 Key 时会复用对话 Key。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ConnectionChannel.entries.forEachIndexed { index, channel ->
                SegmentedButton(
                    selected = selectedChannelIndex == index,
                    onClick = { selectedChannelIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index, ConnectionChannel.entries.size),
                ) { Text(channel.label) }
            }
        }

        when (selectedChannel) {
            ConnectionChannel.TEXT -> {
                ModelPlatformsPanel(viewModel, snackbarHostState)
                ProbeProgress("text", probeBusy, probeHint, probeLines)
                OutlinedButton(
                    onClick = { viewModel.runProbeText { scope.launch { snackbarHostState.showSnackbar(it) } } },
                    enabled = probeBusy == null && apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("测试默认对话线路") }
            }

            ConnectionChannel.IMAGE -> ConnectionCard("AI 配图", "仅在需要生成封面或剧情配图时配置") {
                ApiVendorPresetRow(
                    sectionLabel = "1. 选择服务商",
                    currentBaseUrl = imageBaseUrl,
                    currentModel = imageModel,
                    onBaseUrlChange = viewModel::updateImageBaseUrl,
                    onModelChange = viewModel::updateImageModel,
                    modelHint = ApiVendorModelHint.IMAGE,
                )
                SecretKeyField("2. 配图 API Key（留空复用对话 Key）", imageApiKey, viewModel::updateImageApiKey)
                CollapsiblePresetUrlModelBlock(
                    collapsedPreset = ApiProviderPresets.isExactSinglePresetBaseUrl(imageBaseUrl),
                    showManualFields = showManualImage,
                    onExpandManual = { showManualImage = true },
                    onCollapseManual = { showManualImage = false },
                    baseUrl = imageBaseUrl,
                    model = imageModel,
                    onBaseChange = viewModel::updateImageBaseUrl,
                    onModelChange = viewModel::updateImageModel,
                    baseLabel = "配图服务地址",
                    basePlaceholder = "可与对话线路不同",
                    modelLabel = "生图模型",
                    modelPlaceholder = "如 dall-e-3 或平台模型 id",
                )
                ProbeProgress("image", probeBusy, probeHint, probeLines)
                Button(
                    onClick = { viewModel.runProbeImage { scope.launch { snackbarHostState.showSnackbar(it) } } },
                    enabled = probeBusy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("3. 测试配图连接") }
            }

            ConnectionChannel.VOICE -> ConnectionCard("朗读与语音", "只用本机朗读时选择 system，不需要联网 Key") {
                ApiVendorPresetRow(
                    sectionLabel = "1. 选择服务商",
                    currentBaseUrl = voiceBaseUrl,
                    currentModel = voiceModel,
                    onBaseUrlChange = viewModel::updateVoiceBaseUrl,
                    onModelChange = viewModel::updateVoiceModel,
                    modelHint = ApiVendorModelHint.VOICE_TTS,
                )
                SecretKeyField("2. 朗读 API Key（留空复用对话 Key）", voiceApiKey, viewModel::updateVoiceApiKey)
                CollapsiblePresetUrlModelBlock(
                    collapsedPreset = ApiProviderPresets.isExactSinglePresetBaseUrl(voiceBaseUrl),
                    showManualFields = showManualVoice,
                    onExpandManual = { showManualVoice = true },
                    onCollapseManual = { showManualVoice = false },
                    baseUrl = voiceBaseUrl,
                    model = voiceModel,
                    onBaseChange = viewModel::updateVoiceBaseUrl,
                    onModelChange = viewModel::updateVoiceModel,
                    baseLabel = "朗读服务地址",
                    basePlaceholder = "本机朗读可留空",
                    modelLabel = "朗读模型",
                    modelPlaceholder = "system = 本机免费",
                )
                OutlinedTextField(
                    value = voiceSpeechVoice,
                    onValueChange = viewModel::updateVoiceSpeechVoice,
                    label = { Text("音色") },
                    placeholder = { Text("如 alloy、alex") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = voicePresetPrefixModel,
                    onValueChange = viewModel::updateVoicePresetPrefixModel,
                    label = { Text("音色前缀模型（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "朗读是否可用，以对话消息旁的 🔊 实际播放为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (selectedChannel == ConnectionChannel.TEXT) {
            ExpandableSettingsCard("高级对话", showAdvanced, { showAdvanced = !showAdvanced }) {
                val allowThink by viewModel.allowSessionThinkMax.collectAsStateWithLifecycle()
                val thinkMaxModel by viewModel.thinkMaxModel.collectAsStateWithLifecycle()
                val memoryEnabled by viewModel.universalContextMemoryEnabled.collectAsStateWithLifecycle()
                SettingSwitchRow(
                    title = "允许会话开启思考 / Max",
                    description = "复杂推理时可在会话侧栏按需开启",
                    checked = allowThink,
                    onCheckedChange = viewModel::updateAllowSessionThinkMax,
                )
                OutlinedTextField(
                    value = thinkMaxModel,
                    onValueChange = viewModel::updateThinkMaxModel,
                    label = { Text("思考模型覆盖（可选）") },
                    placeholder = { Text("如 deepseek-reasoner") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SettingSwitchRow(
                    title = "高密度剧情记忆",
                    description = "自动压缩长期剧情中的关系、事件和伏笔",
                    checked = memoryEnabled,
                    onCheckedChange = viewModel::updateUniversalContextMemoryEnabled,
                )
            }
        }

        ExpandableSettingsCard("诊断与日志", showDiagnostics, { showDiagnostics = !showDiagnostics }) {
            Text(
                "仅排查问题时导出；日志可能包含输入、输出和错误信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { exportLogLauncher.launch(LogExportManager.defaultZipFileName()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出诊断日志") }
        }
    }
}

@Composable
private fun ConnectionCard(title: String, description: String, content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SecretKeyField(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("sk-xxx") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun ProbeProgress(channel: String, busy: String?, hint: String, lines: List<String>) {
    if (busy != channel) return
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
    hint.takeIf(String::isNotBlank)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    lines.takeLast(4).forEach {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandableSettingsCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
