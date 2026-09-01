package com.mojing.app.ui.encyclopedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EntryRelationDao
import com.mojing.app.data.local.dao.TimelineEventDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.EntryRelationEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.encyclopedia.WorldInfoAiConverter
import com.mojing.app.domain.encyclopedia.WorldInfoImportParser
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.util.DocxTextExtractor
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.mojing.app.domain.usecase.DeleteEncyclopediaEntryUseCase
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ApiRootLines
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject

enum class EncyclopediaMainTab {
    ENTRIES,
    TIMELINE,
    GRAPH,
    SEDIMENT
}

data class EncyclopediaDetailState(
    val encyclopedia: EncyclopediaEntity? = null,
    val entries: List<EncyclopediaEntryEntity> = emptyList(),
    val selectedType: String = "",
    /** 宽屏「条目」Tab 右侧预览所选条目 id */
    val previewEntryId: Long? = null,
    /** 当前百科下进行中的生成任务（用于进度条） */
    val activeGenTasks: List<GenerationTaskEntity> = emptyList(),
    val mainTab: EncyclopediaMainTab = EncyclopediaMainTab.ENTRIES,
    val timelineEvents: List<TimelineEventEntity> = emptyList(),
    val relations: List<EntryRelationEntity> = emptyList(),
    val entryTitles: Map<Long, String> = emptyMap(),
    val pickerEntries: List<EncyclopediaEntryEntity> = emptyList(),
    val sedimentEntries: List<EncyclopediaEntryEntity> = emptyList(),
    val snackbar: String? = null,
    /** 设置中是否已填公共对话 API Key（用于批量 AI 等入口提示） */
    val hasPublicLlmKey: Boolean = false,
    /** 无法直接解析的导入文本：进入底部预览，可编辑后「智能解析」或「按格式解析」 */
    val worldInfoReviewText: String? = null,
    val worldInfoImportBusy: Boolean = false,
    val isLoaded: Boolean = false,
    val loadError: String? = null,
) {
    /** 是否有百科「扩展 meta」批量补全任务在排队或执行（用于禁用重复提交） */
    val isEncyclopediaMetaFillQueued: Boolean
        get() = activeGenTasks.any { it.taskKind == GenerationTaskKinds.ENCYCLOPEDIA_META_FILL }
}

