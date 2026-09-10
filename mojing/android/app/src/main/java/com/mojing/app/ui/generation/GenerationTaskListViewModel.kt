package com.mojing.app.ui.generation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.domain.generation.GenerationQueueProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class GenerationTaskListViewModel @Inject constructor(
    taskDao: GenerationTaskDao,
    private val processor: GenerationQueueProcessor,
    private val resultResolver: com.mojing.app.domain.generation.GenerationResultResolver,
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

    private val _historyCursors = MutableStateFlow<List<Long>>(emptyList())
    val historyCursors = _historyCursors.asStateFlow()
    private val historyFilter = MutableStateFlow(0)
    private val _hasOlder = MutableStateFlow(false)
    val hasOlder = _hasOlder.asStateFlow()

    fun showHistory() { _loading.value = true; historyFilter.value = 0; _historyCursors.value = listOf(Long.MAX_VALUE) }
    fun showRecent() { _loading.value = true; _historyCursors.value = emptyList() }
    fun selectHistoryFilter(filter: Int) {
        if (filter !in 0..2 || historyFilter.value == filter) return
        _loading.value = true
        historyFilter.value = filter
        if (_historyCursors.value.isNotEmpty()) _historyCursors.value = listOf(Long.MAX_VALUE)
    }
    fun olderPage() {
        if (_loading.value || !_hasOlder.value || _historyCursors.value.isEmpty()) return
        val cursor = tasks.value.lastOrNull()?.id ?: return
        _loading.value = true
        _historyCursors.value = _historyCursors.value + cursor
    }
    fun newerPage() {
        if (!_loading.value && _historyCursors.value.size > 1) { _loading.value = true; _historyCursors.value = _historyCursors.value.dropLast(1) }
    }

    val tasks = combine(_historyCursors, historyFilter) { cursors, filter -> cursors to filter }
        .flatMapLatest { (cursors, filter) ->
            val history = cursors.isNotEmpty()
            (if (history) taskDao.observeHistoryPage(cursors.last(), filter) else taskDao.observeQueueVisible())
                .onStart { retryLoads.tryReceive(); _loading.value = true; _loadError.value = null }
                .map { rows -> _hasOlder.value = history && rows.size > 50; if (history) rows.take(50) else rows }
                .onEach { _loading.value = false; _loadError.value = null }
                .retryWhen { cause, _ ->
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    _loading.value = false
                    _loadError.value = "生成记录读取失败，请重试"
                    retryLoads.receive()
                    true
                }
        }
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
    fun retryFailedTask(task: GenerationTaskEntity) {
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
            try {
                val ok = processor.requeueFailedTask(task)
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
        }
    }
}
