package com.mojing.app.ui.generation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.domain.generation.GenerationQueueProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

@HiltViewModel
class GenerationTaskListViewModel @Inject constructor(
    taskDao: GenerationTaskDao,
    private val processor: GenerationQueueProcessor,
) : ViewModel() {

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

    val tasks = taskDao.observeQueueVisible()
        .onStart {
            retryLoads.tryReceive()
            _loading.value = true
            _loadError.value = null
        }
        .onEach { _loading.value = false; _loadError.value = null }
        .retryWhen { cause, _ ->
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            _loading.value = false
            _loadError.value = "生成记录读取失败，请重试"
            retryLoads.receive()
            true
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

    private fun runAction(success: String, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action(); _snackbar.value = success }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _snackbar.value = "操作未完成，请重试" }
            finally { _busy.value = false }
        }
    }

    fun cancelTask(id: Long) = runAction("任务已取消，已保存内容保留") { check(processor.cancelTask(id)) }
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
