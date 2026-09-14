package com.mojing.app.ui.chat.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.entity.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Only the snippet and identifying fields survive after a result page is read. */
data class SearchHit(val message: MessageEntity, val snippet: String)
data class SearchState(
    val presentation: SearchPresentation = SearchPresentation(),
    val query: String = "", val completedQuery: String = "", val exactMatch: Boolean = false,
    val searching: Boolean = false, val error: String? = null,
    val hits: List<SearchHit> = emptyList(), val totalMatches: Int? = null,
    val hasOlder: Boolean = false, val selectedMessageId: Long? = null,
    val contextMessages: List<MessageEntity> = emptyList(), val history: List<String> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(application: Application, private val messageDao: MessageDao, private val presentationLoader: SearchPresentationLoader,
    private val resultFormatter: SearchResultFormatter,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private var revision = 0L
    private var detailRevision = 0L
    private var beforeId = Long.MAX_VALUE
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var activeSessionId: Long? = null
    private var activeBranchId = "main"
    private val prefs get() = getApplication<Application>().getSharedPreferences(PREFS, 0)

    fun initialize(sessionId: Long, branchId: String = "main") {
        if (activeSessionId == sessionId && activeBranchId == branchId) return
        cancelSearch()
        activeSessionId = sessionId
        activeBranchId = branchId
        _state.value = SearchState(history = history(sessionId))
    }

    fun setQuery(value: String) {
        if (value == state.value.query) return
        cancelSearch()
        _state.value = SearchState(query = value, exactMatch = state.value.exactMatch, history = state.value.history)
    }

    fun setExact(value: Boolean) {
        if (value == state.value.exactMatch) return
        cancelSearch()
        _state.value = SearchState(query = state.value.query, exactMatch = value, history = state.value.history)
    }

    fun removeHistory(sessionId: Long, query: String) = saveHistory(sessionId, history(sessionId).filterNot { it == query })
    fun clearHistory(sessionId: Long) = saveHistory(sessionId, emptyList())

    fun search(sessionId: Long, branchId: String) {
        val q = state.value.query.trim()
        if (q.isBlank()) return
        cancelSearch()
        val token = revision
        val exact = state.value.exactMatch
        beforeId = Long.MAX_VALUE
        saveHistory(sessionId, listOf(q) + history(sessionId).filterNot { it == q })
        _state.update { it.copy(searching = true, error = null, hits = emptyList(), totalMatches = null,
            completedQuery = q, hasOlder = false, selectedMessageId = null, contextMessages = emptyList()) }
        searchJob = viewModelScope.launch {
            try {
                val page = loadPage(sessionId, branchId, q, exact, Long.MAX_VALUE)
                val total = if (branchId == "main") messageDao.countMainMessages(sessionId, q, if (exact) 1 else 0)
                    else messageDao.countVisibleMessages(sessionId, branchId, q, if (exact) 1 else 0)
                val hits = resultFormatter.format(page, q)
                if (token != revision) return@launch
                beforeId = page.lastOrNull()?.id ?: Long.MAX_VALUE
                _state.update { it.copy(searching = false, hits = hits,
                    totalMatches = total, hasOlder = page.size < total && page.isNotEmpty()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == revision) _state.update { it.copy(searching = false, error = "搜索未完成，请重试") } }
        }
    }

    fun loadOlder(sessionId: Long, branchId: String) = loadOlderAndOpen(sessionId, branchId, false)

    private fun loadOlderAndOpen(sessionId: Long, branchId: String, openNext: Boolean) {
        val current = state.value
        if (current.searching || !current.hasOlder || current.completedQuery.isBlank()) return
        val token = revision
        val detailToken = detailRevision
        val cursor = beforeId
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            try {
                val page = loadPage(sessionId, branchId, current.completedQuery, current.exactMatch, cursor)
                val formatted = resultFormatter.format(page, current.completedQuery)
                if (token != revision) return@launch
                beforeId = page.lastOrNull()?.id ?: beforeId
                val hits = (state.value.hits + formatted).distinctBy { it.message.id }
                _state.update { it.copy(searching = false, hits = hits,
                    hasOlder = page.isNotEmpty() && hits.size < (it.totalMatches ?: Int.MAX_VALUE)) }
                if (openNext && detailToken == detailRevision) {
                    page.firstOrNull()?.let { openHit(sessionId, branchId, it.id) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == revision) _state.update { it.copy(searching = false, error = "加载更早结果失败，请重试") } }
        }
    }

    fun navigateHit(sessionId: Long, branchId: String, delta: Int) {
        if (state.value.searching) return
        val current = state.value
        val index = current.hits.indexOfFirst { it.message.id == current.selectedMessageId }
        val next = index + delta
        if (index < 0 || next < 0) return
        current.hits.getOrNull(next)?.let { openHit(sessionId, branchId, it.message.id); return }
        if (delta > 0 && current.hasOlder) loadOlderAndOpen(sessionId, branchId, true)
    }

    fun openHit(sessionId: Long, branchId: String, messageId: Long) {
        detailJob?.cancel()
        val token = revision
        val detailToken = ++detailRevision
        _state.update { it.copy(selectedMessageId = messageId, searching = true, error = null, contextMessages = emptyList()) }
        detailJob = viewModelScope.launch {
            try {
                val target = if (branchId == "main") messageDao.getMainMessageById(sessionId, messageId)
                    else messageDao.getVisibleMessageById(sessionId, branchId, messageId)
                if (target == null) {
                    if (token == revision && detailToken == detailRevision)
                        _state.update { it.copy(searching = false, error = "该消息已删除或不在当前故事线") }
                    return@launch
                }
                val before = if (branchId == "main") messageDao.getMainMessagesBefore(sessionId, messageId, CONTEXT_SIDE)
                    else messageDao.getVisibleMessagesBefore(sessionId, branchId, messageId, CONTEXT_SIDE)
                val after = if (branchId == "main") messageDao.getMainMessagesAfter(sessionId, messageId, CONTEXT_SIDE)
                    else messageDao.getVisibleMessagesAfter(sessionId, branchId, messageId, CONTEXT_SIDE)
                val context = (before.asReversed() + target + after).distinctBy(MessageEntity::id)
                val presentation = presentationLoader.load(sessionId, context)
                if (token == revision && detailToken == detailRevision)
                    _state.update { it.copy(searching = false, contextMessages = context, presentation = presentation) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == revision && detailToken == detailRevision)
                _state.update { it.copy(searching = false, error = "无法打开消息，请重试") } }
        }
    }

    fun closeHit() {
        detailRevision++
        detailJob?.cancel()
        // A pending next-page navigation must not reopen the reader after Back.
        searchJob?.cancel()
        _state.update { it.copy(selectedMessageId = null, contextMessages = emptyList(), searching = false, error = null) }
    }

    private fun cancelSearch() {
        revision++; detailRevision++
        searchJob?.cancel(); detailJob?.cancel()
        searchJob = null; detailJob = null
    }

    private suspend fun loadPage(sessionId: Long, branchId: String, q: String, exact: Boolean, before: Long): List<MessageEntity> =
        if (branchId == "main") messageDao.searchMainMessages(sessionId, q, if (exact) 1 else 0, PAGE_SIZE, before)
        else messageDao.searchVisibleMessages(sessionId, branchId, q, if (exact) 1 else 0, PAGE_SIZE, before)

    private fun history(sessionId: Long): List<String> = prefs.getStringSet(historyKey(sessionId), emptySet()).orEmpty()
        .sortedByDescending { prefs.getLong(timeKey(sessionId, it), 0L) }.take(HISTORY_LIMIT)

    private fun saveHistory(sessionId: Long, values: List<String>) {
        val old = prefs.getStringSet(historyKey(sessionId), emptySet()).orEmpty()
        val ordered = values.distinct().take(HISTORY_LIMIT)
        val edit = prefs.edit().putStringSet(historyKey(sessionId), ordered.toSet())
        old.filterNot { it in ordered }.forEach { edit.remove(timeKey(sessionId, it)) }
        val now = System.currentTimeMillis()
        ordered.forEachIndexed { index, value -> edit.putLong(timeKey(sessionId, value), now - index) }
        edit.apply()
        _state.update { it.copy(history = ordered) }
    }

    private fun historyKey(sessionId: Long) = "history_$sessionId"
    private fun timeKey(sessionId: Long, query: String) = "history_${sessionId}_$query"
    companion object {
        private const val PREFS = "message_search"
        private const val PAGE_SIZE = 40
        private const val HISTORY_LIMIT = 12
        private const val CONTEXT_SIDE = 8
    }
}
