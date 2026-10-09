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
import com.mojing.app.data.local.dao.EncyclopediaEntryListItem
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.EntryRelationEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.data.local.entity.WorldEntryTypeCount
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

data class TimelinePageCursor(val sortOrder: Int, val id: Long)

private const val WORLD_DETAIL_PAGE_SIZE = 24
private val TIMELINE_SOURCES = setOf("", "standalone", "linked")
private val RELATION_ENTRY_TYPES = setOf("", "character", "faction", "location")
private const val SAVED_RELATION_CURSOR_LIMIT = 128

data class EncyclopediaDetailState(
    val encyclopedia: EncyclopediaEntity? = null,
    val entries: List<EncyclopediaEntryListItem> = emptyList(),
    val selectedType: String = "",
    /** 宽屏「条目」Tab 右侧预览所选条目 id */
    val previewEntryId: Long? = null,
    val previewEntry: EncyclopediaEntryEntity? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val entryCursors: List<Long> = listOf(0L),
    val entriesHasNext: Boolean = false,
    val entriesLoading: Boolean = false,
    val entriesError: String? = null,
    val entryDeletingId: Long? = null,
    val entryDeleteError: String? = null,
    val filteredEntryCount: Int = 0,
    val metaFillSubmitting: Boolean = false,
    /** 当前百科下进行中的生成任务（用于进度条） */
    val activeGenTasks: List<GenerationTaskEntity> = emptyList(),
    val mainTab: EncyclopediaMainTab = EncyclopediaMainTab.ENTRIES,
    val timelineSource: String = "",
    val timelineEvents: List<TimelineEventEntity> = emptyList(),
    val timelineCursors: List<TimelinePageCursor?> = listOf(null),
    val timelinePageIndex: Int = 0,
    val timelineHasNext: Boolean = false,
    val timelineLoading: Boolean = false,
    val timelineLoaded: Boolean = false,
    val timelineError: String? = null,
    val timelineRetryIndex: Int? = null,
    val timelineRetryCursor: TimelinePageCursor? = null,
    val timelineMaxSortOrder: Int = -1,
    val timelineSaving: Boolean = false,
    val timelineSaveError: String? = null,
    val timelineDeletingId: Long? = null,
    val timelineDeleteError: String? = null,
    val relationAnchor: com.mojing.app.data.local.dao.EncyclopediaEntryOption? = null,
    val relations: List<EntryRelationEntity> = emptyList(),
    val relationTypeFilter: String = "",
    val relationCursors: List<Long> = listOf(Long.MAX_VALUE),
    val relationPageIndex: Int = 0,
    val relationsHasNext: Boolean = false,
    val relationsLoading: Boolean = false,
    val relationsLoaded: Boolean = false,
    val relationsLoadError: String? = null,
    val relationRetryIndex: Int? = null,
    val relationRetryCursor: Long = Long.MAX_VALUE,
    val relationDeletingId: Long? = null,
    val relationDeleteError: String? = null,
    val relationEndpoints: Map<Long, com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint> = emptyMap(),
    val entryCount: Int = 0,
    /** 聚合统计由 Room projection 提供；详情页不从分页结果推导。 */
    val overviewStats: List<WorldOverviewStat> = emptyList(),
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
    val entryCreating: Boolean = false,
    val createEntryError: String? = null,
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
    private var previewRevision = 0L
    private var previewJob: Job? = null
    private var timelineJob: Job? = null
    private var timelineRevision = 0L
    private var timelineSaveInFlight = false
    private var relationsRevision = 0L
    // 不同百科切换创建新的页面归属；同百科刷新沿用当前写入 owner。
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

    fun load(id: Long, relationEntryId: Long = 0L) {
        val renameState = _state.value.takeIf { encId == id }
        cancelPreviewRead()
        if (encId != id) {
            hadActiveGenerationForEnc = false
            pageRevision++
        }
        val requestRevision = ++loadRevision
        entriesRevision++
        timelineJob?.cancel()
        timelineRevision++
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
            mainTab = if (relationEntryId > 0L) EncyclopediaMainTab.GRAPH else renameState?.mainTab
                ?: savedStateHandle.get<String>("world_tab_$id")?.let { name -> EncyclopediaMainTab.entries.firstOrNull { it.name == name } }
                ?: EncyclopediaMainTab.ENTRIES,
            relationTypeFilter = renameState?.relationTypeFilter ?: savedStateHandle.get<String>("relation_type_$id")?.takeIf { it in RELATION_ENTRY_TYPES }.orEmpty(),
            relationCursors = renameState?.relationCursors ?: restoredRelationCursors(id),
            relationPageIndex = renameState?.relationPageIndex ?: (restoredRelationCursors(id).size - 1),
            timelineSource = renameState?.timelineSource ?: savedStateHandle.get<String>("timeline_source_$id")?.takeIf { it in TIMELINE_SOURCES }.orEmpty(),
            timelineCursors = renameState?.timelineCursors ?: restoredTimelineCursors(id),
            timelinePageIndex = renameState?.timelinePageIndex ?: (restoredTimelineCursors(id).size - 1),
            selectedType = renameState?.selectedType ?: savedStateHandle.get<String>("entry_type_$id").orEmpty(),
            entryCursors = renameState?.entryCursors ?: savedStateHandle.get<LongArray>("entry_cursors_$id")?.toList()
                ?.takeIf { it.firstOrNull() == 0L && it.all { c -> c >= 0 } && it.zipWithNext().all { pair -> pair.first < pair.second } } ?: listOf(0L),
            entryDeletingId = renameState?.entryDeletingId,
            entryDeleteError = renameState?.entryDeleteError,
            timelineDeletingId = renameState?.timelineDeletingId,
            timelineDeleteError = renameState?.timelineDeleteError,
            relationDeletingId = renameState?.relationDeletingId,
            relationDeleteError = renameState?.relationDeleteError,
            metaFillSubmitting = _state.value.metaFillSubmitting,
            previewEntryId = renameState?.previewEntryId,
            isLoaded = false,
            hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
            renameDraft = renameState?.renameDraft,
            renameSaving = renameState?.renameSaving ?: false,
            renameError = renameState?.renameError,
            relationSaving = renameState?.relationSaving ?: false,
            timelineSaving = timelineSaveInFlight,
            relationError = renameState?.relationError,
            entryCreating = _state.value.entryCreating,
            createEntryError = renameState?.createEntryError,
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
                val anchor = if (relationEntryId > 0L) entryDao.getEntryOptionsByIds(id, listOf(relationEntryId)).firstOrNull() else null
                val type = _state.value.selectedType
                val cursor = _state.value.entryCursors.last()
                val rows = entryDao.getEntryListPage(id, cursor, type)
                val entries = rows.take(100)
                val total = entryDao.countEntries(id, "")
                val filteredCount = entryDao.countEntries(id, type)
                val typeCounts = entryDao.getWorldTypeCounts(id)
                if (requestRevision != loadRevision) return@launch
                val current = _state.value.encyclopedia
                val visibleEntries = entries
                _state.value = _state.value.copy(
                    encyclopedia = if (nameRevision != initialNameRevision && current?.id == id) {
                        encyclopedia.copy(name = current.name, updatedAt = current.updatedAt)
                    } else encyclopedia,
                    relationAnchor = anchor,
                    entries = visibleEntries,
                    entriesHasNext = rows.size > 100,
                    filteredEntryCount = filteredCount,
                    previewEntryId = _state.value.previewEntryId?.takeIf { previewId ->
                        visibleEntries.any { it.id == previewId }
                    },
                    entryCount = total,
                    overviewStats = overviewStatsFrom(typeCounts, total),
                    isLoaded = true,
                    loadError = null,
                )
                _state.value.previewEntryId?.let { setPreviewEntry(it) }
                reloadSediment()
                startGenerationObservation(id)
                when (_state.value.mainTab) {
                    EncyclopediaMainTab.TIMELINE -> loadTimelinePage(_state.value.timelinePageIndex,
                        _state.value.timelineCursors[_state.value.timelinePageIndex])
                    EncyclopediaMainTab.GRAPH -> loadRelationPage(_state.value.relationPageIndex,
                        _state.value.relationCursors[_state.value.relationPageIndex])
                    else -> Unit
                }
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

    private fun overviewStatsFrom(typeCounts: List<WorldEntryTypeCount>, total: Int): List<WorldOverviewStat> {
        val counts = typeCounts.associate { it.type to it.count }
        return listOf(
            WorldOverviewStat("条目", total),
            WorldOverviewStat("主要人物", counts["character"] ?: 0),
            WorldOverviewStat("主要地点", counts["location"] ?: 0),
            WorldOverviewStat("主要势力", counts["faction"] ?: 0),
        )
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
        if (_state.value.mainTab == tab) return
        timelineJob?.cancel()
        timelineRevision++
        relationsRevision++
        _state.value = _state.value.copy(mainTab = tab, timelineLoading = false, relationsLoading = false)
        savedStateHandle["world_tab_$encId"] = tab.name
        when (tab) {
            EncyclopediaMainTab.TIMELINE, EncyclopediaMainTab.GRAPH -> viewModelScope.launch { refreshTimelineAndRelations() }
            EncyclopediaMainTab.SEDIMENT -> reloadSediment()
            EncyclopediaMainTab.ENTRIES -> Unit
        }
    }

    fun selectType(type: String) {
        if (!_state.value.isLoaded || _state.value.loadError != null) return
        if (type == _state.value.selectedType) return
        setPreviewEntry(null)
        _state.value = _state.value.copy(selectedType = type, entryCursors = listOf(0L))
        reloadEntryPage()
    }

    fun setPreviewEntry(id: Long?) {
        cancelPreviewRead()
        val selected = id?.takeIf { candidate -> _state.value.entries.any { it.id == candidate && it.encyclopediaId == encId } }
        _state.value = _state.value.copy(previewEntryId = selected, previewEntry = null,
            previewLoading = selected != null, previewError = null)
        if (selected == null) return
        val worldId = encId
        val revision = previewRevision
        val owner = pageRevision
        fun current() = revision == previewRevision && owner == pageRevision && worldId == encId &&
            _state.value.previewEntryId == selected && _state.value.entries.any { it.id == selected }
        previewJob = viewModelScope.launch {
            try {
                val entry = entryDao.getById(selected)
                if (!current()) return@launch
                if (entry == null || entry.encyclopediaId != worldId || entry.id != selected) {
                    _state.value = _state.value.copy(previewLoading = false, previewError = "条目已不可用，请刷新列表")
                } else {
                    _state.value = _state.value.copy(previewEntry = entry, previewLoading = false, previewError = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) _state.value = _state.value.copy(previewLoading = false, previewError = "预览读取失败，请重试")
            }
        }
    }

    fun retryEntryPreview() { setPreviewEntry(_state.value.previewEntryId) }

    private fun cancelPreviewRead() {
        previewRevision++
        previewJob?.cancel()
        previewJob = null
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

    fun clearCreateEntryError() {
        _state.value = _state.value.copy(createEntryError = null)
    }

    fun createEntry(title: String, type: String, onCreated: (Long) -> Unit = {}) {
        if (_state.value.entryCreating) return
        if (title.isBlank()) {
            _state.value = _state.value.copy(createEntryError = "请先填写条目标题")
            return
        }
        val targetId = encId
        val targetPage = pageRevision
        _state.value = _state.value.copy(entryCreating = true, createEntryError = null)
        viewModelScope.launch {
            val created = try {
                saveCharacterEntry(
                    EncyclopediaEntryEntity(
                        encyclopediaId = targetId,
                        title = title.trim(),
                        entryType = type,
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pageRevision == targetPage) _state.value = _state.value.copy(createEntryError = "条目创建失败，标题已保留，请重试")
                null
            } finally {
                _state.value = _state.value.copy(entryCreating = false)
            } ?: return@launch
            if (pageRevision != targetPage) return@launch
            onCreated(created.id)
            try {
                refreshEntries()
                refreshTimelineAndRelations()
                showSnackbar(UserFacingStrings.entryCreatedListHint())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showSnackbar("条目已创建，列表刷新失败，请重新打开百科")
            }
        }
    }

    fun deleteEntry(id: Long, onResult: (Boolean) -> Unit = {}) {
        if (_state.value.entryDeletingId != null) return
        val targetId = encId
        val targetPage = pageRevision
        _state.value = _state.value.copy(entryDeletingId = id, entryDeleteError = null)
        viewModelScope.launch {
            try {
                val deleted = deleteEncyclopediaEntry(id, targetId)
                if (targetId != encId || targetPage != pageRevision) return@launch
                if (!deleted) {
                    _state.value = _state.value.copy(entryDeleteError = "条目已不存在或不属于当前百科，请重新读取")
                    onResult(false)
                    return@launch
                }
                refreshEntries()
                if (targetId != encId || targetPage != pageRevision) return@launch
                refreshTimelineAndRelations()
                if (targetId != encId || targetPage != pageRevision) return@launch
                onResult(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (targetId == encId && targetPage == pageRevision) {
                    _state.value = _state.value.copy(entryDeleteError = "删除失败，请重试")
                    onResult(false)
                }
            } finally {
                if (targetId == encId && targetPage == pageRevision) {
                    _state.value = _state.value.copy(entryDeletingId = null)
                }
            }
        }
    }

    fun clearEntryDeleteError() {
        _state.value = _state.value.copy(entryDeleteError = null)
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

    fun reloadSediment() = refreshSediment(keepCurrentRows = false)

    private fun refreshSediment(keepCurrentRows: Boolean) {
        if (!_state.value.isLoaded || _state.value.encyclopedia == null) return
        sedimentJob?.cancel()
        val revision = ++sedimentRevision
        val id = encId
        val filter = _state.value.sedimentFilter
        val cursor = _state.value.sedimentCursors.last()
        savedStateHandle["sediment_filter_$id"] = filter
        savedStateHandle["sediment_cursors_$id"] = _state.value.sedimentCursors.toLongArray()
        _state.value = _state.value.copy(sedimentLoading = true, sedimentError = null,
            sedimentEntries = if (keepCurrentRows) _state.value.sedimentEntries else emptyList())
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
        if (_state.value.sedimentConfirming || _state.value.sedimentLoading ||
            _state.value.sedimentError != null || !_state.value.isLoaded) return
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
                refreshTimelineAndRelations(preserveSedimentRows = true)
                if (_state.value.mainTab != EncyclopediaMainTab.SEDIMENT) refreshSediment(keepCurrentRows = true)
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
        setPreviewEntry(null)
        _state.value = _state.value.copy(entryCursors = state.entryCursors + cursor)
        reloadEntryPage()
    }

    fun previousEntryPage() {
        val state = _state.value
        if (state.entriesLoading || state.entryCursors.size < 2) return
        setPreviewEntry(null)
        _state.value = _state.value.copy(entryCursors = state.entryCursors.dropLast(1))
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
        cancelPreviewRead()
        _state.value = _state.value.copy(entriesLoading = true, entriesError = null, entries = emptyList(),
            previewEntry = null, previewLoading = false, previewError = null)
        try {
            val rows = entryDao.getEntryListPage(id, cursor, type)
            val count = entryDao.countEntries(id, type)
            val total = entryDao.countEntries(id, "")
            val typeCounts = entryDao.getWorldTypeCounts(id)
            if (!current()) return
            if (rows.isEmpty() && _state.value.entryCursors.size > 1) {
                val previousCursors = _state.value.entryCursors.dropLast(1)
                _state.value = _state.value.copy(entryCursors = previousCursors, previewEntryId = null)
                savedStateHandle["entry_cursors_$id"] = previousCursors.toLongArray()
                refreshEntries()
                return
            }
            val entries = rows.take(100)
            _state.value = _state.value.copy(entries = entries, entriesLoading = false, entriesHasNext = rows.size > 100,
                filteredEntryCount = count, entryCount = total,
                overviewStats = overviewStatsFrom(typeCounts, total),
                previewEntryId = _state.value.previewEntryId?.takeIf { preview -> entries.any { it.id == preview } })
            _state.value.previewEntryId?.let { setPreviewEntry(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (current()) _state.value = _state.value.copy(entriesLoading = false, entriesError = "条目读取失败，请重试") }
    }

    private suspend fun loadRelationEndpoints(id: Long, relations: List<EntryRelationEntity>): Map<Long, com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint> {
        val ids = relations.flatMap { listOf(it.fromEntryId, it.toEntryId) }.distinct()
        // A 24-edge page has at most 48 endpoints. Commit them with the page/revision.
        return if (ids.isEmpty()) emptyMap() else entryDao.getRelationEndpointsByIds(id, ids).associateBy { it.id }
    }

    private suspend fun refreshTimelineAndRelations(preserveSedimentRows: Boolean = false) {
        val id = encId
        val total = try { entryDao.countEntries(id, "") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val typeCounts = try { entryDao.getWorldTypeCounts(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        if (id != encId) return
        if (total != null) _state.value = _state.value.copy(
            entryCount = total,
            overviewStats = overviewStatsFrom(typeCounts, total),
        )
        when (_state.value.mainTab) {
            EncyclopediaMainTab.TIMELINE -> loadTimelinePage(_state.value.timelinePageIndex,
                _state.value.timelineCursors[_state.value.timelinePageIndex])
            EncyclopediaMainTab.GRAPH -> loadRelationPage(_state.value.relationPageIndex,
                _state.value.relationCursors[_state.value.relationPageIndex])
            EncyclopediaMainTab.SEDIMENT -> refreshSediment(keepCurrentRows = preserveSedimentRows)
            EncyclopediaMainTab.ENTRIES -> Unit
        }
    }

    private fun restoredTimelineCursors(id: Long): List<TimelinePageCursor?> {
        val source = savedStateHandle.get<String>("timeline_source_$id")
        if (source != null && source !in TIMELINE_SOURCES) return listOf(null)
        val pairs = savedStateHandle.get<LongArray>("timeline_cursors_$id") ?: return listOf(null)
        if (pairs.size % 2 != 0 || pairs.size > 254) return listOf(null)
        val cursors = pairs.toList().chunked(2)
        if (cursors.any { it[0] !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || it[1] <= 0 } ||
            cursors.zipWithNext().any { (a, b) -> a[0] > b[0] || (a[0] == b[0] && a[1] >= b[1]) }) return listOf(null)
        return listOf(null) + cursors.map { TimelinePageCursor(it[0].toInt(), it[1]) }
    }

    fun selectTimelineSource(source: String) {
        val state = _state.value
        if (source !in TIMELINE_SOURCES || source == state.timelineSource || !state.isLoaded ||
            state.loadError != null || state.timelineSaving || state.timelineDeletingId != null) return
        timelineJob?.cancel()
        timelineRevision++
        savedStateHandle["timeline_source_$encId"] = source
        savedStateHandle["timeline_cursors_$encId"] = longArrayOf()
        _state.value = state.copy(timelineSource = source, timelineEvents = emptyList(),
            timelineCursors = listOf(null), timelinePageIndex = 0, timelineHasNext = false,
            timelineLoaded = false, timelineLoading = false, timelineError = null,
            timelineRetryIndex = null, timelineRetryCursor = null)
        if (state.mainTab == EncyclopediaMainTab.TIMELINE) loadTimelinePage(0, null)
    }

    private fun loadTimelinePage(index: Int, cursor: TimelinePageCursor?) {
        val id = encId
        val source = _state.value.timelineSource
        timelineJob?.cancel()
        val revision = ++timelineRevision
        _state.value = _state.value.copy(timelineLoading = true, timelineError = null)
        timelineJob = viewModelScope.launch {
            try {
                val rows = timelineEventDao.getPage(id, cursor?.sortOrder, cursor?.id, WORLD_DETAIL_PAGE_SIZE + 1, source)
                val maxOrder = timelineEventDao.maxSortOrder(id)
                if (id != encId || revision != timelineRevision || source != _state.value.timelineSource || _state.value.mainTab != EncyclopediaMainTab.TIMELINE) return@launch
                if (rows.isEmpty() && index > 0) {
                    loadTimelinePage(index - 1, _state.value.timelineCursors[index - 1])
                    return@launch
                }
                _state.value = _state.value.copy(
                    timelineEvents = rows.take(WORLD_DETAIL_PAGE_SIZE),
                    timelineCursors = _state.value.timelineCursors.take(index) + cursor,
                    timelinePageIndex = index,
                    timelineHasNext = rows.size > WORLD_DETAIL_PAGE_SIZE,
                    timelineLoading = false,
                    timelineLoaded = true,
                    timelineError = null,
                    timelineRetryIndex = null,
                    timelineRetryCursor = null,
                    timelineMaxSortOrder = maxOrder,
                )
                // Save only bounded cursor identities; event bodies remain in Room.
                savedStateHandle["timeline_cursors_$id"] = if (_state.value.timelineCursors.size <= 128) {
                    _state.value.timelineCursors.filterNotNull().flatMap { listOf(it.sortOrder.toLong(), it.id) }.toLongArray()
                } else longArrayOf()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (id == encId && revision == timelineRevision && source == _state.value.timelineSource && _state.value.mainTab == EncyclopediaMainTab.TIMELINE) {
                    _state.value = _state.value.copy(timelineLoading = false,
                        timelineError = "时间线读取失败，当前页已保留", timelineRetryIndex = index,
                        timelineRetryCursor = cursor)
                }
            }
        }
    }

    fun nextTimelinePage() {
        val state = _state.value
        if (state.timelineLoading || state.timelineError != null || !state.timelineHasNext) return
        val last = state.timelineEvents.lastOrNull() ?: return
        viewModelScope.launch { loadTimelinePage(state.timelinePageIndex + 1, TimelinePageCursor(last.sortOrder, last.id)) }
    }

    fun previousTimelinePage() {
        val state = _state.value
        if (state.timelineLoading || state.timelinePageIndex == 0) return
        val index = state.timelinePageIndex - 1
        viewModelScope.launch { loadTimelinePage(index, state.timelineCursors[index]) }
    }

    fun retryTimelinePage() {
        val state = _state.value
        if (state.timelineLoading) return
        val index = state.timelineRetryIndex ?: state.timelinePageIndex
        val cursor = if (state.timelineRetryIndex != null) state.timelineRetryCursor else state.timelineCursors[index]
        viewModelScope.launch { loadTimelinePage(index, cursor) }
    }

    private fun restoredRelationCursors(id: Long): List<Long> {
        val type = savedStateHandle.get<String>("relation_type_$id")
        if (type != null && type !in RELATION_ENTRY_TYPES) return listOf(Long.MAX_VALUE)
        return savedStateHandle.get<LongArray>("relation_cursors_$id")?.toList()?.takeIf {
            it.size in 1..SAVED_RELATION_CURSOR_LIMIT && it.first() == Long.MAX_VALUE &&
                it.all { cursor -> cursor > 0 } && it.zipWithNext().all { pair -> pair.first > pair.second }
        } ?: listOf(Long.MAX_VALUE)
    }

    fun selectRelationType(type: String) {
        val state = _state.value
        if (type !in RELATION_ENTRY_TYPES || type == state.relationTypeFilter || !state.isLoaded ||
            state.loadError != null || state.relationSaving || state.relationDeletingId != null) return
        relationsRevision++
        savedStateHandle["relation_type_$encId"] = type
        savedStateHandle["relation_cursors_$encId"] = longArrayOf(Long.MAX_VALUE)
        _state.value = state.copy(relationTypeFilter = type, relations = emptyList(), relationEndpoints = emptyMap(),
            relationCursors = listOf(Long.MAX_VALUE), relationPageIndex = 0, relationsHasNext = false,
            relationsLoading = false, relationsLoaded = false, relationsLoadError = null,
            relationRetryIndex = null, relationRetryCursor = Long.MAX_VALUE)
        if (state.mainTab == EncyclopediaMainTab.GRAPH) viewModelScope.launch { loadRelationPage(0, Long.MAX_VALUE) }
    }

    private suspend fun loadRelationPage(index: Int, cursor: Long) {
        val id = encId
        val type = _state.value.relationTypeFilter
        val revision = ++relationsRevision
        fun ownsRead() = id == encId && revision == relationsRevision &&
            type == _state.value.relationTypeFilter && _state.value.mainTab == EncyclopediaMainTab.GRAPH
        _state.value = _state.value.copy(relationsLoading = true, relationsLoadError = null)
        try {
            val rows = if (type.isBlank()) entryRelationDao.getPage(id, cursor, WORLD_DETAIL_PAGE_SIZE + 1)
                else entryRelationDao.getTypePage(id, cursor, type, WORLD_DETAIL_PAGE_SIZE + 1)
            if (!ownsRead()) return
            val relations = rows.take(WORLD_DETAIL_PAGE_SIZE)
            val endpoints = loadRelationEndpoints(id, relations)
            if (!ownsRead()) return
            if (rows.isEmpty() && index > 0) {
                loadRelationPage(index - 1, _state.value.relationCursors[index - 1])
                return
            }
            _state.value = _state.value.copy(
                relations = relations,
                relationCursors = _state.value.relationCursors.take(index) + cursor,
                relationPageIndex = index,
                relationsHasNext = rows.size > WORLD_DETAIL_PAGE_SIZE,
                relationsLoading = false,
                relationsLoaded = true,
                relationsLoadError = null,
                relationRetryIndex = null,
                relationRetryCursor = Long.MAX_VALUE,
                relationEndpoints = endpoints,
            )
            savedStateHandle["world_tab_$id"] = EncyclopediaMainTab.GRAPH.name
            savedStateHandle["relation_type_$id"] = type
            // Bound SavedState payload; unusually deep walks restore the first page, never the wrong cursor.
            savedStateHandle["relation_cursors_$id"] = if (_state.value.relationCursors.size <= SAVED_RELATION_CURSOR_LIMIT)
                _state.value.relationCursors.toLongArray() else longArrayOf(Long.MAX_VALUE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (ownsRead()) {
                _state.value = _state.value.copy(relationsLoading = false,
                    relationsLoadError = "关系读取失败，当前页已保留", relationRetryIndex = index,
                    relationRetryCursor = cursor)
            }
        }
    }

    fun nextRelationPage() {
        val state = _state.value
        if (state.relationsLoading || state.relationsLoadError != null || !state.relationsHasNext) return
        val lastId = state.relations.lastOrNull()?.id ?: return
        viewModelScope.launch { loadRelationPage(state.relationPageIndex + 1, lastId) }
    }

    fun previousRelationPage() {
        val state = _state.value
        if (state.relationsLoading || state.relationPageIndex == 0) return
        val index = state.relationPageIndex - 1
        viewModelScope.launch { loadRelationPage(index, state.relationCursors[index]) }
    }

    fun retryRelationPage() {
        val state = _state.value
        if (state.relationsLoading) return
        val index = state.relationRetryIndex ?: state.relationPageIndex
        val cursor = if (state.relationRetryIndex != null) state.relationRetryCursor else state.relationCursors[index]
        viewModelScope.launch { loadRelationPage(index, cursor) }
    }

    fun clearTimelineSaveError() {
        _state.value = _state.value.copy(timelineSaveError = null)
    }

    private fun timelineDraftKey(worldId: Long, eventId: Long?, field: String): String =
        "timeline_draft_${worldId}_${eventId ?: 0L}_$field"

    fun timelineDraft(eventId: Long?, field: String, fallback: String = ""): String =
        savedStateHandle[timelineDraftKey(encId, eventId, field)] ?: fallback

    fun saveTimelineDraft(eventId: Long?, field: String, value: String) {
        savedStateHandle[timelineDraftKey(encId, eventId, field)] = value
    }

    fun discardTimelineDraft(eventId: Long?) = discardTimelineDraft(encId, eventId)

    private fun discardTimelineDraft(worldId: Long, eventId: Long?) {
        listOf("title", "description", "time", "order").forEach { field ->
            savedStateHandle.remove<String>(timelineDraftKey(worldId, eventId, field))
        }
    }

    private fun clearSubmittedTimelineDraft(worldId: Long, eventId: Long?, title: String,
        description: String, timeLabel: String, sortOrder: Int) {
        val unchanged = mapOf("title" to title, "description" to description, "time" to timeLabel).all { (field, submitted) ->
            savedStateHandle.get<String>(timelineDraftKey(worldId, eventId, field))?.let { it == submitted } ?: true
        }
        val order = savedStateHandle.get<String>(timelineDraftKey(worldId, eventId, "order"))
        if (unchanged && (order == null || order.trim().toIntOrNull() == sortOrder)) {
            discardTimelineDraft(worldId, eventId)
        }
    }

    fun addTimelineEvent(title: String, description: String, timeLabel: String, sortOrder: Int,
        onSaved: (Boolean) -> Unit = {}) =
        saveTimelineEvent(null, title, description, timeLabel, sortOrder, onSaved)

    fun addTimelineEvent(title: String, timeLabel: String, sortOrder: Int, onSaved: (Boolean) -> Unit = {}) =
        addTimelineEvent(title, "", timeLabel, sortOrder, onSaved)

    fun updateTimelineEvent(eventId: Long, title: String, description: String, timeLabel: String, sortOrder: Int,
        onSaved: (Boolean) -> Unit = {}) =
        saveTimelineEvent(eventId, title, description, timeLabel, sortOrder, onSaved)

    private fun saveTimelineEvent(eventId: Long?, title: String, description: String, timeLabel: String,
        sortOrder: Int, onSaved: (Boolean) -> Unit) {
        // A page reload must not release the owner of an in-flight database write.
        if (timelineSaveInFlight) return
        if (title.isBlank()) {
            _state.value = _state.value.copy(timelineSaveError = UserFacingStrings.timelineTitleRequired())
            onSaved(false)
            return
        }
        val targetId = encId
        val targetPage = pageRevision
        val targetLoad = loadRevision
        fun ownsPage() = targetId == encId && targetPage == pageRevision && targetLoad == loadRevision
        timelineSaveInFlight = true
        _state.value = _state.value.copy(timelineSaving = true, timelineSaveError = null)
        viewModelScope.launch {
            var committed = false
            try {
                if (eventId == null) {
                    timelineEventDao.upsert(TimelineEventEntity(encyclopediaId = targetId,
                        title = title.trim(), description = description.trim(),
                        eventTime = timeLabel.trim(), sortOrder = sortOrder))
                } else if (timelineEventDao.updateStandalone(eventId, targetId, title.trim(), description.trim(), timeLabel.trim(), sortOrder) != 1) {
                    if (ownsPage()) {
                        _state.value = _state.value.copy(timelineSaveError = "事件已不存在或不可编辑，请重新读取时间线")
                        onSaved(false)
                    }
                    return@launch
                }
                committed = true
                // Commit belongs to the captured world even if the visible world changed.
                clearSubmittedTimelineDraft(targetId, eventId, title, description, timeLabel, sortOrder)
                if (!ownsPage()) return@launch
                onSaved(true)
                refreshTimelineAndRelations()
                if (ownsPage()) showSnackbar("事件已保存，可按排序翻页查看")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ownsPage()) {
                    if (committed) showSnackbar("事件已保存，列表刷新失败，请重试读取")
                    else {
                        _state.value = _state.value.copy(timelineSaveError = "事件保存失败，输入已保留，请重试")
                        onSaved(false)
                    }
                }
            } finally {
                timelineSaveInFlight = false
                _state.value = _state.value.copy(timelineSaving = false)
            }
        }
    }

    fun clearTimelineDeleteError() {
        _state.value = _state.value.copy(timelineDeleteError = null)
    }

    fun deleteTimelineEvent(id: Long, onResult: (Boolean) -> Unit = {}) {
        if (_state.value.timelineDeletingId != null) return
        val targetId = encId
        val targetPage = pageRevision
        fun ownsPage() = targetId == encId && targetPage == pageRevision
        _state.value = _state.value.copy(timelineDeletingId = id, timelineDeleteError = null)
        viewModelScope.launch {
            var committed = false
            try {
                timelineEventDao.delete(id)
                committed = true
                discardTimelineDraft(targetId, id)
                if (!ownsPage()) return@launch
                onResult(true)
                refreshTimelineAndRelations()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ownsPage()) {
                    if (committed) showSnackbar("事件已删除，列表刷新失败，请重试读取")
                    else {
                        _state.value = _state.value.copy(timelineDeleteError = "删除失败，请重试")
                        onResult(false)
                    }
                }
            } finally {
                if (ownsPage()) {
                    _state.value = _state.value.copy(timelineDeletingId = null)
                }
            }
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
            var rejectedByEndpointScope = false
            val saved = try {
                val committed = entryRelationDao.upsertIfEndpointsBelongToEncyclopedia(
                    EntryRelationEntity(
                        encyclopediaId = targetId,
                        fromEntryId = fromEntryId,
                        toEntryId = toEntryId,
                        relationType = relationType.ifBlank { "关联" },
                        label = label.trim(),
                    )
                )
                rejectedByEndpointScope = !committed
                committed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pageRevision == targetPage) {
                    _state.value = _state.value.copy(relationError = "关系保存失败，输入已保留，请重试")
                }
                false
            } finally {
                if (encId == targetId && pageRevision == targetPage) {
                    _state.value = _state.value.copy(relationSaving = false)
                }
            }
            if (encId != targetId || pageRevision != targetPage) return@launch
            if (!saved) {
                if (rejectedByEndpointScope) {
                    _state.value = _state.value.copy(relationError = "两个端点必须属于当前百科，输入已保留，请重新选择")
                }
                return@launch
            }
            onSaved()
            try {
                if (_state.value.mainTab == EncyclopediaMainTab.GRAPH) {
                    val state = _state.value
                    loadRelationPage(state.relationPageIndex, state.relationCursors[state.relationPageIndex])
                    if (_state.value.relationsLoadError == null) showSnackbar("关系已保存，可按当前分类翻页查看")
                } else refreshTimelineAndRelations()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pageRevision == targetPage) showSnackbar("关系已保存，列表刷新失败，请重新打开百科")
            }
        }
    }

    fun clearRelationDeleteError() {
        _state.value = _state.value.copy(relationDeleteError = null)
    }

    fun deleteRelation(id: Long, onResult: (Boolean) -> Unit = {}) {
        if (_state.value.relationDeletingId != null) return
        val targetId = encId
        val targetPage = pageRevision
        _state.value = _state.value.copy(relationDeletingId = id, relationDeleteError = null)
        fun ownsPage() = targetId == encId && targetPage == pageRevision
        viewModelScope.launch {
            var committed = false
            try {
                entryRelationDao.delete(id)
                committed = true
                if (!ownsPage()) return@launch
                _state.value = _state.value.copy(relations = _state.value.relations.filterNot { it.id == id })
                onResult(true)
                if (_state.value.mainTab == EncyclopediaMainTab.GRAPH) {
                    val state = _state.value
                    loadRelationPage(state.relationPageIndex, state.relationCursors[state.relationPageIndex])
                    if (_state.value.relationsLoadError != null) showSnackbar("关系已删除，列表刷新失败，请重试读取")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ownsPage()) {
                    if (committed) showSnackbar("关系已删除，列表刷新失败，请重试读取")
                    else {
                        _state.value = _state.value.copy(relationDeleteError = "删除失败，请重试")
                        onResult(false)
                    }
                }
            } finally {
                if (ownsPage()) {
                    _state.value = _state.value.copy(relationDeletingId = null)
                }
            }
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
