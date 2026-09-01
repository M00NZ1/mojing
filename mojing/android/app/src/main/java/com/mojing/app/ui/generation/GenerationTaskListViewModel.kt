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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GenerationTaskListViewModel @Inject constructor(
    taskDao: GenerationTaskDao,
    private val processor: GenerationQueueProcessor,
) : ViewModel() {

    val tasks = taskDao.observeQueueVisible()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _queuePaused = MutableStateFlow(processor.isQueuePaused())
    val queuePaused: StateFlow<Boolean> = _queuePaused.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    private val retryingIds = mutableSetOf<Long>()

    fun consumeSnackbar() {
        _snackbar.value = null
    }

    fun cancelTask(id: Long) {
        viewModelScope.launch { processor.cancelTask(id) }
    }

    fun pauseQueue() {
        viewModelScope.launch {
            processor.pauseAll()
            _queuePaused.value = true
        }
    }

    fun resumeQueue() {
        viewModelScope.launch {
            processor.resumeAll()
            _queuePaused.value = false
        }
    }

    /** 失败任务：原记录重新排队（0/原始总数），并立即提示避免重复点击。 */
    fun retryFailedTask(task: GenerationTaskEntity) {
        if (task.status != GenerationTaskStatus.FAILED) {
            _snackbar.value = "仅失败任务可重新排队"
            return
        }
        if (task.id in retryingIds) {
            _snackbar.value = "正在重新排队…"
            return
        }
        val total = processor.resolveRetryTotalForUi(task)
        retryingIds.add(task.id)
        _snackbar.value = "正在重新排队（0/$total）…"
        viewModelScope.launch {
            try {
                val ok = processor.requeueFailedTask(task)
                _snackbar.value = if (ok) {
                    "已重新排队（0/$total）"
                } else {
                    "重新排队失败，请稍后重试"
                }
            } finally {
                retryingIds.remove(task.id)
            }
        }
    }
}
