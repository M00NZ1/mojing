package com.mojing.app.ui.character

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.domain.usecase.CreateSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class CharacterDetailState(
    val character: CharacterEntity? = null,
    val encyclopediaName: String? = null,
    val recentStories: List<SessionEntity> = emptyList(),
    val storyPageIndex: Int = 0,
    val storyHasNext: Boolean = false,
    val storiesLoading: Boolean = false,
    val storiesError: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val actionInProgress: Boolean = false,
    val actionError: String? = null,
)

@HiltViewModel
class CharacterDetailViewModel @Inject constructor(
    private val characterDao: CharacterDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val sessionDao: SessionDao,
    private val createSession: CreateSessionUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(CharacterDetailState())
    val state: StateFlow<CharacterDetailState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var loadRevision = 0L
    private var actionRevision = 0L
    private var actionOwnerId: Long? = null
    private var characterDataRevision = 0L
    private var storyJob: Job? = null
    private var storyRevision = 0L
    private val storyCursors = mutableListOf<Pair<Long, Long>?>(null)
    private var requestedStoryPage = 0

    fun load(id: Long, force: Boolean = false) {
        if (!force && !(_state.value.loading || _state.value.character?.id != id)) return
        loadJob?.cancel()
        val revision = ++loadRevision
        val sameCharacter = _state.value.character?.id == id
        storyJob?.cancel()
        storyRevision++
        if (!sameCharacter) {
            actionRevision++
            actionOwnerId = null
            storyCursors.clear()
            storyCursors.add(null)
            _state.update { it.copy(recentStories = emptyList(), storyPageIndex = 0,
                storyHasNext = false, storiesLoading = false, storiesError = null) }
        }
        val dataRevision = characterDataRevision
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    actionInProgress = if (sameCharacter) it.actionInProgress else false,
                    actionError = if (sameCharacter) it.actionError else null,
                )
            }
            try {
                val character = characterDao.getById(id)
                    ?: throw IllegalStateException("找不到这个角色，它可能已经被删除")
                val worldName = character.boundEncyclopediaId.takeIf { it > 0L }?.let {
                    encyclopediaDao.getNameById(it)
                }
                if (revision != loadRevision) return@launch
                if (dataRevision != characterDataRevision) {
                    viewModelScope.launch { load(id, force = true) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        character = character,
                        encyclopediaName = worldName,
                        loading = false,
                        error = null,
                        actionInProgress = if (sameCharacter) it.actionInProgress else false,
                        actionError = if (sameCharacter) it.actionError else null,
                    )
                }
                loadStoryPage(_state.value.storyPageIndex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision != loadRevision) return@launch
                if (dataRevision != characterDataRevision) {
                    viewModelScope.launch { load(id, force = true) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "读取角色失败，请重试",
                        actionInProgress = if (sameCharacter) it.actionInProgress else false,
                        actionError = if (sameCharacter) it.actionError else null,
                    )
                }
            }
        }
    }

    fun retry(id: Long) = load(id, force = true)

    fun nextStoryPage() {
        if (_state.value.storyHasNext) loadStoryPage(_state.value.storyPageIndex + 1)
    }

    fun previousStoryPage() {
        if (_state.value.storyPageIndex > 0) loadStoryPage(_state.value.storyPageIndex - 1)
    }

    fun retryStoryPage() = loadStoryPage(requestedStoryPage)

    private fun loadStoryPage(targetPage: Int) {
        val current = _state.value
        val id = current.character?.id ?: return
        if (current.loading || storyJob?.isActive == true || targetPage !in storyCursors.indices) return
        requestedStoryPage = targetPage
        val revision = ++storyRevision
        val characterRevision = loadRevision
        _state.update { it.copy(storiesLoading = true, storiesError = null) }
        storyJob = viewModelScope.launch {
            try {
                var page = targetPage
                var rows: List<SessionEntity>
                do {
                    val cursor = storyCursors[page]
                    rows = sessionDao.getRecentForCharacter(id, STORY_PAGE_SIZE + 1, cursor?.first, cursor?.second)
                    if (rows.isNotEmpty() || page == 0) break
                    page--
                } while (true)
                if (revision != storyRevision || characterRevision != loadRevision || _state.value.character?.id != id) return@launch
                val visible = rows.take(STORY_PAGE_SIZE)
                while (storyCursors.size > page + 1) storyCursors.removeAt(storyCursors.lastIndex)
                val hasNext = rows.size > STORY_PAGE_SIZE
                if (hasNext) visible.last().let { storyCursors.add(it.updatedAt to it.id) }
                _state.update { it.copy(recentStories = visible, storyPageIndex = page,
                    storyHasNext = hasNext, storiesLoading = false, storiesError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == storyRevision && characterRevision == loadRevision && _state.value.character?.id == id) {
                    _state.update { it.copy(storiesLoading = false, storiesError = "相关故事读取失败，请重试") }
                }
            }
        }
    }

    private companion object {
        const val STORY_PAGE_SIZE = 5
    }

    fun toggleFavorite() {
        val id = _state.value.character?.id ?: return
        val token = beginAction(id) ?: return
        viewModelScope.launch {
            try {
                characterDao.toggleFavorite(id)
                characterDataRevision++
                val refreshed = characterDao.getById(id)
                    ?: throw IllegalStateException("角色已不存在")
                if (ownsAction(id, token)) _state.updateAction { it.copy(character = refreshed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ownsAction(id, token)) _state.updateAction { it.copy(actionError = "收藏操作失败，请重试") }
            } finally {
                finishAction(id, token)
            }
        }
    }

    fun startChat(onCreated: (Long) -> Unit) {
        val id = _state.value.character?.id ?: return
        val token = beginAction(id) ?: return
        viewModelScope.launch {
            var createdSessionId: Long? = null
            var shouldDeliver = false
            try {
                when (val result = createSession.createForCharacter(id)) {
                    is CreateSessionUseCase.Result.Created -> createdSessionId = result.sessionId
                    CreateSessionUseCase.Result.CharacterNotFound -> if (ownsAction(id, token)) fail("找不到这个角色")
                    CreateSessionUseCase.Result.UnboundCharacter -> if (ownsAction(id, token)) fail("角色还没有绑定世界，请先编辑角色")
                    CreateSessionUseCase.Result.EncyclopediaMismatch -> if (ownsAction(id, token)) fail("角色与百科不一致，请先编辑角色")
                    CreateSessionUseCase.Result.EmptyParticipants -> if (ownsAction(id, token)) fail("角色尚未准备好")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ownsAction(id, token)) fail("创建对话失败，请重试")
            }
            finally {
                shouldDeliver = createdSessionId != null && ownsAction(id, token)
                finishAction(id, token)
            }
            if (shouldDeliver) onCreated(createdSessionId!!)
        }
    }

    fun consumeActionError() {
        _state.updateAction { it.copy(actionError = null) }
    }

    private fun fail(message: String) {
        _state.updateAction { it.copy(actionError = message) }
    }

    private fun beginAction(id: Long): Long? {
        if (_state.value.actionInProgress) return null
        val token = ++actionRevision
        actionOwnerId = id
        _state.updateAction { it.copy(actionInProgress = true, actionError = null) }
        return token
    }

    private fun ownsAction(id: Long, token: Long): Boolean =
        actionOwnerId == id && actionRevision == token && _state.value.character?.id == id

    private fun finishAction(id: Long, token: Long) {
        if (!ownsAction(id, token)) return
        actionOwnerId = null
        _state.updateAction { it.copy(actionInProgress = false) }
    }

    private inline fun MutableStateFlow<CharacterDetailState>.updateAction(
        transform: (CharacterDetailState) -> CharacterDetailState,
    ) = update(transform)
}
