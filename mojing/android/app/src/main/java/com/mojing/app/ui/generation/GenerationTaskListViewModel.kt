package com.mojing.app.ui.generation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.generation.CharacterPersonaAiPayload
import com.mojing.app.domain.generation.WorldTemplatePromptAiPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import com.google.gson.Gson

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class GenerationTaskListViewModel @Inject constructor(
    taskDao: GenerationTaskDao,
    private val processor: GenerationQueueProcessor,
    private val resultResolver: com.mojing.app.domain.generation.GenerationResultResolver,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _openingResultId = MutableStateFlow<Long?>(null)
    val openingResultId: StateFlow<Long?> = _openingResultId.asStateFlow()
    private var resultLookupJob: Job? = null
    private var resultLookupRevision = 0L

    fun cancelResultLookup() {
        resultLookupRevision++
        resultLookupJob?.cancel()
        resultLookupJob = null
        _openingResultId.value = null
    }

    fun openResult(
        task: GenerationTaskEntity,
        onOpen: (com.mojing.app.domain.generation.GenerationResultTarget) -> Unit,
        onError: ((String) -> Unit)? = null,
    ) {
        if (_openingResultId.value != null) return
        val revision = ++resultLookupRevision
        _openingResultId.value = task.id
        fun reportError(message: String) {
            if (revision != resultLookupRevision) return
            if (onError != null) onError(message) else _snackbar.value = message
        }
        resultLookupJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val target = resultResolver.resolve(task)
                coroutineContext.ensureActive()
                if (revision != resultLookupRevision) return@launch
                if (target == null) reportError("生成内容已不存在或未关联，记录仍保留")
                else onOpen(target)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { reportError("生成内容暂时无法打开，请重试") }
            finally {
                if (revision == resultLookupRevision) {
                    _openingResultId.value = null
                    resultLookupJob = null
                }
            }
        }
        resultLookupJob?.start()
    }

    private val retryLoads = Channel<Unit>(Channel.CONFLATED)
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    fun retryLoad() {
        if (_loadError.value == null) return
        _loadError.value = null
        _loading.value = true
        retryLoads.trySend(Unit)
    }

    private data class BrowseQuery(
        val cursors: List<Long>,
        val filter: Int,
        val keyword: String,
        val taskKind: String?,
        val debounceInput: Boolean = false,
    )

    private fun readNavigation(value: LongArray): Pair<List<Long>, Int> {
        val filter = value.firstOrNull()?.toInt()?.takeIf { it in 0..2 } ?: 0
        val cursors = value.drop(1)
        val valid = cursors.isEmpty() || (cursors.first() == Long.MAX_VALUE && cursors.all { it > 0 }
            && cursors.zipWithNext().all { (a, b) -> b < a })
        return (if (valid) cursors else emptyList()) to filter
    }

    private fun restoreBrowseQuery(): BrowseQuery {
        val (cursors, filter) = readNavigation(savedStateHandle.get<LongArray>("generation_browse") ?: longArrayOf(0))
        return BrowseQuery(
            cursors = cursors,
            filter = filter,
            keyword = savedStateHandle.get<String>("generation_search").orEmpty(),
            taskKind = savedStateHandle.get<String>("generation_task_kind"),
        )
    }

    private val browseQuery = MutableStateFlow(restoreBrowseQuery())
    private var latestQueryRevision = 0L
    val historyCursors = browseQuery.map { it.cursors }
        .stateIn(viewModelScope, SharingStarted.Eagerly, browseQuery.value.cursors)
    val selectedFilter = browseQuery.map { it.filter }
        .stateIn(viewModelScope, SharingStarted.Eagerly, browseQuery.value.filter)
    val searchQuery: StateFlow<String> = browseQuery.map { it.keyword }
        .stateIn(viewModelScope, SharingStarted.Eagerly, browseQuery.value.keyword)
    val selectedTaskKind: StateFlow<String?> = browseQuery.map { it.taskKind }
        .stateIn(viewModelScope, SharingStarted.Eagerly, browseQuery.value.taskKind)
    private val _hasOlder = MutableStateFlow(false)
    val hasOlder = _hasOlder.asStateFlow()

    private fun updateBrowseQuery(next: BrowseQuery) {
        if (browseQuery.value == next) return
        latestQueryRevision++
        _loading.value = true
        _loadError.value = null
        // Saved fields are restored together; all live reads use the single BrowseQuery owner.
        savedStateHandle["generation_browse"] = longArrayOf(next.filter.toLong(), *next.cursors.toLongArray())
        savedStateHandle["generation_search"] = next.keyword
        savedStateHandle["generation_task_kind"] = next.taskKind
        browseQuery.value = next
    }
    private fun hasSearchFilters(query: BrowseQuery = browseQuery.value): Boolean =
        query.keyword.isNotBlank() || query.taskKind != null

    fun showHistory() = updateBrowseQuery(browseQuery.value.copy(cursors = listOf(Long.MAX_VALUE), debounceInput = false))
    fun showRecent() {
        updateBrowseQuery(BrowseQuery(emptyList(), 0, "", null))
    }

    fun updateSearchQuery(value: String) {
        val current = browseQuery.value
        if (value == current.keyword) return
        updateBrowseQuery(current.copy(
            cursors = if (value.isNotBlank() || current.taskKind != null) listOf(Long.MAX_VALUE) else emptyList(),
            keyword = value, debounceInput = value.isNotBlank(),
        ))
    }

    fun selectTaskKind(kind: String?) {
        val normalized = kind?.takeIf { it.isNotBlank() }
        val current = browseQuery.value
        updateBrowseQuery(current.copy(
            cursors = if (current.keyword.isNotBlank() || normalized != null) listOf(Long.MAX_VALUE) else emptyList(),
            taskKind = normalized, debounceInput = false,
        ))
    }

    fun selectHistoryFilter(filter: Int) {
        val current = browseQuery.value
        if (filter !in 0..2 || current.filter == filter) return
        updateBrowseQuery(current.copy(
            cursors = if (current.cursors.isEmpty() && !hasSearchFilters(current)) emptyList() else listOf(Long.MAX_VALUE),
            filter = filter, debounceInput = false,
        ))
    }
    fun olderPage() {
        val current = browseQuery.value
        if (_loading.value || _loadError.value != null || !_hasOlder.value) return
        val cursor = tasks.value.lastOrNull()?.id ?: return
        updateBrowseQuery(current.copy(cursors = (if (current.cursors.isEmpty()) listOf(Long.MAX_VALUE) else current.cursors) + cursor, debounceInput = false))
    }
    fun newerPage() {
        val current = browseQuery.value
        if (!_loading.value && current.cursors.size > 1) updateBrowseQuery(current.copy(cursors = current.cursors.dropLast(1), debounceInput = false))
    }

    val tasks = browseQuery
        .flatMapLatest { query ->
          kotlinx.coroutines.flow.flow {
            val revision = latestQueryRevision
            if (query.debounceInput) kotlinx.coroutines.delay(250)
            if (revision != latestQueryRevision || query != browseQuery.value) return@flow
            val cursors = query.cursors
            val filter = query.filter
            val keyword = query.keyword.trim()
            val taskKind = query.taskKind
            val history = cursors.isNotEmpty() || keyword.isNotBlank() || taskKind != null
            emitAll((if (history) {
                val beforeId = if (cursors.isEmpty()) Long.MAX_VALUE else cursors.last()
                if (keyword.isBlank() && taskKind == null) taskDao.observeHistoryPage(beforeId, filter)
                else taskDao.observeHistoryPageFiltered(beforeId, filter, keyword, taskKind)
            } else {
                taskDao.observeQueueVisible()
            })
                .onStart { retryLoads.tryReceive(); _loading.value = true; _loadError.value = null }
                .map { rows ->
                    if (revision != latestQueryRevision) throw kotlinx.coroutines.CancellationException("Superseded record query")
                    _hasOlder.value = history && rows.size > 50
                    if (history) rows.take(50) else rows
                }
                .onEach {
                    if (revision == latestQueryRevision) {
                        _loading.value = false
                        _loadError.value = null
                    }
                }
                .retryWhen { cause, _ ->
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    if (revision == latestQueryRevision) {
                        _loading.value = false
                        _loadError.value = "生成记录读取失败，请重试"
                    }
                    retryLoads.receive()
                    true
                })
          }
        }
        .buffer(0)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queuePaused: StateFlow<Boolean> = processor.pausedState
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    private val _retryingIds = MutableStateFlow<Set<Long>>(emptySet())
    val retryingTaskIds: StateFlow<Set<Long>> = _retryingIds.asStateFlow()

    fun consumeSnackbar(expectedMessage: String) {
        _snackbar.compareAndSet(expectedMessage, null)
    }

    private fun runAction(success: String, onResult: (Boolean) -> Unit = {}, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val completed = try { action(); _snackbar.value = success; true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _snackbar.value = "操作未完成，请重试"; false }
            finally { _busy.value = false }
            onResult(completed)
        }
    }

    fun cancelTask(id: Long, onResult: (Boolean) -> Unit = {}) =
        runAction("任务已取消，已保存内容保留", onResult) { check(processor.cancelTask(id)) }
    fun pauseQueue() = runAction("将在当前步骤保存后暂停") { processor.pauseAll() }
    fun resumeQueue() = runAction("已继续生成") { processor.resumeAll() }

    /** 失败任务：原记录从已保存进度继续，并立即提示避免重复点击。 */
    fun retryFailedTask(task: GenerationTaskEntity, onResult: (Boolean) -> Unit = {}) {
        if (task.status != GenerationTaskStatus.FAILED) {
            _snackbar.value = "仅失败任务可重新排队"
            return
        }
        if (task.id in _retryingIds.value) {
            _snackbar.value = "正在重新排队…"
            return
        }
        val total = processor.resolveRetryTotalForUi(task)
        _retryingIds.value = _retryingIds.value + task.id
        _snackbar.value = "正在重新排队（${task.progressDone}/$total）…"
        viewModelScope.launch {
            var completed = false
            try {
                val ok = processor.requeueFailedTask(task)
                completed = ok
                _snackbar.value = if (ok) {
                    "已重新排队（${task.progressDone}/$total）"
                } else {
                    "重新排队失败，请稍后重试"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                _snackbar.value = "继续失败，请重试"
            } finally {
                _retryingIds.value = _retryingIds.value - task.id
            }
            onResult(completed)
        }
    }

    private val _application = MutableStateFlow(SnapshotApplicationState())
    val application = _application.asStateFlow()
    private var applicationJob: Job? = null
    private var applicationRevision = 0L

    fun previewSnapshot(task: GenerationTaskEntity) = previewSnapshot(task.id)

    fun previewSnapshot(taskId: Long) {
        if (_application.value.applying) return
        applicationJob?.cancel()
        val revision = ++applicationRevision
        _application.value = SnapshotApplicationState(taskId = taskId, loading = true)
        applicationJob = viewModelScope.launch {
            try {
                val preview = processor.previewResult(taskId)
                if (revision == applicationRevision) _application.value = SnapshotApplicationState(
                    taskId = taskId, preview = preview, error = if (preview == null) "结果暂时无法读取，请重试" else null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == applicationRevision) _application.value = SnapshotApplicationState(taskId = taskId, error = "读取当前内容失败，请重试")
            }
        }
    }

    fun dismissSnapshot() {
        if (_application.value.applying) return
        applicationRevision++
        applicationJob?.cancel()
        _application.value = SnapshotApplicationState()
    }

    fun applySnapshot() {
        val current = _application.value
        val preview = current.preview ?: return
        if (current.applying || current.loading || current.error != null || !preview.targetExists || preview.alreadyApplied) return
        _application.value = current.copy(applying = true)
        applicationJob = viewModelScope.launch {
            try {
                val outcome = processor.applyResult(preview.taskId, preview.currentPersona, preview.currentSummary, preview.currentWorld)
                when (outcome) {
                    GenerationQueueProcessor.ResultApplyOutcome.Applied, GenerationQueueProcessor.ResultApplyOutcome.AlreadyApplied -> {
                        _application.value = SnapshotApplicationState()
                        _snackbar.value = "AI 结果已应用"
                    }
                    else -> _application.value = current.copy(error = when (outcome) {
                        GenerationQueueProcessor.ResultApplyOutcome.StalePreview -> "内容又有变化，请刷新后重新确认"
                        GenerationQueueProcessor.ResultApplyOutcome.TargetMissing -> "目标已删除，结果仍可复制"
                        else -> "结果格式无法应用，原数据与结果仍保留"
                    })
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _application.value = current.copy(error = "应用未完成，原数据与结果仍保留；请刷新后重试") }
        }
    }
}

data class SnapshotApplicationState(
    val taskId: Long? = null,
    val loading: Boolean = false,
    val applying: Boolean = false,
    val preview: com.mojing.app.domain.generation.GenerationResultApplicationPreview? = null,
    val error: String? = null,
)
