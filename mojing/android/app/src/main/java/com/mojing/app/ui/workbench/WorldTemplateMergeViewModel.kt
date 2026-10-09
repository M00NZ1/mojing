package com.mojing.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaFilterOption
import com.mojing.app.data.local.dao.LegacyWorldMappingDao
import com.mojing.app.domain.usecase.MergeWorldTemplateUseCase
import com.mojing.app.domain.usecase.PromoteWorldTemplateUseCase
import com.mojing.app.domain.usecase.WorldMergeField
import com.mojing.app.domain.usecase.WorldTemplateMergeLoreRow
import com.mojing.app.domain.usecase.WorldTemplateMergePreview
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class WorldTemplateMergeState(
    val templateId: Long? = null,
    val searchQuery: String = "",
    val worlds: List<EncyclopediaFilterOption> = emptyList(),
    val worldsLoading: Boolean = false,
    val worldsLoaded: Boolean = false,
    val worldsPage: Int = 0,
    val worldsHasNext: Boolean = false,
    val worldsError: String? = null,
    val mappingError: String? = null,
    val selectedWorldId: Long? = null,
    val preview: WorldTemplateMergePreview? = null,
    val previewError: String? = null,
    val lore: List<WorldTemplateMergeLoreRow> = emptyList(),
    val loreLoading: Boolean = false,
    val loreHasNext: Boolean = false,
    val loreError: String? = null,
    val lorePageIndex: Int = 0,
    val loreHasPrevious: Boolean = false,
    val pendingLorePage: Int? = null,
    val previewValid: Boolean = false,
    val selectedFields: Set<WorldMergeField> = emptySet(),
    val applying: Boolean = false,
    val applyError: String? = null,
)

