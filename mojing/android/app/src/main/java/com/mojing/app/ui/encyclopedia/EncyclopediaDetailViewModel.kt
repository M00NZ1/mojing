package com.mojing.app.ui.encyclopedia

import androidx.lifecycle.SavedStateHandle
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
    val entryCursors: List<Long> = listOf(0L),
    val entriesHasNext: Boolean = false,
    val entriesLoading: Boolean = false,
    val entriesError: String? = null,
    val filteredEntryCount: Int = 0,
    val metaFillSubmitting: Boolean = false,
    /** 当前百科下进行中的生成任务（用于进度条） */
    val activeGenTasks: List<GenerationTaskEntity> = emptyList(),
    val mainTab: EncyclopediaMainTab = EncyclopediaMainTab.ENTRIES,
    val timelineEvents: List<TimelineEventEntity> = emptyList(),
    val relations: List<EntryRelationEntity> = emptyList(),
    val entryTitles: Map<Long, String> = emptyMap(),
    val entryCount: Int = 0,
    val sedimentEntries: List<EncyclopediaEntryEntity> = emptyList(),
    val sedimentConfirming: Boolean = false,
    val sedimentFilter: String = "all",
    val sedimentCursors: List<Long> = listOf(Long.MAX_VALUE),
    val sedimentHasNext: Boolean = false,
    val sedimentLoading: Boolean = false,
    val sedimentError: String? = null,
    val sedimentTotal: Int = 0,
    val sedimentConfirmed: Int = 0,
    val snackbar: String? = null,
    /** 设置中是否已填公共对话 API Key（用于批量 AI 等入口提示） */
    val hasPublicLlmKey: Boolean = false,
    /** 无法直接解析的导入文本：进入底部预览，可编辑后「智能解析」或「按格式解析」 */
    val worldInfoReviewText: String? = null,
    val worldInfoImportBusy: Boolean = false,
    val isLoaded: Boolean = false,
    val loadError: String? = null,
    val renameDraft: String? = null,
    val renameSaving: Boolean = false,
    val renameError: String? = null,
    val relationSaving: Boolean = false,
    val relationError: String? = null,
) {
    /** 是否有百科「扩展 meta」批量补全任务在排队或执行（用于禁用重复提交） */
    val isEncyclopediaMetaFillQueued: Boolean
        get() = metaFillSubmitting || activeGenTasks.any { it.taskKind == GenerationTaskKinds.ENCYCLOPEDIA_META_FILL }
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
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(EncyclopediaDetailState())
    val state: StateFlow<EncyclopediaDetailState> = _state.asStateFlow()

    private var sedimentJob: Job? = null
    private var sedimentRevision = 0L
    private var encId: Long = 0
    private var loadJob: Job? = null
    // 同一百科的多次读取也必须按最后一次请求发布。
    private var loadRevision = 0L
    private var entriesRevision = 0L
    private var relationsRevision = 0L
    // 离开再返回相同 ID 仍是新的页面归属。
    private var pageRevision = 0L
    // 读取多个表期间保存的名称优先于读取开始时的快照。
    private var nameRevision = 0L
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
        val renameState = _state.value.takeIf { encId == id }
        if (encId != id) {
            hadActiveGenerationForEnc = false
            pageRevision++
        }
        val requestRevision = ++loadRevision
        entriesRevision++
        relationsRevision++
        val initialNameRevision = nameRevision
        loadJob?.cancel()
        sedimentJob?.cancel()
        sedimentRevision++
        encId = id
        genObserveJob?.cancel()
        genObserveJob = null
        _state.value = EncyclopediaDetailState(
            sedimentConfirming = _state.value.sedimentConfirming,
            sedimentFilter = renameState?.sedimentFilter ?: savedStateHandle.get<String>("sediment_filter_$id")?.takeIf { it in listOf("all", "pending", "confirmed") } ?: "all",
            sedimentCursors = renameState?.sedimentCursors ?: savedStateHandle.get<LongArray>("sediment_cursors_$id")?.toList()?.takeIf { it.firstOrNull() == Long.MAX_VALUE && it.all { cursor -> cursor > 0 } && it.zipWithNext().all { pair -> pair.first > pair.second } } ?: listOf(Long.MAX_VALUE),
            encyclopedia = renameState?.encyclopedia,
            mainTab = renameState?.mainTab ?: EncyclopediaMainTab.ENTRIES,
            selectedType = renameState?.selectedType ?: savedStateHandle.get<String>("entry_type_$id").orEmpty(),
            entryCursors = renameState?.entryCursors ?: savedStateHandle.get<LongArray>("entry_cursors_$id")?.toList()
                ?.takeIf { it.firstOrNull() == 0L && it.all { c -> c >= 0 } && it.zipWithNext().all { pair -> pair.first < pair.second } } ?: listOf(0L),
            metaFillSubmitting = _state.value.metaFillSubmitting,
            previewEntryId = renameState?.previewEntryId,
            isLoaded = false,
            hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
            renameDraft = renameState?.renameDraft,
            renameSaving = renameState?.renameSaving ?: false,
            renameError = renameState?.renameError,
            relationSaving = _state.value.relationSaving,
            relationError = renameState?.relationError,
        )
        loadJob = viewModelScope.launch {
            try {
                val encyclopedia = encyclopediaDao.getById(id)
                if (requestRevision != loadRevision) return@launch
                if (encyclopedia == null) {
                    _state.value = _state.value.copy(
                        isLoaded = true,
                        loadError = "找不到这个百科，它可能已经被删除",
                    )
                    return@launch
                }
                val type = _state.value.selectedType
                val cursor = _state.value.entryCursors.last()
                val rows = entryDao.getEntryPage(id, cursor, type)
                val entries = rows.take(100)
                val total = entryDao.countEntries(id, "")
                val filteredCount = entryDao.countEntries(id, type)
                val events = timelineEventDao.getByEncyclopedia(id)
                val relations = entryRelationDao.getByEncyclopedia(id)
                val titles = loadRelationTitles(id, relations)
                if (requestRevision != loadRevision) return@launch
                val current = _state.value.encyclopedia
                val visibleEntries = entries
                _state.value = _state.value.copy(
                    encyclopedia = if (nameRevision != initialNameRevision && current?.id == id) {
                        encyclopedia.copy(name = current.name, updatedAt = current.updatedAt)
                    } else encyclopedia,
                    entries = visibleEntries,
                    entriesHasNext = rows.size > 100,
                    filteredEntryCount = filteredCount,
                    previewEntryId = _state.value.previewEntryId?.takeIf { previewId ->
                        visibleEntries.any { it.id == previewId }
                    },
                    timelineEvents = events,
                    relations = relations,
                    entryTitles = titles,
                    entryCount = total,
                    isLoaded = true,
                    loadError = null,
                )
                reloadSediment()
                startGenerationObservation(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestRevision != loadRevision) return@launch
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
        if (!_state.value.isLoaded || _state.value.loadError != null) return
        if (type == _state.value.selectedType) return
        _state.value = _state.value.copy(selectedType = type, entryCursors = listOf(0L), previewEntryId = null)
        reloadEntryPage()
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

    fun beginRename() {
        if (_state.value.renameSaving) return
        val name = _state.value.encyclopedia?.name ?: return
        _state.value = _state.value.copy(renameDraft = name, renameError = null)
    }

    fun editRename(name: String) {
        if (!_state.value.renameSaving) {
            _state.value = _state.value.copy(renameDraft = name, renameError = null)
        }
    }

    fun dismissRename() {
        if (!_state.value.renameSaving) {
            _state.value = _state.value.copy(renameDraft = null, renameError = null)
        }
    }

    fun updateEncyclopediaName() {
        if (_state.value.renameSaving) return
        val trimmed = _state.value.renameDraft?.trim() ?: return
        if (trimmed.isBlank()) {
            _state.value = _state.value.copy(renameError = "百科名称不能为空")
            return
        }
        val targetId = encId
        val targetPage = pageRevision
        _state.value = _state.value.copy(renameSaving = true, renameError = null)
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val updated = encyclopediaDao.updateName(targetId, trimmed, now)
                if (pageRevision != targetPage) return@launch
                if (updated == 0) {
                    _state.value = _state.value.copy(renameError = "百科已不存在，未保存名称")
                } else {
                    nameRevision++
                    _state.value = _state.value.copy(
                        encyclopedia = _state.value.encyclopedia?.copy(name = trimmed, updatedAt = now),
                        renameDraft = null,
                    )
                    showSnackbar("已更新百科名称")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pageRevision == targetPage) _state.value = _state.value.copy(renameError = "名称保存失败，请重试")
            } finally {
                if (pageRevision == targetPage) _state.value = _state.value.copy(renameSaving = false)
            }
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

    suspend fun relationOptions(query: String, afterId: Long): List<com.mojing.app.data.local.dao.EncyclopediaEntryOption> =
        entryDao.getRelationOptions(encId, afterId, query.trim())

    fun setSedimentFilter(filter: String) {
        if (filter !in listOf("all", "pending", "confirmed") || filter == _state.value.sedimentFilter || _state.value.sedimentConfirming) return
        _state.value = _state.value.copy(sedimentFilter = filter, sedimentCursors = listOf(Long.MAX_VALUE))
        reloadSediment()
    }

    fun nextSedimentPage() {
        val state = _state.value
        if (state.sedimentLoading || state.sedimentConfirming || state.sedimentError != null || !state.sedimentHasNext) return
        val cursor = state.sedimentEntries.lastOrNull()?.id ?: return
        _state.value = state.copy(sedimentCursors = state.sedimentCursors + cursor)
        reloadSediment()
    }

    fun previousSedimentPage() {
        val state = _state.value
        if (state.sedimentLoading || state.sedimentConfirming || state.sedimentCursors.size < 2) return
        _state.value = state.copy(sedimentCursors = state.sedimentCursors.dropLast(1))
        reloadSediment()
    }

    fun reloadSediment() {
        if (!_state.value.isLoaded || _state.value.encyclopedia == null) return
        sedimentJob?.cancel()
        val revision = ++sedimentRevision
        val id = encId
        val filter = _state.value.sedimentFilter
        val cursor = _state.value.sedimentCursors.last()
        savedStateHandle["sediment_filter_$id"] = filter
        savedStateHandle["sediment_cursors_$id"] = _state.value.sedimentCursors.toLongArray()
        _state.value = _state.value.copy(sedimentLoading = true, sedimentError = null, sedimentEntries = emptyList())
        sedimentJob = viewModelScope.launch {
            try {
                val rows = entryDao.getSedimentPage(id, cursor, filter)
                val total = entryDao.countSediment(id, false)
                val confirmed = entryDao.countSediment(id, true)
                if (id != encId || revision != sedimentRevision) return@launch
                _state.value = _state.value.copy(sedimentEntries = rows.take(100), sedimentHasNext = rows.size > 100,
                    sedimentTotal = total, sedimentConfirmed = confirmed, sedimentLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (id == encId && revision == sedimentRevision) _state.value = _state.value.copy(
                    sedimentLoading = false, sedimentError = "资料读取失败，请重试")
            }
        }
    }

    fun confirmSedimentEntries(ids: Set<Long>, onConfirmed: () -> Unit = {}) {
        if (_state.value.sedimentConfirming || !_state.value.isLoaded) return
        val targetId = encId
        val revision = loadRevision
        val selected = _state.value.sedimentEntries.filter { it.id in ids && it.confidence != "confirmed" }.map { it.id }.distinct()
        if (selected.isEmpty()) return
        if (selected.size > 100) { showSnackbar("每次最多确认 100 条资料"); return }
        _state.value = _state.value.copy(sedimentConfirming = true)
        viewModelScope.launch {
            var committed = false
            try {
                val count = entryDao.confirmSedimentEntries(targetId, selected, System.currentTimeMillis())
                committed = true
                if (encId != targetId || loadRevision != revision) return@launch
                onConfirmed()
                refreshEntries()
                if (encId != targetId || loadRevision != revision) return@launch
                refreshTimelineAndRelations()
                if (encId == targetId && loadRevision == revision) showSnackbar("已确认 $count 条资料")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (encId == targetId && loadRevision == revision) showSnackbar(
                    if (committed) "确认已保存，刷新失败，请重新打开百科" else "确认失败，选择已保留，请重试",
                )
            } finally { _state.value = _state.value.copy(sedimentConfirming = false) }
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

    fun reloadEntryPage() {
        _state.value = _state.value.copy(entriesLoading = true)
        viewModelScope.launch { refreshEntries() }
    }

    fun nextEntryPage() {
        val state = _state.value
        if (state.entriesLoading || state.entriesError != null || !state.entriesHasNext) return
        val cursor = state.entries.lastOrNull()?.id ?: return
        _state.value = state.copy(entryCursors = state.entryCursors + cursor, previewEntryId = null)
        reloadEntryPage()
    }

    fun previousEntryPage() {
        val state = _state.value
        if (state.entriesLoading || state.entryCursors.size < 2) return
        _state.value = state.copy(entryCursors = state.entryCursors.dropLast(1), previewEntryId = null)
        reloadEntryPage()
    }

    private suspend fun refreshEntries() {
        val id = encId
        val revision = ++entriesRevision
        val type = _state.value.selectedType
        val cursor = _state.value.entryCursors.last()
        savedStateHandle["entry_type_$id"] = type
        savedStateHandle["entry_cursors_$id"] = _state.value.entryCursors.toLongArray()
        fun current() = revision == entriesRevision && id == encId && type == _state.value.selectedType && cursor == _state.value.entryCursors.last()
        _state.value = _state.value.copy(entriesLoading = true, entriesError = null, entries = emptyList())
        try {
            val rows = entryDao.getEntryPage(id, cursor, type)
            val count = entryDao.countEntries(id, type)
            if (!current()) return
            val entries = rows.take(100)
            _state.value = _state.value.copy(entries = entries, entriesLoading = false, entriesHasNext = rows.size > 100,
                filteredEntryCount = count, previewEntryId = _state.value.previewEntryId?.takeIf { preview -> entries.any { it.id == preview } })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (current()) _state.value = _state.value.copy(entriesLoading = false, entriesError = "条目读取失败，请重试") }
    }

    private suspend fun loadRelationTitles(id: Long, relations: List<com.mojing.app.data.local.entity.EntryRelationEntity>): Map<Long, String> {
        val ids = relations.flatMap { listOf(it.fromEntryId, it.toEntryId) }.distinct()
        return ids.chunked(900).flatMap { entryDao.getEntryOptionsByIds(id, it) }.associate { it.id to it.title }
    }

    private suspend fun refreshTimelineAndRelations() {
        val id = encId
        val revision = ++relationsRevision
        val events = timelineEventDao.getByEncyclopedia(id)
        val rels = entryRelationDao.getByEncyclopedia(id)
        val titles = loadRelationTitles(id, rels)
        val total = entryDao.countEntries(id, "")
        if (revision != relationsRevision || id != encId) return
        _state.value = _state.value.copy(
            timelineEvents = events,
            relations = rels,
            entryTitles = titles,
            entryCount = total,
        )
        reloadSediment()
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

    fun clearRelationError() {
        _state.value = _state.value.copy(relationError = null)
    }

    fun addRelation(fromEntryId: Long, toEntryId: Long, relationType: String, label: String, onSaved: () -> Unit = {}) {
        if (_state.value.relationSaving) return
        if (fromEntryId == toEntryId) {
            _state.value = _state.value.copy(relationError = UserFacingStrings.relationEndpointsMustDiffer())
            return
        }
        val targetId = encId
        val targetPage = pageRevision
        _state.value = _state.value.copy(relationSaving = true, relationError = null)
        viewModelScope.launch {
            val saved = try {
                entryRelationDao.upsert(
                    EntryRelationEntity(
                        encyclopediaId = targetId,
                        fromEntryId = fromEntryId,
                        toEntryId = toEntryId,
                        relationType = relationType.ifBlank { "关联" },
                        label = label.trim(),
                    )
                )
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pageRevision == targetPage) {
                    _state.value = _state.value.copy(relationError = "关系保存失败，输入已保留，请重试")
                }
                false
            } finally {
                _state.value = _state.value.copy(relationSaving = false)
            }
            if (!saved || pageRevision != targetPage) return@launch
            onSaved()
            try {
                refreshTimelineAndRelations()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pageRevision == targetPage) showSnackbar("关系已保存，列表刷新失败，请重新打开百科")
            }
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
     * 对当前分类中的每一条，用设置里的文本模型补全 **扩展 meta** 中空缺键（不覆盖已有非空内容）。
     */
    fun batchAiFillMetaForCurrentEntries() {
        if (secureStorage.publicApiKey.isBlank()) {
            showSnackbar(UserFacingStrings.llmKeyMissingForAiComplete())
            return
        }
        if (_state.value.filteredEntryCount == 0) {
            showSnackbar("当前列表没有条目")
            return
        }
        if (_state.value.isEncyclopediaMetaFillQueued) {
            showSnackbar("已有扩展字段补全任务在队列中")
            return
        }
        val enc = _state.value.encyclopedia ?: return
        val type = _state.value.selectedType
        _state.value = _state.value.copy(metaFillSubmitting = true)
        viewModelScope.launch {
            try {
                val ids = entryDao.getEntryIdsForType(enc.id, type)
                if (ids.isEmpty()) { if (encId == enc.id) showSnackbar("当前分类没有条目"); return@launch }
                generationQueueProcessor.enqueueEncyclopediaMetaFill(
                    encyclopediaId = enc.id,
                    encyclopediaName = enc.name.ifBlank { "百科" },
                    entryIds = ids,
                )
                val n = generationQueueProcessor.countActiveTasks()
                if (encId == enc.id) showSnackbar("已加入队列（当前共 $n 个在跑）。补全完会写入各条目并刷新本页；全部结束时会有提示。点顶部云图标可看进度。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (encId == enc.id) showSnackbar("补全任务创建失败，请重试") }
            finally { _state.value = _state.value.copy(metaFillSubmitting = false) }
        }
    }
}
