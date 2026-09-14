package com.mojing.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.WorldTemplateDao
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal sealed interface SessionLibraryUiState {
    data object Loading : SessionLibraryUiState
    data class Loaded(val sessions: List<SessionWithListMeta>) : SessionLibraryUiState
    data object Failed : SessionLibraryUiState
}

internal data class SessionDeletionState(
    val sessionId: Long? = null,
    val running: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionDao: SessionDao,
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
    private var sessionLibraryJob: Job? = null

    init {
        observeSessionLibrary()
    }

    internal fun retrySessionLibrary() {
        observeSessionLibrary()
    }

    private fun observeSessionLibrary() {
        sessionLibraryJob?.cancel()
        sessionLibraryJob = viewModelScope.launch {
            _sessionLibraryState.value = SessionLibraryUiState.Loading
            try {
                sessionDao.observeAllWithListMeta().collect { sessions ->
                    _sessionLibraryState.value = SessionLibraryUiState.Loaded(sessions)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _sessionLibraryState.value = SessionLibraryUiState.Failed
            }
        }
    }

    val pendingGenerationTaskCount = generationTaskDao.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isCreatingSession = MutableStateFlow(false)
    val isCreatingSession: StateFlow<Boolean> = _isCreatingSession.asStateFlow()

    fun updateSearch(query: String) { _searchQuery.value = query }

    fun createNewSession(
        onCreated: (Long) -> Unit = {},
        onFailed: (String) -> Unit = {},
    ) {
        if (!_isCreatingSession.compareAndSet(expect = false, update = true)) {
            onFailed(SESSION_CREATION_BUSY_MESSAGE)
            return
        }
        viewModelScope.launch {
            try {
                when (val result = createSessionUseCase.createBlank()) {
                    is CreateSessionUseCase.Result.Created -> onCreated(result.sessionId)
                    else -> onFailed("创建对话失败，请重试")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailed("创建对话失败，请重试")
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
    ) {
        if (!_isCreatingSession.compareAndSet(expect = false, update = true)) {
            onBlocked(SESSION_CREATION_BUSY_MESSAGE)
            return
        }
        viewModelScope.launch {
            try {
                when (val result = createSessionUseCase.create(
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
                )) {
                    is CreateSessionUseCase.Result.Created -> onCreated(result.sessionId)
                    CreateSessionUseCase.Result.CharacterNotFound -> onBlocked("找不到所选角色")
                    CreateSessionUseCase.Result.UnboundCharacter -> onBlocked("参与角色须已绑定百科")
                    CreateSessionUseCase.Result.EncyclopediaMismatch -> onBlocked("参与角色须全部属于所选百科")
                    CreateSessionUseCase.Result.EmptyParticipants -> onBlocked("请至少选择一名参与角色")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onBlocked("创建对话失败，请重试")
            } finally {
                _isCreatingSession.value = false
            }
        }
    }

    private val _deletionState = MutableStateFlow(SessionDeletionState())
    internal val deletionState = _deletionState.asStateFlow()

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
        }
    }

    private companion object {
        const val SESSION_CREATION_BUSY_MESSAGE = "正在创建对话，请稍候"
    }
}