/** Owns all asynchronous work for the merge sheet; no preview can outlive its target selection. */
@HiltViewModel
class WorldTemplateMergeViewModel @Inject constructor(
    private val encyclopediaDao: EncyclopediaDao,
    private val legacyWorldMappingDao: LegacyWorldMappingDao,
    private val mergeWorldTemplate: MergeWorldTemplateUseCase,
    private val promoteWorldTemplate: PromoteWorldTemplateUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(WorldTemplateMergeState())
    val state: StateFlow<WorldTemplateMergeState> = _state.asStateFlow()

    private val revision = AtomicInteger(0)
    private val jobs = mutableSetOf<Job>()
    private val pageCursors = mutableListOf<EncyclopediaFilterOption?>(null)
    private var worldsPage = 0
    private var pendingWorldPage = 0
    private val loreAfterIds = mutableListOf(0L)

    suspend fun start(templateId: Long) {
        cancelAndJoin()
        pageCursors.clear()
        pageCursors += null
        worldsPage = 0
        _state.value = WorldTemplateMergeState(templateId = templateId, worldsLoading = true)
        loadWorlds(0)
        val startRevision = revision.get()
        loreAfterIds.clear(); loreAfterIds += 0L
        // A previously promoted template always points back to its canonical world.
        // It must not be silently promoted into a newly selected duplicate.
        launchTracked {
            try {
                val mappedWorldId = legacyWorldMappingDao.getByTemplateId(templateId)?.encyclopediaId ?: return@launchTracked
                if (_state.value.templateId == templateId && revision.get() == startRevision && _state.value.selectedWorldId == null) {
                    selectWorldInternal(mappedWorldId, cancelCurrent = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                if (_state.value.templateId == templateId && revision.get() == startRevision) _state.update { it.copy(mappingError = cause.message ?: "旧世界映射读取失败，请重试") }
            }
        }
    }

    fun setSearchQuery(query: String) {
        val normalized = query.trim()
        if (normalized == _state.value.searchQuery || _state.value.applying) return
        releaseOperations()
        pageCursors.clear()
        pageCursors += null
        worldsPage = 0
        _state.update { it.copy(searchQuery = normalized, worlds = emptyList(), worldsLoaded = false, worldsHasNext = false, worldsError = null, mappingError = null, selectedWorldId = null, preview = null, previewError = null, lore = emptyList(), selectedFields = emptySet(), previewValid = false) }
        loadWorlds(0)
    }

    fun nextWorldPage() {
        val s = _state.value
        if (s.worldsLoading || s.applying || !s.worldsHasNext) return
        val cursor = s.worlds.lastOrNull() ?: return
        if (pageCursors.size <= worldsPage + 1) pageCursors += cursor
        loadWorlds(worldsPage + 1)
    }

    fun previousWorldPage() {
        val s = _state.value
        if (s.worldsLoading || s.applying || worldsPage == 0) return
        loadWorlds(worldsPage - 1)
    }

    fun retryWorlds() = loadWorlds(pendingWorldPage)

    fun retryMapping() {
        val templateId = _state.value.templateId ?: return
        val requestRevision = revision.get()
        _state.update { it.copy(mappingError = null) }
        launchTracked {
            try {
                val mappedWorldId = legacyWorldMappingDao.getByTemplateId(templateId) ?: return@launchTracked
                if (_state.value.templateId == templateId && revision.get() == requestRevision && _state.value.selectedWorldId == null) {
                    selectWorldInternal(mappedWorldId.encyclopediaId, cancelCurrent = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                if (_state.value.templateId == templateId && revision.get() == requestRevision) _state.update { it.copy(mappingError = cause.message ?: "旧世界映射读取失败，请重试") }
            }
        }
    }

    fun clearPreview() {
        if (_state.value.applying) return
        releaseOperations()
        loreAfterIds.clear(); loreAfterIds += 0L
        _state.update { it.copy(selectedWorldId = null, preview = null, previewError = null, lore = emptyList(), loreLoading = false, loreError = null, applyError = null, selectedFields = emptySet(), previewValid = false) }
        if (_state.value.worldsLoading || !_state.value.worldsLoaded) loadWorlds(worldsPage)
    }

    fun selectWorld(worldId: Long) {
        selectWorldInternal(worldId, cancelCurrent = true)
    }

    private fun selectWorldInternal(worldId: Long, cancelCurrent: Boolean) {
        val templateId = _state.value.templateId ?: return
        if (_state.value.applying) return
        if (cancelCurrent) releaseOperations()
        val operationRevision = revision.incrementAndGet()
        loreAfterIds.clear(); loreAfterIds += 0L
        _state.update { it.copy(selectedWorldId = worldId, preview = null, previewError = null, lore = emptyList(), loreLoading = true, loreHasNext = false, loreError = null, applyError = null, selectedFields = emptySet(), previewValid = false) }
        launchTracked {
            try {
                val preview = mergeWorldTemplate.preview(templateId, worldId)
                if (operationRevision != revision.get()) return@launchTracked
                _state.update { it.copy(preview = preview, loreLoading = false, previewValid = true) }
                loadLorePage(preview, operationRevision, afterId = 0L, reset = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                if (operationRevision == revision.get()) _state.update { it.copy(preview = null, previewValid = false, loreLoading = false, previewError = cause.message ?: "预览加载失败，请重试") }
            }
        }
    }

    fun retryPreview() {
        _state.value.selectedWorldId?.let(::selectWorld)
    }

    fun retryLore() {
        val preview = _state.value.preview ?: return
        val page = (_state.value.pendingLorePage ?: _state.value.lorePageIndex).coerceIn(0, loreAfterIds.lastIndex)
        loadLorePage(preview, revision.get(), loreAfterIds[page], reset = false, pageIndex = page)
    }

    fun nextLorePage() {
        val s = _state.value
        val preview = s.preview ?: return
        if (s.loreLoading || s.applying || !s.loreHasNext) return
        val nextPage = s.lorePageIndex + 1
        loadLorePage(preview, revision.get(), s.lore.lastOrNull()?.id ?: 0L, reset = false, pageIndex = nextPage)
    }

    fun previousLorePage() {
        val s = _state.value
        if (s.loreLoading || s.applying || s.lorePageIndex == 0) return
        val page = s.lorePageIndex - 1
        loadLorePage(s.preview ?: return, revision.get(), loreAfterIds[page], reset = false, pageIndex = page)
    }

    fun toggleField(field: WorldMergeField) {
        if (_state.value.applying) return
        _state.update { current -> current.copy(selectedFields = current.selectedFields.toMutableSet().apply { if (!add(field)) remove(field) }) }
    }

    fun apply(onSuccess: (Long) -> Unit) {
        val preview = _state.value.preview ?: return
        if (_state.value.applying || !_state.value.previewValid) return
        val operationRevision = revision.get()
        val fields = _state.value.selectedFields.toSet()
        _state.update { it.copy(applying = true, applyError = null, previewValid = false) }
        launchTracked {
            try {
                val result = mergeWorldTemplate.apply(preview, fields)
                if (operationRevision != revision.get()) {
                    return@launchTracked
                }
                _state.update { it.copy(applying = false) }
                onSuccess(result.world.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                if (operationRevision == revision.get()) _state.update { it.copy(applying = false, previewValid = false, applyError = cause.message ?: "归入失败，请重新读取预览后重试") }
            }
        }
    }

    fun createWorld(onSuccess: (Long) -> Unit) {
        val templateId = _state.value.templateId ?: return
        if (_state.value.applying) return
        val operationRevision = revision.get()
        _state.update { it.copy(applying = true, applyError = null) }
        launchTracked {
            try {
                val world = promoteWorldTemplate(templateId)
                if (operationRevision != revision.get()) return@launchTracked
                _state.update { it.copy(applying = false) }
                onSuccess(world.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                if (operationRevision == revision.get()) _state.update { it.copy(applying = false, applyError = cause.message ?: "新建世界失败，请重试") }
            }
        }
    }

    suspend fun cancelAndJoin() {
        val pending = synchronized(jobs) { jobs.toList() }
        revision.incrementAndGet()
        pending.forEach { it.cancel() }
        pending.joinAll()
        synchronized(jobs) { jobs.removeAll(pending.toSet()) }
        _state.update { it.copy(applying = false, worldsLoading = false, loreLoading = false) }
    }

    fun release() = releaseOperations()

    private fun loadWorlds(page: Int) {
        if (_state.value.applying) return
        releaseOperations()
        val operationRevision = revision.incrementAndGet()
        pendingWorldPage = page
        val cursor = pageCursors.getOrNull(page)
        _state.update { it.copy(worldsLoading = true, worldsError = null) }
        launchTracked {
            try {
                val rows = encyclopediaDao.getCharacterFilterPage(
                    query = _state.value.searchQuery,
                    cursorPinned = cursor?.let { if (it.pinnedAt > 0L) 1 else 0 },
                    cursorPinnedAt = cursor?.pinnedAt,
                    cursorUpdatedAt = cursor?.updatedAt,
                    cursorId = cursor?.id,
                    limit = 25,
                )
                if (operationRevision != revision.get()) return@launchTracked
                worldsPage = page
                while (pageCursors.size > page + 1) pageCursors.removeAt(pageCursors.lastIndex)
                if (rows.size >= 25 && pageCursors.size <= page + 1) pageCursors += rows[23]
                _state.update { it.copy(worlds = rows.take(24), worldsLoading = false, worldsLoaded = true, worldsPage = page, worldsHasNext = rows.size > 24) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                if (operationRevision == revision.get()) _state.update { it.copy(worldsLoading = false, worldsLoaded = true, worldsError = cause.message ?: "世界列表加载失败，请重试") }
            }
        }
    }

    private fun loadLorePage(preview: WorldTemplateMergePreview, operationRevision: Int, afterId: Long, reset: Boolean, pageIndex: Int = 0) {
        // Full preview rows are replaced; only small cursor metadata survives a page change.
        // Record before I/O so retry can return to the failed page too.
        if (pageIndex == loreAfterIds.size) loreAfterIds += afterId
        _state.update { it.copy(loreLoading = true, loreError = null) }
        launchTracked {
            try {
                val rows = mergeWorldTemplate.lorePage(preview, afterId = afterId, limit = 25)
                if (operationRevision != revision.get()) return@launchTracked
                val pageRows = rows.take(24)
                while (loreAfterIds.size > pageIndex + 1) loreAfterIds.removeAt(loreAfterIds.lastIndex)
                if (pageIndex > 0 && loreAfterIds.size == pageIndex) loreAfterIds += afterId
                _state.update { current -> current.copy(lore = pageRows, loreLoading = false, lorePageIndex = pageIndex, loreHasPrevious = pageIndex > 0, loreHasNext = rows.size > 24, pendingLorePage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                if (operationRevision == revision.get()) _state.update { it.copy(loreLoading = false, pendingLorePage = pageIndex, loreError = cause.message ?: "条目预览加载失败，请重试") }
            }
        }
    }

    private fun launchTracked(block: suspend () -> Unit) {
        lateinit var job: Job
        job = viewModelScope.launch { block() }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
    }

    private fun releaseOperations() {
        revision.incrementAndGet()
        synchronized(jobs) { jobs.toList().forEach { it.cancel() } }
    }

    override fun onCleared() {
        releaseOperations()
        super.onCleared()
    }
}
