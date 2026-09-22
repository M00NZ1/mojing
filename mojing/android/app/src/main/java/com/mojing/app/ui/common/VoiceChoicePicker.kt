package com.mojing.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.VoiceChoice
import com.mojing.app.data.VoicePreferences
import com.mojing.app.media.AzureSpeech
import com.mojing.app.media.VoiceEngineCatalog
import com.mojing.app.media.VoiceEngineOption
import com.mojing.app.media.VoiceOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 紧凑的引擎 -> 音色选择器；不在 UI 中伪造引擎或音色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChoicePicker(
    choice: VoiceChoice,
    allowInherit: Boolean,
    onSelected: (VoiceChoice) -> Unit,
    onDismiss: () -> Unit,
    inheritLabel: String = "跟随当前会话",
    description: String = "先选引擎，再选择可用音色",
    saving: Boolean = false,
    saveError: String? = null,
    onPreviewStart: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { VoicePreferences(context) }
    var engines by remember { mutableStateOf<List<VoiceEngineOption>>(emptyList()) }
    var selectedEngine by remember(choice.engineId) { mutableStateOf(choice.engineId) }
    var query by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    var loading by remember { mutableStateOf(choice.engineId != "inherit") }
    var engineLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var engineError by remember { mutableStateOf<String?>(null) }
    var azureConfigured by remember { mutableStateOf(false) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    var voiceLoadJob by remember { mutableStateOf<Job?>(null) }
    var engineLoadJob by remember { mutableStateOf<Job?>(null) }
    var engineGeneration by remember { mutableIntStateOf(0) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewGeneration by remember { mutableIntStateOf(0) }

    fun stopPreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        previewId = null
        previewError = null
    }

    fun preview(voice: VoiceOption) {
        val wasPlaying = previewId == voice.id
        stopPreview()
        if (wasPlaying) return
        onPreviewStart()
        val generation = previewGeneration
        val engine = selectedEngine
        previewId = voice.id
        previewJob = scope.launch {
            try {
                kotlinx.coroutines.withTimeout(30_000L) {
                    val text = "你好，欢迎来到墨境。这是当前音色的试听。"
                    if (engine == "azure") {
                        val credentials = withContext(Dispatchers.IO) { preferences.azureRegion to preferences.azureKey }
                        if (!AzureSpeech.speak(context.applicationContext, text, credentials.first, credentials.second, voice.id))
                            throw IllegalStateException("语音播放未完成")
                    } else com.mojing.app.media.AndroidTts.preview(context.applicationContext, text, VoiceChoice(engine, voice.id))
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (generation == previewGeneration) previewError = "试听超时，请检查引擎或网络后重试"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == previewGeneration) previewError = if (engine == "azure") AzureSpeech.failureMessage(failure)
                    else "试听失败，请检查引擎与语音包后重试"
            } finally {
                if (generation == previewGeneration) previewId = null
            }
        }
    }

    fun loadEngines() {
        val generation = ++engineGeneration
        engineLoadJob?.cancel()
        engineLoading = true
        engineError = null
        engineLoadJob = scope.launch {
            try {
                val result = (VoiceEngineCatalog.engines(context) + VoiceEngineOption("azure", "微软 Azure 语音"))
                    .distinctBy { it.id }
                if (generation == engineGeneration) engines = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == engineGeneration) engineError = "语音引擎列表加载失败"
            } finally {
                if (generation == engineGeneration) engineLoading = false
            }
        }
    }

    fun load(engineId: String) {
        stopPreview()
        val generation = ++loadGeneration
        voiceLoadJob?.cancel()
        selectedEngine = engineId
        query = ""
        loading = true
        error = null
        voices = emptyList()
        voiceLoadJob = scope.launch {
            try {
                val result = if (engineId == "azure") {
                    val (region, key) = withContext(Dispatchers.IO) {
                        preferences.azureRegion.trim() to preferences.azureKey.trim()
                    }
                    if (region.isBlank() || key.isBlank()) {
                        if (generation == loadGeneration) error = "请先在语音设置填写 Azure 区域和 API Key"
                        emptyList()
                    } else withContext(Dispatchers.IO) { AzureSpeech.voices(region, key) }
                } else {
                    VoiceEngineCatalog.voices(context, engineId)
                }
                if (generation == loadGeneration && selectedEngine == engineId) voices = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == loadGeneration && selectedEngine == engineId) {
                    error = if (engineId == "azure") AzureSpeech.failureMessage(failure)
                    else "音色列表加载失败，请检查引擎和语音包后重试"
                }
            } finally {
                if (generation == loadGeneration && selectedEngine == engineId) loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        azureConfigured = withContext(Dispatchers.IO) {
            preferences.azureRegion.isNotBlank() && preferences.azureKey.isNotBlank()
        }
        loadEngines()
        if (selectedEngine != "inherit") load(selectedEngine)
    }

    val searchQuery = query.trim()
    val displayVoices = (listOf(VoiceOption("", if (selectedEngine == "azure") "默认 · 晓晓" else "引擎默认")) + voices.filter { it.id.isNotBlank() })
        .filter { searchQuery.isBlank() || it.name.contains(searchQuery, true) || it.id.contains(searchQuery, true) }

    val currentSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { value -> value != SheetValue.Hidden || !currentSaving },
    )
    ModalBottomSheet(sheetState = sheetState, onDismissRequest = { if (!saving) { stopPreview(); onDismiss() } }) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = (configuration.screenHeightDp * 0.8f).dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择朗读引擎", style = MaterialTheme.typography.titleLarge)
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(enabled = !saving, onClick = { loadEngines(); if (selectedEngine != "inherit") load(selectedEngine) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新引擎和音色")
                }
            }
            if (saving || saveError != null) {
                Text(
                    if (saving) "正在保存语音选择…" else saveError.orEmpty(),
                    color = if (saving) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            if (allowInherit) {
                TextButton(
                    enabled = !saving,
                    onClick = { stopPreview(); onSelected(VoiceChoice("inherit", "")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) { Text(if (choice.engineId == "inherit") "✓ $inheritLabel" else inheritLabel) }
                HorizontalDivider()
            }
            if (engineLoading) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else if (engineError != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(engineError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::loadEngines) { Text("重试") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    engines.forEach { engine ->
                        FilterChip(
                            selected = selectedEngine == engine.id,
                            onClick = { if (selectedEngine != engine.id) load(engine.id) },
                            enabled = !saving && (engine.id != "azure" || azureConfigured),
                            label = { Text(engine.name, modifier = Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            if (!azureConfigured) {
                Text(
                    "使用微软语音：在设置 → 朗读中填写 Azure 区域和 Speech Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (selectedEngine != "inherit") {
                if (loading) {
                    Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                } else if (error != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { load(selectedEngine) }) { Text("重试") }
                    }
                } else {
                    Text("音色", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                    if (voices.size > 8) MoJingTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("搜索音色") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    ) {
                        if (displayVoices.isEmpty()) {
                            item {
                                Text("没有匹配的音色，请更换关键词", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                            }
                        }
                        items(displayVoices, key = { "${selectedEngine}:${it.id}" }) { voice ->
                            val selected = selectedEngine == choice.engineId && voice.id == choice.voiceId
                            Surface(
                                enabled = !saving,
                                onClick = { stopPreview(); onSelected(VoiceChoice(selectedEngine, voice.id)) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                        supportingColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    headlineContent = { Text(voice.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { if (voice.id.isNotBlank()) Text(voice.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (selected) Text("已选", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                            TextButton(enabled = !saving, onClick = { preview(voice) }) {
                                                Text(if (previewId == voice.id) "停止" else "试听")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