@HiltViewModel
class EncyclopediaDetailViewModel @Inject constructor(
    private val encyclopediaDao: EncyclopediaDao,
    private val entryDao: EncyclopediaEntryDao,
    private val saveCharacterEntry: SaveCharacterEntryUseCase,
    private val deleteEncyclopediaEntry: DeleteEncyclopediaEntryUseCase,
    private val entryRelationDao: EntryRelationDao,
    private val timelineEventDao: TimelineEventDao,
    private val secureStorage: SecureStorage,
    private val generationQueueProcessor: GenerationQueueProcessor,
    private val llmApiService: LlmApiService,
    private val worldInfoAiConverter: WorldInfoAiConverter,
) : ViewModel() {
    private val _state = MutableStateFlow(EncyclopediaDetailState())
    val state: StateFlow<EncyclopediaDetailState> = _state.asStateFlow()

    private var encId: Long = 0
    private var genObserveJob: Job? = null
    /** 曾观察到本百科有进行中的生成任务；用于在「进行中 → 无」时提示一次批量结束（避免重复空列表误报） */
    private var hadActiveGenerationForEnc: Boolean = false

    fun consumeSnackbar() {
        _state.value = _state.value.copy(snackbar = null)
    }

    private fun showSnackbar(msg: String) {
        _state.value = _state.value.copy(snackbar = msg)
    }

    fun load(id: Long) {
        if (encId != id) hadActiveGenerationForEnc = false
        encId = id
        genObserveJob?.cancel()
        genObserveJob = null
        _state.value = EncyclopediaDetailState(
            isLoaded = false,
            hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
        )
        viewModelScope.launch {
            try {
                val encyclopedia = encyclopediaDao.getById(id)
                if (encyclopedia == null) {
                    _state.value = _state.value.copy(
                        isLoaded = true,
                        loadError = "找不到这个百科，它可能已经被删除",
                    )
                    return@launch
                }
                val entries = entryDao.getByEncyclopedia(id)
                val events = timelineEventDao.getByEncyclopedia(id)
                val relations = entryRelationDao.getByEncyclopedia(id)
                val sediment = entryDao.getSedimentEntries(id)
                _state.value = _state.value.copy(
                    encyclopedia = encyclopedia,
                    entries = entries,
                    timelineEvents = events,
                    relations = relations,
                    entryTitles = entries.associate { it.id to it.title },
                    pickerEntries = entries,
                    sedimentEntries = sediment,
                    isLoaded = true,
                    loadError = null,
                )
                startGenerationObservation(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    isLoaded = true,
                    loadError = "读取百科失败，请重试",
                )
            }
        }
    }

    private fun startGenerationObservation(id: Long) {
        genObserveJob?.cancel()
        genObserveJob = viewModelScope.launch {
            generationQueueProcessor.observeActiveForEncyclopedia(id).collectLatest { list ->
                val hasActive = list.isNotEmpty()
                if (hadActiveGenerationForEnc && !hasActive) {
                    showSnackbar(
                        "本百科的批量生成或补全已结束，新内容已写入库。点顶部「任务」可查看记录。",
                    )
                    hadActiveGenerationForEnc = false
                } else if (hasActive) {
                    hadActiveGenerationForEnc = true
                }
                _state.value = _state.value.copy(activeGenTasks = list)
                refreshEntries()
                refreshTimelineAndRelations()
            }
        }
    }

    fun setMainTab(tab: EncyclopediaMainTab) {
        _state.value = _state.value.copy(mainTab = tab)
        if (tab != EncyclopediaMainTab.ENTRIES) {
            viewModelScope.launch { refreshTimelineAndRelations() }
        }
    }

    fun selectType(type: String) {
        _state.value = _state.value.copy(selectedType = type, previewEntryId = null)
        viewModelScope.launch { refreshEntries() }
    }

    fun setPreviewEntry(id: Long?) {
        _state.value = _state.value.copy(previewEntryId = id)
    }

    fun importWorldInfoJson(raw: String) {
        viewModelScope.launch { tryImportWorldInfoText(raw, openEditorOnFailure = true) }
    }

    suspend fun loadWorldInfoImportBytes(bytes: ByteArray, fileName: String?) {
        _state.value = _state.value.copy(worldInfoImportBusy = true)
        try {
            val text = withContext(Dispatchers.Default) { decodeImportBytesToText(bytes, fileName) }
            if (text.isNullOrBlank()) {
                showSnackbar("无法从该文件提取文本（.doc 旧版不支持，请另存为 .docx 或 .txt）")
                return
            }
            tryImportWorldInfoText(text, openEditorOnFailure = true)
        } finally {
            _state.value = _state.value.copy(worldInfoImportBusy = false)
        }
    }

    fun dismissWorldInfoReview() {
        _state.value = _state.value.copy(worldInfoReviewText = null, worldInfoImportBusy = false)
    }

    fun updateWorldInfoReviewText(text: String) {
        _state.value = _state.value.copy(worldInfoReviewText = text)
    }

    fun importWorldInfoFromReviewTryParse() {
        val raw = _state.value.worldInfoReviewText ?: return
        viewModelScope.launch {
            try {
                val drafts = withContext(Dispatchers.Default) { WorldInfoImportParser.parse(raw.trim()) }
                if (drafts.isEmpty()) {
                    showSnackbar("仍无法识别为 WorldInfo JSON，请使用「智能解析并入库」")
                    return@launch
                }
                persistWorldInfoDrafts(drafts)
                dismissWorldInfoReview()
                showSnackbar("已导入 ${drafts.size} 条")
            } catch (e: Exception) {
                showSnackbar("解析失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun importWorldInfoFromReviewWithAi() {
        if (secureStorage.publicApiKey.isBlank()) {
            showSnackbar(UserFacingStrings.llmKeyMissingForAiComplete())
            return
        }
        val raw = _state.value.worldInfoReviewText ?: return
        val enc = _state.value.encyclopedia ?: return
        val hint = encyclopediaWorldHint(enc)
        viewModelScope.launch {
            _state.value = _state.value.copy(worldInfoImportBusy = true)
            try {
                val apiKey = secureStorage.publicApiKey.trim()
                val model = secureStorage.publicModel.trim()
                var last: Throwable? = null
                var json: String? = null
                for (base in llmBases()) {
                    val got = runCatching {
                        worldInfoAiConverter.rawTextToWorldInfoJsonArray(
                            apiKey = apiKey,
                            baseUrl = base,
                            model = model,
                            rawText = raw,
                            encyclopediaHint = hint,
                        )
                    }.onFailure { last = it }.getOrNull()
                    if (got != null) {
                        json = got
                        break
                    }
                }
                if (json == null) throw last ?: IllegalStateException("模型调用失败")
                val drafts = withContext(Dispatchers.Default) { WorldInfoImportParser.parse(json) }
                if (drafts.isEmpty()) throw IllegalStateException("解析结果为空")
                persistWorldInfoDrafts(drafts)
                dismissWorldInfoReview()
                showSnackbar("已通过 AI 整理并导入 ${drafts.size} 条")
            } catch (e: Exception) {
                showSnackbar("智能导入失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.value = _state.value.copy(worldInfoImportBusy = false)
            }
        }
    }

    private suspend fun tryImportWorldInfoText(raw: String, openEditorOnFailure: Boolean) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            showSnackbar("内容为空")
            return
        }
        val drafts = withContext(Dispatchers.Default) {
            runCatching { WorldInfoImportParser.parse(trimmed) }.getOrElse { emptyList() }
        }
        if (drafts.isNotEmpty()) {
            persistWorldInfoDrafts(drafts)
            refreshEntries()
            refreshTimelineAndRelations()
            showSnackbar("已导入 WorldInfo ${drafts.size} 条")
            return
        }
        if (openEditorOnFailure) {
            _state.value = _state.value.copy(worldInfoReviewText = trimmed, worldInfoImportBusy = false)
            showSnackbar("未识别为标准格式，已打开编辑预览：可修改后选「智能解析并入库」")
        } else {
            showSnackbar("WorldInfo 导入失败: 无法解析为条目数组")
        }
    }

    private suspend fun persistWorldInfoDrafts(drafts: List<WorldInfoImportParser.DraftEntry>) {
        for (d in drafts) {
            saveCharacterEntry(
                EncyclopediaEntryEntity(
                    encyclopediaId = encId,
                    title = d.title,
                    entryType = d.entryType,
                    summary = d.summary,
                    content = d.content,
                    tags = d.tags,
                    metaJson = d.metaJson,
                ),
            )
        }
        refreshEntries()
        refreshTimelineAndRelations()
    }

    fun updateEncyclopediaName(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isBlank()) {
                showSnackbar("百科名称不能为空")
                return@launch
            }
            val enc = encyclopediaDao.getById(encId) ?: return@launch
            val now = System.currentTimeMillis()
            if (encyclopediaDao.updateName(encId, trimmed, now) == 0) {
                showSnackbar("百科库已删除，未保存名称")
                return@launch
            }
            _state.value = _state.value.copy(encyclopedia = enc.copy(name = trimmed, updatedAt = now))
            showSnackbar("已更新百科名称")
        }
    }

    private fun decodeImportBytesToText(bytes: ByteArray, fileName: String?): String? {
        val lower = fileName?.lowercase().orEmpty()
        val isPk = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (lower.endsWith(".doc") && !lower.endsWith(".docx")) return null
        if (lower.endsWith(".docx")) {
            return DocxTextExtractor.tryExtractPlainText(bytes)
        }
        val docxText = DocxTextExtractor.tryExtractPlainText(bytes)
        if (docxText != null && isPk) return docxText
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            try {
                String(bytes, Charset.forName("GBK"))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun llmBases(): List<String> {
        val trimmed = secureStorage.publicBaseUrl.trim()
        return ApiRootLines.splitToOrderedDistinct(trimmed, llmApiService::normalizeOpenAiCompatibleBase)
    }

    fun createEntry(title: String, type: String) {
        viewModelScope.launch {
            saveCharacterEntry(
                EncyclopediaEntryEntity(
                    encyclopediaId = encId,
                    title = title.ifBlank { "新条目" },
                    entryType = type
                )
            )
            refreshEntries()
            refreshTimelineAndRelations()
            showSnackbar(UserFacingStrings.entryCreatedListHint())
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            deleteEncyclopediaEntry(id)
            refreshEntries()
            refreshTimelineAndRelations()
        }
    }

    fun toggleEntryFeatured(id: Long) {
        viewModelScope.launch {
            val e = entryDao.getById(id) ?: return@launch
            if (e.encyclopediaId != encId) return@launch
            val ts = System.currentTimeMillis()
            entryDao.upsert(e.copy(isFeatured = !e.isFeatured, updatedAt = ts))
            refreshEntries()
            refreshTimelineAndRelations()
        }
    }

    private suspend fun refreshEntries() {
        val type = _state.value.selectedType
        val entries = if (type.isEmpty() || type == "全部") {
            entryDao.getByEncyclopedia(encId)
        } else {
            entryDao.getByType(encId, type)
        }
        val prevPreview = _state.value.previewEntryId
        val nextPreview =
            if (prevPreview != null && entries.none { it.id == prevPreview }) null else prevPreview
        _state.value = _state.value.copy(entries = entries, previewEntryId = nextPreview)
    }

    private suspend fun refreshTimelineAndRelations() {
        val events = timelineEventDao.getByEncyclopedia(encId)
        val rels = entryRelationDao.getByEncyclopedia(encId)
        val allEntries = entryDao.getByEncyclopedia(encId)
        val titles = allEntries.associate { it.id to it.title }
        val sediment = entryDao.getSedimentEntries(encId)
        _state.value = _state.value.copy(
            timelineEvents = events,
            relations = rels,
            entryTitles = titles,
            pickerEntries = allEntries,
            sedimentEntries = sediment
        )
    }

    fun addTimelineEvent(title: String, timeLabel: String, sortOrder: Int) {
        viewModelScope.launch {
            if (title.isBlank()) {
                showSnackbar(UserFacingStrings.timelineTitleRequired())
                return@launch
            }
            timelineEventDao.upsert(
                TimelineEventEntity(
                    encyclopediaId = encId,
                    entryId = null,
                    title = title.trim(),
                    description = "",
                    eventTime = timeLabel.trim(),
                    sortOrder = sortOrder
                )
            )
            refreshTimelineAndRelations()
        }
    }

    fun deleteTimelineEvent(id: Long) {
        viewModelScope.launch {
            timelineEventDao.delete(id)
            refreshTimelineAndRelations()
        }
    }

    fun addRelation(fromEntryId: Long, toEntryId: Long, relationType: String, label: String) {
        viewModelScope.launch {
            if (fromEntryId == toEntryId) {
                showSnackbar(UserFacingStrings.relationEndpointsMustDiffer())
                return@launch
            }
            entryRelationDao.upsert(
                EntryRelationEntity(
                    encyclopediaId = encId,
                    fromEntryId = fromEntryId,
                    toEntryId = toEntryId,
                    relationType = relationType.ifBlank { "关联" },
                    label = label.trim()
                )
            )
            refreshTimelineAndRelations()
        }
    }

    fun deleteRelation(id: Long) {
        viewModelScope.launch {
            entryRelationDao.delete(id)
            refreshTimelineAndRelations()
        }
    }

    fun batchGenerate(
        type: String,
        count: Int,
        minWords: Int,
        maxWords: Int,
        contextPrompt: String,
        runInBackground: Boolean,
        outputMode: String = "entries",
        preGenNotes: String = "",
    ) {
        if (secureStorage.publicApiKey.isBlank()) {
            showSnackbar(UserFacingStrings.llmKeyMissingForAiComplete())
            return
        }
        val enc = _state.value.encyclopedia ?: return
        val worldBg = encyclopediaWorldHint(enc)
        viewModelScope.launch {
            generationQueueProcessor.enqueueEncyclopediaBatch(
                encyclopediaId = enc.id,
                encyclopediaName = enc.name.ifBlank { "百科" },
                worldBackground = worldBg,
                entryType = type,
                count = count,
                minWords = minWords,
                maxWords = maxWords,
                userContext = contextPrompt,
                outputMode = outputMode.trim().lowercase().takeIf { it.isNotBlank() && it != "entries" },
                preGenNotes = preGenNotes.trim().takeIf { it.isNotBlank() },
            )
            val n = generationQueueProcessor.countActiveTasks()
            showSnackbar(
                if (runInBackground) {
                    "已加入队列（当前共 $n 个在跑）。生成完会自动写入本百科；离开本页也没关系，结束时会有提示。点顶部云图标可看进度。"
                } else {
                    "已开始（当前共 $n 个在跑）。看下方进度条；写完会自动刷新列表，结束时也会有提示。"
                },
            )
        }
    }

    private fun encyclopediaWorldHint(enc: EncyclopediaEntity): String =
        listOfNotNull(
            enc.description.trim().takeIf { it.isNotBlank() },
            enc.genreTags.trim().takeIf { it.isNotBlank() },
            enc.worldPrompt.trim().takeIf { it.isNotBlank() },
            enc.antiCheatPrompt.trim().takeIf { it.isNotBlank() },
        ).joinToString("\n\n")

    /**
     * 对当前「条目」列表中的每一条，用设置里的文本模型补全 **扩展 meta** 中空缺键（不覆盖已有非空内容）。
     */
    fun batchAiFillMetaForCurrentEntries() {
        if (secureStorage.publicApiKey.isBlank()) {
            showSnackbar(UserFacingStrings.llmKeyMissingForAiComplete())
            return
        }
        val entries = _state.value.entries
        if (entries.isEmpty()) {
            showSnackbar("当前列表没有条目")
            return
        }
        if (_state.value.isEncyclopediaMetaFillQueued) {
            showSnackbar("已有扩展字段补全任务在队列中")
            return
        }
        val enc = _state.value.encyclopedia ?: return
        viewModelScope.launch {
            generationQueueProcessor.enqueueEncyclopediaMetaFill(
                encyclopediaId = enc.id,
                encyclopediaName = enc.name.ifBlank { "百科" },
                entryIds = entries.map { it.id },
            )
            val n = generationQueueProcessor.countActiveTasks()
            showSnackbar("已加入队列（当前共 $n 个在跑）。补全完会写入各条目并刷新本页；全部结束时会有提示。点顶部云图标可看进度。")
        }
    }
}
