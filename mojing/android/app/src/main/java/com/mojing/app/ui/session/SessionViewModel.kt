package com.mojing.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.branch.BranchVisibilityIndexManager
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.CreateSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

internal sealed interface SessionLibraryUiState {
    data object Loading : SessionLibraryUiState
    data class Loaded(
        val sessions: List<SessionWithListMeta>,
        val refreshing: Boolean = false,
        val refreshError: Boolean = false,
        val pageIndex: Int = 0,
        val hasMore: Boolean = false,
        val query: String = "",
    ) : SessionLibraryUiState
    data object Failed : SessionLibraryUiState
}

private data class SessionPageCursor(val pinnedAt: Long, val updatedAt: Long, val id: Long)

private const val SESSION_LIBRARY_PAGE_SIZE = 40

data class BranchCardPreviewState(
    val branchId: String,
    val sessionUpdatedAt: Long,
    val revision: Long,
    val label: String = "",
    val contentPrefix: String? = null,
    val speakerType: String? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val fallsBackToMain: Boolean = false,
)

internal data class SessionDeletionState(
    val sessionId: Long? = null,
    val running: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)

internal data class SessionRenameState(
    val sessionId: Long? = null,
    val running: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionDao: SessionDao,
    private val sessionBranchDao: SessionBranchDao,
    private val messageDao: MessageDao,
    private val branchVisibilityIndexManager: BranchVisibilityIndexManager,
    private val worldTemplateDao: WorldTemplateDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val characterDao: CharacterDao,
    private val createSessionUseCase: CreateSessionUseCase,
    private val generationTaskDao: GenerationTaskDao,
    private val secureStorage: SecureStorage,
    private val uiPreferencesRepository: UiPreferencesRepository,
) : ViewModel() {
    val quickStartGuideDismissed: StateFlow<Boolean?> = uiPreferencesRepository.quickStartGuideDismissed
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun dismissQuickStartGuide() {
        viewModelScope.launch {
            uiPreferencesRepository.dismissQuickStartGuide()
        }
    }
    private val _hasPublicLlmKey = MutableStateFlow(secureStorage.publicApiKey.isNotBlank())
    val hasPublicLlmKey: StateFlow<Boolean> = _hasPublicLlmKey.asStateFlow()

    fun syncPublicLlmKeyFromStorage() {
        _hasPublicLlmKey.value = secureStorage.publicApiKey.isNotBlank()
    }

    private val _sessionLibraryState = MutableStateFlow<SessionLibraryUiState>(SessionLibraryUiState.Loading)
    internal val sessionLibraryState: StateFlow<SessionLibraryUiState> = _sessionLibraryState.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private var sessionLibraryJob: Job? = null
    private val sessionPageCursors = mutableListOf<SessionPageCursor?>(null)
    private var pendingSessionPage: Pair<Int, SessionPageCursor?>? = null
    internal val lastChatBranches: StateFlow<Map<Long, String>?> = uiPreferencesRepository.lastChatBranches
        .catch { emit(emptyMap()) }
        .map<Map<Long, String>, Map<Long, String>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _branchCardPreviews = MutableStateFlow<Map<Long, BranchCardPreviewState>>(emptyMap())
    internal val branchCardPreviews: StateFlow<Map<Long, BranchCardPreviewState>> = _branchCardPreviews.asStateFlow()
    private val _branchPreviewEpoch = MutableStateFlow(0L)
    internal val branchPreviewEpoch: StateFlow<Long> = _branchPreviewEpoch.asStateFlow()
    private val branchPreviewJobs = mutableMapOf<Long, Job>()
    private val branchPreviewReadSlots = Semaphore(4)
    private var branchPreviewRevision = 0L

    init {
        observeSessionLibrary()
    }

    internal fun retrySessionLibrary() {
        val loaded = _sessionLibraryState.value as? SessionLibraryUiState.Loaded
        val target = pendingSessionPage ?: loaded?.let { it.pageIndex to sessionPageCursors[it.pageIndex] }
        observeSessionLibrary(target?.first ?: 0, target?.second, _searchQuery.value)
    }

    internal fun nextSessionLibraryPage() {
        val loaded = _sessionLibraryState.value as? SessionLibraryUiState.Loaded ?: return
        if (loaded.refreshing || !loaded.hasMore || loaded.sessions.isEmpty()) return
        val last = loaded.sessions.last().session
        observeSessionLibrary(loaded.pageIndex + 1, SessionPageCursor(last.pinnedAt, last.updatedAt, last.id), loaded.query)
    }

    internal fun previousSessionLibraryPage() {
        val loaded = _sessionLibraryState.value as? SessionLibraryUiState.Loaded ?: return
        if (loaded.refreshing || loaded.pageIndex == 0) return
        val index = loaded.pageIndex - 1
        observeSessionLibrary(index, sessionPageCursors[index], loaded.query)
    }

    internal fun refreshBranchCardPreviews() {
        branchPreviewJobs.values.toList().forEach { it.cancel() }
        branchPreviewJobs.clear()
        _branchCardPreviews.value = emptyMap()
        _branchPreviewEpoch.value += 1L
    }

    internal fun clearBranchCardPreview(sessionId: Long) {
        branchPreviewJobs.remove(sessionId)?.cancel()
        _branchCardPreviews.value = _branchCardPreviews.value - sessionId
    }

    internal fun loadBranchCardPreview(sessionId: Long, branchId: String, updatedAt: Long, retry: Boolean = false) {
        if (sessionId <= 0L || branchId == "main") return
        val previous = _branchCardPreviews.value[sessionId]
        if (!retry && previous?.branchId == branchId && previous.sessionUpdatedAt == updatedAt && !previous.error) return
        branchPreviewJobs.remove(sessionId)?.cancel()
        val revision = ++branchPreviewRevision
        publishBranchCardPreview(sessionId, BranchCardPreviewState(branchId, updatedAt, revision))
        val job = viewModelScope.launch {
            try {
                val result = branchPreviewReadSlots.withPermit {
                    val branch = sessionBranchDao.getByBranch(sessionId, branchId)
                    if (branch == null) {
                        BranchCardPreviewState(branchId, updatedAt, revision, fallsBackToMain = true, loading = false)
                    } else {
                        branchVisibilityIndexManager.ensureReady()
                        val preview = messageDao.getVisibleStoryCardPreview(sessionId, branchId)
                        BranchCardPreviewState(
                            branchId = branchId, sessionUpdatedAt = updatedAt, revision = revision,
                            label = branch.label.ifBlank { "故事线" }, contentPrefix = preview?.contentPrefix,
                            speakerType = preview?.speakerType, loading = false,
                        )
                    }
                }
                publishBranchCardPreviewIfCurrent(sessionId, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishBranchCardPreviewIfCurrent(
                    sessionId, BranchCardPreviewState(branchId, updatedAt, revision, loading = false, error = true),
                )
            }
        }
        branchPreviewJobs[sessionId] = job
        job.invokeOnCompletion { if (branchPreviewJobs[sessionId] === job) branchPreviewJobs.remove(sessionId) }
    }

    private fun publishBranchCardPreviewIfCurrent(sessionId: Long, state: BranchCardPreviewState) {
        if (_branchCardPreviews.value[sessionId]?.revision == state.revision) publishBranchCardPreview(sessionId, state)
    }

    private fun publishBranchCardPreview(sessionId: Long, state: BranchCardPreviewState) {
        val next = LinkedHashMap(_branchCardPreviews.value)
        next.remove(sessionId)
        next[sessionId] = state
        while (next.size > 80) {
            val oldest = next.keys.first()
            next.remove(oldest)
            branchPreviewJobs.remove(oldest)?.cancel()
        }
        _branchCardPreviews.value = next
    }

    private fun observeSessionLibrary(
        pageIndex: Int = 0,
        cursor: SessionPageCursor? = null,
        query: String = _searchQuery.value,
    ) {
        sessionLibraryJob?.cancel()
        pendingSessionPage = pageIndex to cursor
        val previous = (_sessionLibraryState.value as? SessionLibraryUiState.Loaded)?.takeIf { it.query == query }
        _sessionLibraryState.value = previous?.copy(refreshing = true, refreshError = false)
            ?: SessionLibraryUiState.Loading
        sessionLibraryJob = viewModelScope.launch {
            try {
                sessionDao.observeListPageWithMeta(
                    query, cursor?.pinnedAt, cursor?.updatedAt, cursor?.id, SESSION_LIBRARY_PAGE_SIZE + 1,
                ).collect { rows ->
                    val sessions = rows.take(SESSION_LIBRARY_PAGE_SIZE)
                    if (pageIndex == sessionPageCursors.size) sessionPageCursors.add(cursor)
                    else if (pageIndex < sessionPageCursors.size) sessionPageCursors[pageIndex] = cursor
                    while (sessionPageCursors.size > pageIndex + 1) sessionPageCursors.removeAt(sessionPageCursors.lastIndex)
                    pendingSessionPage = null
                    _sessionLibraryState.value = SessionLibraryUiState.Loaded(
                        sessions = sessions, pageIndex = pageIndex, hasMore = rows.size > SESSION_LIBRARY_PAGE_SIZE,
                        query = query,
                    )
                }
                val current = _sessionLibraryState.value
                if (current is SessionLibraryUiState.Loading ||
                    current is SessionLibraryUiState.Loaded && current.refreshing) {
                    showSessionLibraryReadFailure()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showSessionLibraryReadFailure()
            }
        }
    }

    private fun showSessionLibraryReadFailure() {
        _sessionLibraryState.value = when (val current = _sessionLibraryState.value) {
            is SessionLibraryUiState.Loaded -> current.copy(refreshing = false, refreshError = true)
            else -> SessionLibraryUiState.Failed
        }
    }

    val pendingGenerationTaskCount = generationTaskDao.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isCreatingSession = MutableStateFlow(false)
    val isCreatingSession: StateFlow<Boolean> = _isCreatingSession.asStateFlow()

    fun updateSearch(query: String) {
        if (_searchQuery.value == query) return
        _searchQuery.value = query
        sessionPageCursors.clear()
        sessionPageCursors.add(null)
        observeSessionLibrary(query = query)
    }

    private fun resetLibraryPageAfterReorder() {
        if ((_sessionLibraryState.value as? SessionLibraryUiState.Loaded)?.pageIndex == 0) return
        sessionPageCursors.clear()
        sessionPageCursors.add(null)
        observeSessionLibrary(query = _searchQuery.value)
    }

    fun createNewSession(
        onCreated: (Long) -> Unit = {},
        onFailed: (String) -> Unit = {},
        onCreatedButNotOpened: (Long) -> Unit = { onFailed("对话已创建，但未能打开，请从故事库进入") },
        creationRequestId: String? = null,
    ) {
        if (!_isCreatingSession.compareAndSet(expect = false, update = true)) {
            onFailed(SESSION_CREATION_BUSY_MESSAGE)
            return
        }
        viewModelScope.launch {
            try {
                val result = try {
                    createSessionUseCase.createBlank(creationRequestId = creationRequestId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onFailed("创建对话失败，请重试")
                    return@launch
                }
                if (result is CreateSessionUseCase.Result.Created) {
                    try { onCreated(result.sessionId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onCreatedButNotOpened(result.sessionId) }
                } else {
                    onFailed("创建对话失败，请重试")
                }
            } finally {
                _isCreatingSession.value = false
            }
        }
    }

    /** 与 Web LocalConfig 对齐：新建会话 FAB 表单初始开关 */
    fun newSessionDialogDefaults(): NewSessionDialogDefaults = NewSessionDialogDefaults(
        narratorEnabled = secureStorage.defaultNarratorEnabled,
        choiceEnabled = secureStorage.defaultChoiceGenerationEnabled,
        antiCheatEnabled = secureStorage.defaultAntiCheatEnabled,
        defaultWorldTemplateId = secureStorage.defaultWorldTemplateId,
    )

    data class NewSessionDialogDefaults(
        val narratorEnabled: Boolean,
        val choiceEnabled: Boolean,
        val antiCheatEnabled: Boolean,
        val defaultWorldTemplateId: String,
    )

    data class NewSessionDialogData(
        val templates: List<WorldTemplateEntity>,
        val encyclopedias: List<EncyclopediaEntity>,
        val boundCharacters: List<CharacterEntity>,
        val worldMappings: Map<Long, Long> = emptyMap(),
    )

    suspend fun loadNewSessionDialogData(): NewSessionDialogData = NewSessionDialogData(
        templates = worldTemplateDao.getAll(),
        encyclopedias = encyclopediaDao.getAll(),
        boundCharacters = characterDao.getAll(),
        worldMappings = worldTemplateDao.getWorldMappings().associate { it.worldTemplateId to it.encyclopediaId },
    )

    fun createSessionWithOptions(
        title: String,
        template: WorldTemplateEntity?,
        encyclopediaId: Long?,
        narratorEnabled: Boolean,
        narratorName: String,
        choiceEnabled: Boolean,
        maxChoices: Int,
        antiCheatEnabled: Boolean,
        /** 聊天页展示用上下文上限（tokens），默认 100 万 */
        displayContextTokenLimit: Int = 1_000_000,
        /** 至少一名；顺序写入 sortOrder */
        participantCharacterIds: List<Long>,
        onCreated: (Long) -> Unit = {},
        /** 校验失败（参与者与百科不一致等） */
        onBlocked: (String) -> Unit = {},
        onCreatedButNotOpened: (Long) -> Unit = { onBlocked("对话已创建，但未能打开，请从故事库进入") },
        creationRequestId: String? = null,
    ) {
        if (!_isCreatingSession.compareAndSet(expect = false, update = true)) {
            onBlocked(SESSION_CREATION_BUSY_MESSAGE)
            return
        }
        viewModelScope.launch {
            try {
                val result = try {
                    createSessionUseCase.create(
                        title = title,
                        template = template,
                        encyclopediaId = encyclopediaId,
                        narratorEnabled = narratorEnabled,
                        narratorName = narratorName,
                        choiceEnabled = choiceEnabled,
                        maxChoices = maxChoices,
                        antiCheatEnabled = antiCheatEnabled,
                        displayContextTokenLimit = displayContextTokenLimit,
                        characterIds = participantCharacterIds,
                        creationRequestId = creationRequestId,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onBlocked("创建对话失败，请重试")
                    return@launch
                }
                when (result) {
                    is CreateSessionUseCase.Result.Created -> {
                        try { onCreated(result.sessionId) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { onCreatedButNotOpened(result.sessionId) }
                    }
                    CreateSessionUseCase.Result.CharacterNotFound -> onBlocked("找不到所选角色")
                    CreateSessionUseCase.Result.UnboundCharacter -> onBlocked("参与角色须已绑定百科")
                    CreateSessionUseCase.Result.EncyclopediaMismatch -> onBlocked("参与角色须全部属于所选百科")
                    CreateSessionUseCase.Result.EmptyParticipants -> onBlocked("请至少选择一名参与角色")
                }
            } finally {
                _isCreatingSession.value = false
            }
        }
    }

    private val _deletionState = MutableStateFlow(SessionDeletionState())
    internal val deletionState = _deletionState.asStateFlow()

    private val _renameState = MutableStateFlow(SessionRenameState())
    internal val renameState = _renameState.asStateFlow()

    internal fun clearRenameResult() {
        if (!_renameState.value.running) _renameState.value = SessionRenameState()
    }

    fun renameSession(id: Long, title: String) {
        if (_renameState.value.running) return
        val nextTitle = title.trim().take(100)
        if (nextTitle.isBlank()) {
            _renameState.value = SessionRenameState(id, error = "请填写对话名称")
            return
        }
        _renameState.value = SessionRenameState(id, running = true)
        viewModelScope.launch {
            try {
                check(sessionDao.getById(id) != null) { "对话已不存在" }
                sessionDao.updateTitle(id, nextTitle)
                resetLibraryPageAfterReorder()
                _renameState.value = SessionRenameState(id, completed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _renameState.value = SessionRenameState(id, error = "名称保存失败，请重试")
            }
        }
    }

    internal fun clearDeletionResult() {
        if (!_deletionState.value.running) _deletionState.value = SessionDeletionState()
    }

    fun deleteSession(id: Long) {
        if (_deletionState.value.running) return
        if (id in com.mojing.app.ui.chat.RetainedChatSessions.running.value) {
            _deletionState.value = SessionDeletionState(id, error = "请先停止此对话的后台任务，再删除")
            return
        }
        _deletionState.value = SessionDeletionState(id, running = true)
        viewModelScope.launch {
            try {
                sessionDao.delete(id)
                resetLibraryPageAfterReorder()
                branchPreviewJobs.remove(id)?.cancel()
                _branchCardPreviews.value = _branchCardPreviews.value - id
                _deletionState.value = SessionDeletionState(id, completed = true)
                runCatching { uiPreferencesRepository.clearLastChatBranch(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _deletionState.value = SessionDeletionState(id, error = "删除未完成，请重试")
            }
        }
    }

    fun setSessionPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            val s = sessionDao.getById(id) ?: return@launch
            sessionDao.update(
                s.copy(pinnedAt = if (pinned) System.currentTimeMillis() else 0L),
            )
            resetLibraryPageAfterReorder()
        }
    }

    private companion object {
        const val SESSION_CREATION_BUSY_MESSAGE = "正在创建对话，请稍候"
    }
}
