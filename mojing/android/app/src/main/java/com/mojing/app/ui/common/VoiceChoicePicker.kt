package com.mojing.app.ui.common

import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.VoiceChoice
import com.mojing.app.data.VoicePreferences
import com.mojing.app.media.AzureSpeech
import com.mojing.app.media.VoiceEngineCatalog
import com.mojing.app.media.VoiceCatalogException
import com.mojing.app.media.VoiceEngineOption
import com.mojing.app.media.VoiceCatalogResult
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
    var searchExpanded by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    var loadedEngine by remember { mutableStateOf<String?>(null) }
    var defaultEnginePackage by remember { mutableStateOf<String?>(null) }
    var showOtherLanguages by remember { mutableStateOf(false) }
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
    var previewName by remember { mutableStateOf("") }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewGeneration by remember { mutableIntStateOf(0) }
    val voiceListState = rememberLazyListState()
    LaunchedEffect(selectedEngine) { voiceListState.scrollToItem(0) }

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
        previewName = voice.name
        previewJob = scope.launch {
            try {
                kotlinx.coroutines.withTimeout(30_000L) {
                    val text = voicePreviewText(voice.languageTag)
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
        loading = true
        error = null
        voiceLoadJob = scope.launch {
            try {
                val result = if (engineId == "azure") {
                    val (region, key) = withContext(Dispatchers.IO) {
                        preferences.azureRegion.trim() to preferences.azureKey.trim()
                    }
                    if (region.isBlank() || key.isBlank()) {
                        if (generation == loadGeneration) error = "请先在语音设置填写 Azure 区域和 API Key"
                        VoiceCatalogResult(emptyList(), null)
                    } else VoiceCatalogResult(withContext(Dispatchers.IO) { AzureSpeech.voices(region, key) }, null)
                } else {
                    VoiceEngineCatalog.voices(context, engineId)
                }
                if (generation == loadGeneration && selectedEngine == engineId) {
                    query = ""
                    searchExpanded = false
                    voices = result.voices
                    loadedEngine = engineId
                    showOtherLanguages = engineId != "azure" && engineId == choice.engineId &&
                        result.voices.any { it.id == choice.voiceId && !it.isChineseVoice() }
                    if (engineId != "azure") defaultEnginePackage = result.defaultEnginePackage
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == loadGeneration && selectedEngine == engineId) {
                    error = if (engineId == "azure") AzureSpeech.failureMessage(failure)
                    else (failure as? VoiceCatalogException)?.message
                        ?: "音色列表加载失败，请检查引擎和语音包后重试"
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
    // Do not display the previous engine's voices under a newly selected engine while it loads.
    val readyVoices = voices.takeIf { !loading && error == null && loadedEngine == selectedEngine }.orEmpty()
    val otherLanguageCount = if (selectedEngine == "azure") 0 else readyVoices.count { !it.isChineseVoice() }
    val visibleVoices = if (selectedEngine == "azure" || showOtherLanguages || searchQuery.isNotBlank()) readyVoices
        else readyVoices.filter { it.isChineseVoice() }
    val displayVoices = (listOf(VoiceOption("", if (selectedEngine == "azure") "默认 · 晓晓" else "引擎默认")) + visibleVoices.filter { it.id.isNotBlank() })
        .distinctBy { it.id }
        .filter { searchQuery.isBlank() || it.name.contains(searchQuery, true) || it.id.contains(searchQuery, true) }

    val currentSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !currentSaving },
    )
    ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
        sheetState = sheetState,
        onDismissRequest = { if (!saving) { stopPreview(); onDismiss() } },
        sheetMaxWidth = 640.dp,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().height((configuration.screenHeightDp * 0.85f).dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择朗读音色", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(enabled = !saving, onClick = { loadEngines(); if (selectedEngine != "inherit") load(selectedEngine) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新引擎和音色")
                }
                IconButton(enabled = !saving, onClick = { stopPreview(); onDismiss() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭音色选择")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = voiceListState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            ) {
            item(key = "voice-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp))
            if (saving || saveError != null) {
                Text(
                    if (saving) "正在保存语音选择…" else saveError.orEmpty(),
                    color = if (saving) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            if (previewId != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("试听中", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(previewName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        TextButton(onClick = ::stopPreview) { Text("停止试听") }
                    }
                }
            }
            if (allowInherit) {
                val inheritSelected = choice.engineId == "inherit"
                Surface(
                    enabled = !saving,
                    onClick = { stopPreview(); onSelected(VoiceChoice("inherit", "")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = if (inheritSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(inheritLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (inheritSelected) Icon(Icons.Outlined.Check, "已选跟随设置", Modifier.size(20.dp))
                    }
                }
                HorizontalDivider()
            }
            if (engines.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    engines.forEach { engine ->
                        val engineName = when {
                            engine.id == "system" -> "跟随系统默认"
                            engine.id.startsWith("android:") -> "指定 · ${engine.name}"
                            else -> engine.name
                        }
                        FilterChip(
                            selected = selectedEngine == engine.id,
                            onClick = { if (selectedEngine != engine.id) load(engine.id) },
                            enabled = !saving && (engine.id != "azure" || azureConfigured),
                            label = { Text(engineName, modifier = Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
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
            }
            if (selectedEngine != "azure" && defaultEnginePackage != null) {
                val defaultName = engines.firstOrNull { it.id == "android:$defaultEnginePackage" }?.name
                    ?: defaultEnginePackage
                Text(
                    when (selectedEngine) {
                        "system" -> "系统设置的默认引擎是 $defaultName；此选项会跟随系统设置。"
                        "android:$defaultEnginePackage" -> "已指定 $defaultName。它也是系统设置的默认引擎，因此两项音色相同。"
                        else -> "系统设置的默认引擎是 $defaultName；已请求所选引擎，若不可用 Android 可能回退。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
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
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(if (loadedEngine == null) "正在读取音色…" else "正在读取所选引擎的音色…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (error != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { load(selectedEngine) }) { Text("重试") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selectedEngine == "azure") "音色" else if (showOtherLanguages || searchQuery.isNotBlank()) "全部语种与声音" else "中文语音", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${visibleVoices.size}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (otherLanguageCount > 0) TextButton(onClick = { showOtherLanguages = !showOtherLanguages }) {
                            Text(if (showOtherLanguages) "只看中文" else "其他语种 $otherLanguageCount")
                        }
                        if (readyVoices.size > 8) IconButton(onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) query = ""
                        }) {
                            Icon(if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                                if (searchExpanded) "收起音色搜索" else "搜索音色")
                        }
                    }
                    if (readyVoices.size > 8 && searchExpanded) MoJingTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("搜索音色") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    if (selectedEngine != "azure") Text(
                        if (showOtherLanguages || searchQuery.isNotBlank()) "列表包含引擎报告的语言和声音，不代表会翻译原文或一定有不同声线；中文正文仍可能按中文朗读。试听使用对应语种。"
                        else if (readyVoices.none { it.isChineseVoice() }) "此引擎未提供已安装的中文语音，可改用引擎默认或检查系统语音包。"
                        else "这里只显示已安装的中文语音；其他语种可按需展开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            }
            }
            if (selectedEngine != "inherit" && !loading && error == null && loadedEngine == selectedEngine) {
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
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(voice.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                                        if (voice.id.isNotBlank()) Text(voice.id, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (selected) Icon(Icons.Outlined.Check, "已选音色", Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    TextButton(enabled = !saving && !loading, onClick = { preview(voice) }) {
                                        Text(if (previewId == voice.id) "停止" else "试听")
                                    }
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
            }
            }
        }
    }
}

internal fun VoiceOption.isChineseVoice(): Boolean = languageTag.substringBefore('-').equals("zh", ignoreCase = true)

internal fun voicePreviewText(languageTag: String): String = when (languageTag.substringBefore('-').lowercase()) {
    "en" -> "Hello, welcome to MoJing. This is a voice preview."
    "ar" -> "مرحبًا، هذا مثال للاستماع إلى الصوت."
    "ja" -> "こんにちは。これは音声の試聴です。"
    "ko" -> "안녕하세요. 음성 미리 듣기입니다."
    "fr" -> "Bonjour, ceci est un aperçu de la voix."
    "de" -> "Hallo, dies ist eine Stimmprobe."
    "es" -> "Hola, esta es una prueba de voz."
    "ru" -> "Здравствуйте, это образец голоса."
    "zh", "" -> "你好，欢迎来到墨境。这是当前音色的试听。"
    else -> "1 2 3 4 5"
}
