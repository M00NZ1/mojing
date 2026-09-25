package com.mojing.app.ui.chat.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.search.MessageSearchIndexManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only the snippet and identifying fields survive after a result page is read. */
data class SearchHit(val message: MessageEntity, val snippet: String)
enum class SearchPageDirection { NEWER, OLDER }
enum class SearchContextDirection { BEFORE, AFTER }
data class SearchState(
    val presentation: SearchPresentation = SearchPresentation(),
    val query: String = "", val completedQuery: String = "", val exactMatch: Boolean = false,
    val searching: Boolean = false, val error: String? = null,
    val indexing: Boolean = false,
    val hits: List<SearchHit> = emptyList(), val totalMatches: Int? = null,
    val counting: Boolean = false, val countError: String? = null,
    val hasOlder: Boolean = false, val hasNewer: Boolean = false, val firstHitOffset: Int = 0,
    val failedPage: SearchPageDirection? = null, val selectedMessageId: Long? = null,
    val contextMessages: List<MessageEntity> = emptyList(), val history: List<String> = emptyList(),
    val contextBeforeHasMore: Boolean = false, val contextAfterHasMore: Boolean = false,
    val contextLoadingBefore: Boolean = false, val contextLoadingAfter: Boolean = false,
    val contextFailedBefore: Boolean = false, val contextFailedAfter: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(application: Application, private val messageDao: MessageDao,
    private val searchIndexManager: MessageSearchIndexManager, private val presentationLoader: SearchPresentationLoader,
    private val resultFormatter: SearchResultFormatter,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private var revision = 0L
    private var detailRevision = 0L
    private var searchJob: Job? = null
    private var countJob: Job? = null
    private var detailJob: Job? = null
    private var activeSessionId: Long? = null
    private var activeBranchId = "main"
    private var contextBefore = emptyList<MessageEntity>()
    private var contextAfter = emptyList<MessageEntity>()
    private var contextTarget: MessageEntity? = null
    private val contextMutex = Mutex()
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
        saveHistory(sessionId, listOf(q) + history(sessionId).filterNot { it == q })
        _state.update { it.copy(searching = true, indexing = true, error = null, hits = emptyList(), totalMatches = null,
            counting = false, countError = null,
            completedQuery = q, hasOlder = false, hasNewer = false, firstHitOffset = 0,
            failedPage = null, selectedMessageId = null, contextMessages = emptyList()) }
        searchJob = viewModelScope.launch {
            try {
                // A partial FTS rebuild cannot provide complete Unicode results or an exact count.
                // Join the app's resumable rebuild owner before publishing either result.
                searchIndexManager.rebuildIfNeeded()
                if (token != revision) return@launch
                _state.update { it.copy(indexing = false) }
                val page = loadPage(sessionId, branchId, q, exact, Long.MAX_VALUE)
                val hits = resultFormatter.format(page, q)
                if (token != revision) return@launch
                _state.update { it.copy(searching = false, hits = hits,
                    totalMatches = if (page.size < PAGE_SIZE) page.size else null,
                    hasOlder = page.size == PAGE_SIZE) }
                if (page.size == PAGE_SIZE) countMatches(sessionId, branchId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == revision) _state.update {
                it.copy(searching = false, indexing = false,
                    error = if (it.indexing) "搜索索引整理失败，请重试" else "搜索未完成，请重试")
            } }
        }
    }

    fun loadOlder(sessionId: Long, branchId: String) = loadOlderAndOpen(sessionId, branchId, false)

    fun countMatches(sessionId: Long, branchId: String) {
        val current = state.value
        if (current.counting || current.completedQuery.isBlank() || current.totalMatches != null) return
        val token = revision
        _state.update { it.copy(counting = true, countError = null) }
        countJob = viewModelScope.launch {
            try {
                val total = if (branchId == "main") messageDao.countMainMessages(sessionId, current.completedQuery, if (current.exactMatch) 1 else 0)
                    else messageDao.countVisibleMessages(sessionId, branchId, current.completedQuery, if (current.exactMatch) 1 else 0)
                if (token == revision) _state.update {
                    it.copy(counting = false, totalMatches = total,
                        hasOlder = it.hasOlder && it.firstHitOffset + it.hits.size < total)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == revision) _state.update { it.copy(counting = false, countError = "匹配总数统计失败") }
            }
        }
    }

    private fun loadOlderAndOpen(sessionId: Long, branchId: String, openNext: Boolean) {
        val current = state.value
        if (current.searching || !current.hasOlder || current.completedQuery.isBlank()) return
        val token = revision
        val detailToken = detailRevision
        val cursor = current.hits.lastOrNull()?.message?.id ?: return
        _state.update { it.copy(searching = true, error = null, failedPage = null) }
        searchJob = viewModelScope.launch {
            try {
                val page = loadPage(sessionId, branchId, current.completedQuery, current.exactMatch, cursor)
                val formatted = resultFormatter.format(page, current.completedQuery)
                if (token != revision) return@launch
                _state.update {
                    val combined = (it.hits + formatted).distinctBy { hit -> hit.message.id }
                    val dropped = (combined.size - MAX_CACHED_HITS).coerceAtLeast(0)
                    val hits = combined.drop(dropped)
                    val offset = it.firstHitOffset + dropped
                    it.copy(searching = false, hits = hits, firstHitOffset = offset,
                        hasNewer = offset > 0,
                        hasOlder = page.size == PAGE_SIZE && offset + hits.size < (it.totalMatches ?: Int.MAX_VALUE))
                }
                if (openNext && detailToken == detailRevision) {
                    page.firstOrNull()?.let { openHit(sessionId, branchId, it.id) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == revision) _state.update {
                it.copy(searching = false, error = "加载更早结果失败，请重试", failedPage = SearchPageDirection.OLDER) } }
        }
    }

    fun loadNewer(sessionId: Long, branchId: String) = loadNewerAndOpen(sessionId, branchId, false)

    private fun loadNewerAndOpen(sessionId: Long, branchId: String, openPrevious: Boolean) {
        val current = state.value
        if (current.searching || !current.hasNewer || current.completedQuery.isBlank()) return
        val token = revision
        val detailToken = detailRevision
        val cursor = current.hits.firstOrNull()?.message?.id ?: return
        _state.update { it.copy(searching = true, error = null, failedPage = null) }
        searchJob = viewModelScope.launch {
            try {
                val page = loadNewerPage(sessionId, branchId, current.completedQuery, current.exactMatch, cursor)
                val formatted = resultFormatter.format(page.asReversed(), current.completedQuery)
                if (token != revision) return@launch
                _state.update {
                    val combined = (formatted + it.hits).distinctBy { hit -> hit.message.id }
                    val hits = combined.take(MAX_CACHED_HITS)
                    val offset = if (page.size < PAGE_SIZE) 0 else (it.firstHitOffset - formatted.size).coerceAtLeast(0)
                    it.copy(searching = false, hits = hits, firstHitOffset = offset, hasNewer = offset > 0,
                        hasOlder = it.hasOlder || combined.size > MAX_CACHED_HITS)
                }
                if (openPrevious && detailToken == detailRevision) {
                    page.firstOrNull()?.let { openHit(sessionId, branchId, it.id) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == revision) _state.update {
                it.copy(searching = false, error = "加载较新结果失败，请重试", failedPage = SearchPageDirection.NEWER) } }
        }
    }

    fun navigateHit(sessionId: Long, branchId: String, delta: Int) {
        if (state.value.searching) return
        val current = state.value
        val index = current.hits.indexOfFirst { it.message.id == current.selectedMessageId }
        val next = index + delta
        if (index < 0) return
        if (next < 0) {
            if (delta < 0 && current.hasNewer) loadNewerAndOpen(sessionId, branchId, true)
            return
        }
        current.hits.getOrNull(next)?.let { openHit(sessionId, branchId, it.message.id); return }
        if (delta > 0 && current.hasOlder) loadOlderAndOpen(sessionId, branchId, true)
    }

    fun openHit(sessionId: Long, branchId: String, messageId: Long) {
        detailJob?.cancel()
        val token = revision
        val detailToken = ++detailRevision
        contextBefore = emptyList()
        contextAfter = emptyList()
        contextTarget = null
        _state.update { it.copy(selectedMessageId = messageId, searching = true, error = null, contextMessages = emptyList(),
            contextBeforeHasMore = false, contextAfterHasMore = false,
            contextLoadingBefore = false, contextLoadingAfter = false,
            contextFailedBefore = false, contextFailedAfter = false) }
        detailJob = viewModelScope.launch {
            try {
                val target = if (branchId == "main") messageDao.getMainMessageById(sessionId, messageId)
                    else messageDao.getVisibleMessageById(sessionId, branchId, messageId)
                if (target == null) {
                    if (token == revision && detailToken == detailRevision)
                        _state.update { it.copy(searching = false, error = "该消息已删除或不在当前故事线") }
                    return@launch
                }
                if (token != revision || detailToken != detailRevision) return@launch
                contextTarget = target
                val presentation = presentationLoader.load(sessionId, listOf(target))
                if (token != revision || detailToken != detailRevision) return@launch
                _state.update { it.copy(searching = false, contextMessages = listOf(target), presentation = presentation,
                    contextLoadingBefore = true, contextLoadingAfter = true) }
                kotlinx.coroutines.coroutineScope {
                    launch { loadContextSide(sessionId, branchId, messageId, SearchContextDirection.BEFORE, CONTEXT_INITIAL_SIDE, token, detailToken) }
                    launch { loadContextSide(sessionId, branchId, messageId, SearchContextDirection.AFTER, CONTEXT_INITIAL_SIDE, token, detailToken) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == revision && detailToken == detailRevision)
                _state.update { it.copy(searching = false, error = "无法打开消息，请重试") } }
        }
    }

    fun loadMoreContext(sessionId: Long, branchId: String, direction: SearchContextDirection) {
        val current = state.value
        val messageId = current.selectedMessageId ?: return
        if (current.searching || current.contextLoadingBefore || current.contextLoadingAfter) return
        val canLoad = when (direction) {
            SearchContextDirection.BEFORE -> current.contextBeforeHasMore || current.contextFailedBefore
            SearchContextDirection.AFTER -> current.contextAfterHasMore || current.contextFailedAfter
        }
        if (!canLoad) return
        val token = revision
        val detailToken = detailRevision
        _state.update { it.copy(error = null,
            contextLoadingBefore = direction == SearchContextDirection.BEFORE,
            contextLoadingAfter = direction == SearchContextDirection.AFTER,
            contextFailedBefore = if (direction == SearchContextDirection.BEFORE) false else it.contextFailedBefore,
            contextFailedAfter = if (direction == SearchContextDirection.AFTER) false else it.contextFailedAfter) }
        val initialRetry = (direction == SearchContextDirection.BEFORE && contextBefore.isEmpty() && current.contextFailedBefore) ||
            (direction == SearchContextDirection.AFTER && contextAfter.isEmpty() && current.contextFailedAfter)
        detailJob = viewModelScope.launch {
            loadContextSide(sessionId, branchId, messageId, direction,
                if (initialRetry) CONTEXT_INITIAL_SIDE else CONTEXT_SIDE, token, detailToken)
        }
    }

    fun retryContext(sessionId: Long, branchId: String) {
        val direction = when {
            state.value.contextFailedBefore -> SearchContextDirection.BEFORE
            state.value.contextFailedAfter -> SearchContextDirection.AFTER
            else -> return
        }
        loadMoreContext(sessionId, branchId, direction)
    }

    private suspend fun loadContextSide(
        sessionId: Long, branchId: String, messageId: Long, direction: SearchContextDirection,
        limit: Int, token: Long, detailToken: Long,
    ) {
        try {
            val loaded = if (direction == SearchContextDirection.BEFORE) contextBefore.size else contextAfter.size
            val requestLimit = if (loaded == 0) limit else (CONTEXT_SIDE - loaded).coerceAtLeast(1)
            val cursor = when (direction) {
                SearchContextDirection.BEFORE -> contextBefore.firstOrNull()?.id ?: messageId
                SearchContextDirection.AFTER -> contextAfter.lastOrNull()?.id ?: messageId
            }
            val rows = when (direction) {
                SearchContextDirection.BEFORE -> if (branchId == "main") messageDao.getMainMessagesBefore(sessionId, cursor, requestLimit)
                    else messageDao.getVisibleMessagesBefore(sessionId, branchId, cursor, requestLimit)
                SearchContextDirection.AFTER -> if (branchId == "main") messageDao.getMainMessagesAfter(sessionId, cursor, requestLimit)
                    else messageDao.getVisibleMessagesAfter(sessionId, branchId, cursor, requestLimit)
            }
            contextMutex.withLock {
                if (token != revision || detailToken != detailRevision) return@withLock
                val nextBefore = if (direction == SearchContextDirection.BEFORE)
                    (rows.asReversed() + contextBefore).distinctBy(MessageEntity::id).takeLast(CONTEXT_SIDE)
                else contextBefore
                val nextAfter = if (direction == SearchContextDirection.AFTER)
                    (contextAfter + rows).distinctBy(MessageEntity::id).take(CONTEXT_SIDE)
                else contextAfter
                val target = contextTarget ?: return@withLock
                val context = (nextBefore + target + nextAfter).distinctBy(MessageEntity::id)
                val presentation = presentationLoader.load(sessionId, context)
                if (token != revision || detailToken != detailRevision) return@withLock
                if (direction == SearchContextDirection.BEFORE) contextBefore = nextBefore else contextAfter = nextAfter
                _state.update {
                    it.copy(contextMessages = context, presentation = presentation,
                        contextBeforeHasMore = if (direction == SearchContextDirection.BEFORE) contextBefore.size < CONTEXT_SIDE && rows.size == requestLimit else it.contextBeforeHasMore,
                        contextAfterHasMore = if (direction == SearchContextDirection.AFTER) contextAfter.size < CONTEXT_SIDE && rows.size == requestLimit else it.contextAfterHasMore,
                        contextLoadingBefore = if (direction == SearchContextDirection.BEFORE) false else it.contextLoadingBefore,
                        contextLoadingAfter = if (direction == SearchContextDirection.AFTER) false else it.contextLoadingAfter,
                        contextFailedBefore = if (direction == SearchContextDirection.BEFORE) false else it.contextFailedBefore,
                        contextFailedAfter = if (direction == SearchContextDirection.AFTER) false else it.contextFailedAfter,
                        error = if (direction == SearchContextDirection.BEFORE && it.contextFailedBefore || direction == SearchContextDirection.AFTER && it.contextFailedAfter) null else it.error)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (token == revision && detailToken == detailRevision) _state.update {
                it.copy(searching = false,
                    contextLoadingBefore = if (direction == SearchContextDirection.BEFORE) false else it.contextLoadingBefore,
                    contextLoadingAfter = if (direction == SearchContextDirection.AFTER) false else it.contextLoadingAfter,
                    contextFailedBefore = if (direction == SearchContextDirection.BEFORE) true else it.contextFailedBefore,
                    contextFailedAfter = if (direction == SearchContextDirection.AFTER) true else it.contextFailedAfter,
                    error = if (direction == SearchContextDirection.BEFORE) "无法加载上文，请重试" else "无法加载下文，请重试")
            }
        }
    }

    fun closeHit() {
        detailRevision++
        detailJob?.cancel()
        // A pending next-page navigation must not reopen the reader after Back.
        searchJob?.cancel()
        _state.update { it.copy(selectedMessageId = null, contextMessages = emptyList(), searching = false, error = null,
            contextBeforeHasMore = false, contextAfterHasMore = false,
            contextLoadingBefore = false, contextLoadingAfter = false,
            contextFailedBefore = false, contextFailedAfter = false) }
        contextBefore = emptyList()
        contextAfter = emptyList()
        contextTarget = null
    }

    private fun cancelSearch() {
        revision++; detailRevision++
        searchJob?.cancel(); detailJob?.cancel()
        countJob?.cancel(); countJob = null
        searchJob = null; detailJob = null
    }

    private suspend fun loadPage(sessionId: Long, branchId: String, q: String, exact: Boolean, before: Long): List<MessageEntity> =
        if (branchId == "main") messageDao.searchMainMessages(sessionId, q, if (exact) 1 else 0, PAGE_SIZE, before)
        else messageDao.searchVisibleMessages(sessionId, branchId, q, if (exact) 1 else 0, PAGE_SIZE, before)

    private suspend fun loadNewerPage(sessionId: Long, branchId: String, q: String, exact: Boolean, after: Long): List<MessageEntity> =
        if (branchId == "main") messageDao.searchMainMessagesAfter(sessionId, q, if (exact) 1 else 0, PAGE_SIZE, after)
        else messageDao.searchVisibleMessagesAfter(sessionId, branchId, q, if (exact) 1 else 0, PAGE_SIZE, after)

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
        private const val MAX_CACHED_HITS = PAGE_SIZE * 3
        private const val HISTORY_LIMIT = 12
        private const val CONTEXT_SIDE = 8
        private const val CONTEXT_INITIAL_SIDE = 2
    }
}
