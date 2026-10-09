package com.mojing.app.ui.chat.contents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.domain.story.NovelChapter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class StoryContentsState(
    val query: String = "",
    val catalogLoaded: Boolean = false,
    val latestEntry: StoryContentsEntry? = null,
    val canResumeChapter: Boolean = false,
    val entries: List<StoryContentsEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val refreshingId: Long? = null,
    val refreshFailedId: Long? = null,
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
    private var pageJob: Job? = null

    fun load(sessionId: Long, branchId: String) {
        pageJob?.cancel()
        val branch = branchId.ifBlank { "main" }
        // Reattaching the same directory (including Activity recreation) must not
        // discard its search intent. A different story or line starts unfiltered.
        val previous = _state.value.takeIf { this.sessionId == sessionId && this.branchId == branch }
        this.sessionId = sessionId
        this.branchId = branch
        requestToken++
        cursor = Long.MAX_VALUE
        _state.value = StoryContentsState(
            query = previous?.query.orEmpty(), isLoading = true,
            catalogLoaded = previous?.catalogLoaded ?: false,
            latestEntry = previous?.latestEntry,
            canResumeChapter = previous?.canResumeChapter ?: false,
        )
        fetch(reset = true, requestToken)
    }

    fun retry() {
        if (_state.value.isLoading) return
        resetQuery(_state.value.query, debounce = false)
    }

    fun updateQuery(query: String) {
        if (!_state.value.catalogLoaded || query == _state.value.query) return
        resetQuery(query, debounce = true)
    }

    private fun resetQuery(query: String, debounce: Boolean) {
        pageJob?.cancel()
        cursor = Long.MAX_VALUE
        _state.value = StoryContentsState(query = query, isLoading = true,
            catalogLoaded = _state.value.catalogLoaded, latestEntry = _state.value.latestEntry,
            canResumeChapter = _state.value.canResumeChapter)
        fetch(reset = true, ++requestToken, debounce)
    }

    fun loadMore() {
        if (_state.value.isLoading || _state.value.isLoadingMore || !_state.value.hasMore) return
        _state.value = _state.value.copy(isLoadingMore = true, error = null)
        fetch(reset = false, requestToken)
    }

    fun refreshEntry(messageId: Long) {
        if (_state.value.refreshingId != null) return
        // A renamed result may no longer match; repeat the SQL filter rather than keep a stale hit.
        if (_state.value.query.isNotBlank()) {
            resetQuery(_state.value.query, debounce = false)
            return
        }
        if (_state.value.entries.none { it.messageId == messageId }) return
        val token = requestToken
        val session = sessionId
        val branch = branchId
        _state.value = _state.value.copy(refreshingId = messageId, refreshFailedId = null)
        viewModelScope.launch {
            try {
                val row = if (branch == "main") messageDao.getMainStoryContentsEntry(session, messageId)
                    else messageDao.getBranchStoryContentsEntry(session, branch, messageId)
                if (token != requestToken) return@launch
                val replacement = row?.toContentsEntry()
                _state.value = _state.value.copy(
                    entries = _state.value.entries.mapNotNull { entry -> if (entry.messageId == messageId) replacement else entry },
                    refreshingId = null,
                    latestEntry = if (_state.value.latestEntry?.messageId == messageId) replacement else _state.value.latestEntry,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == requestToken) _state.value = _state.value.copy(refreshingId = null, refreshFailedId = messageId)
            }
        }
    }

    private fun fetch(reset: Boolean, token: Long, debounce: Boolean = false) {
        val session = sessionId
        val branch = branchId
        val before = cursor
        val query = _state.value.query.trim()
        pageJob = viewModelScope.launch {
        try {
            if (debounce) delay(250)
            val rows = if (query.isEmpty()) messageDao.getVisibleStoryContentsBefore(session, branch, before, PAGE_SIZE + 1)
                else messageDao.searchVisibleStoryContentsBefore(session, branch, before, PAGE_SIZE + 1, query, contentsQueryChapterNumber(query))
            val canResume = if (reset && query.isEmpty()) messageDao.getStoryChapterTail(session, branch)?.let {
                NovelChapter.canResumeTail(branch, it.branchId, it.structuredContentJson)
            } ?: false else _state.value.canResumeChapter
            if (token != requestToken) return@launch
            val page = rows.take(PAGE_SIZE)
            val mapped = page.toContentsEntries()
            if (page.isNotEmpty()) cursor = page.last().id
            val hasMore = rows.size > PAGE_SIZE
            _state.value = _state.value.copy(
                entries = if (reset) mapped else _state.value.entries + mapped,
                isLoading = false, isLoadingMore = false, hasMore = hasMore, error = null,
                catalogLoaded = true,
                latestEntry = if (reset && query.isEmpty()) mapped.firstOrNull() else _state.value.latestEntry,
                canResumeChapter = canResume,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (token != requestToken) return@launch
            _state.value = if (reset) _state.value.copy(isLoading = false, error = "目录加载失败，请重试")
            else _state.value.copy(isLoadingMore = false, error = "更多章节加载失败，请重试")
        }
        }
    }

    private companion object { const val PAGE_SIZE = 40 }
}
