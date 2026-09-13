package com.mojing.app.ui.chat.contents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.MessageDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StoryContentsState(
    val entries: List<StoryContentsEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class StoryContentsViewModel @Inject constructor(
    private val messageDao: MessageDao,
) : ViewModel() {
    private val _state = MutableStateFlow(StoryContentsState())
    val state = _state.asStateFlow()
    private var sessionId: Long = 0
    private var branchId: String = "main"
    private var cursor = Long.MAX_VALUE
    private var requestToken = 0L

    fun load(sessionId: Long, branchId: String) {
        this.sessionId = sessionId
        this.branchId = branchId.ifBlank { "main" }
        requestToken++
        cursor = Long.MAX_VALUE
        _state.value = StoryContentsState(isLoading = true)
        fetch(reset = true, requestToken)
    }

    fun retry() {
        if (_state.value.isLoading) return
        cursor = Long.MAX_VALUE
        _state.value = StoryContentsState(isLoading = true)
        fetch(reset = true, ++requestToken)
    }

    fun loadMore() {
        if (_state.value.isLoading || _state.value.isLoadingMore || !_state.value.hasMore) return
        fetch(reset = false, requestToken)
    }

    private fun fetch(reset: Boolean, token: Long) = viewModelScope.launch {
        if (!reset) _state.value = _state.value.copy(isLoadingMore = true, error = null)
        try {
            val rows = messageDao.getVisibleStoryContentsBefore(sessionId, branchId, cursor, PAGE_SIZE)
            if (token != requestToken) return@launch
            val mapped = rows.toContentsEntries()
            if (rows.isNotEmpty()) cursor = rows.minOf { it.id }
            _state.value = if (reset) StoryContentsState(entries = mapped, hasMore = rows.size == PAGE_SIZE)
            else _state.value.copy(entries = _state.value.entries + mapped, isLoadingMore = false, hasMore = rows.size == PAGE_SIZE)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (token != requestToken) return@launch
            _state.value = if (reset) StoryContentsState(isLoading = false, error = "目录加载失败，请重试")
            else _state.value.copy(isLoadingMore = false, error = "更多章节加载失败，请重试")
        }
    }

    private companion object { const val PAGE_SIZE = 40 }
}
