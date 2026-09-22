package com.mojing.app.ui.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.WorldEditDraft
import com.mojing.app.data.WorldEditDraftStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorldSettingsState(
    val world: EncyclopediaEntity? = null,
    val name: String = "",
    val description: String = "",
    val worldPrompt: String = "",
    val gameplayMode: String = "自由剧情",
    val antiCheatPrompt: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
    val saveError: String? = null,
    val saved: Boolean = false,
    val recoverableDraft: WorldEditDraft? = null,
    val draftError: String? = null,
)

@HiltViewModel
class WorldSettingsViewModel @Inject constructor(
    private val encyclopediaDao: EncyclopediaDao,
    private val draftStore: WorldEditDraftStore,
) : ViewModel() {
    private val _state = MutableStateFlow(WorldSettingsState())
    val state: StateFlow<WorldSettingsState> = _state.asStateFlow()
    private var worldId: Long = 0L
    private var savedSnapshot = ""
    private var loadJob: Job? = null

    fun load(id: Long) {
        if (_state.value.saving) return
        if (worldId == id && (loadJob?.isActive == true || !_state.value.loading && _state.value.error == null)) return
        loadJob?.cancel()
        worldId = id
        if (id <= 0L) {
            _state.value = WorldSettingsState(loading = false, error = "世界不存在，请返回世界列表重新选择")
            return
        }
        _state.value = WorldSettingsState(loading = true)
        loadJob = viewModelScope.launch {
            try {
                val world = encyclopediaDao.getById(id) ?: error("找不到这个世界")
                val draft = withContext(Dispatchers.IO) { draftStore.load(id) }
                if (worldId == id) {
                    applyLoaded(world)
                    _state.value = _state.value.copy(recoverableDraft = draft)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (worldId == id) _state.value = WorldSettingsState(loading = false, error = e.message ?: "读取世界失败，请重试")
            }
        }
    }

    fun retry() { load(worldId) }

    private fun applyLoaded(world: EncyclopediaEntity) {
        savedSnapshot = snapshot(world.name, world.description, world.worldPrompt, world.gameplayMode, world.antiCheatPrompt)
        _state.value = WorldSettingsState(world, world.name, world.description, world.worldPrompt, world.gameplayMode, world.antiCheatPrompt, loading = false)
    }

    private fun update(transform: (WorldSettingsState) -> WorldSettingsState) {
        if (_state.value.loading || _state.value.saving || _state.value.recoverableDraft != null) return
        val next = transform(_state.value).copy(saved = false, saveError = null)
        _state.value = next.copy(dirty = snapshot(next.name, next.description, next.worldPrompt, next.gameplayMode, next.antiCheatPrompt) != savedSnapshot)
        persistDraft()
    }

    private fun persistDraft() {
        val current = _state.value
        try {
            if (current.dirty) draftStore.save(worldId, WorldEditDraft(current.name, current.description, current.worldPrompt, current.gameplayMode, current.antiCheatPrompt))
            else draftStore.clear(worldId)
            _state.value = _state.value.copy(draftError = null)
        } catch (_: Exception) {
            _state.value = _state.value.copy(draftError = if (current.dirty) "草稿暂存失败，输入仍保留在页面中，请重试或保存世界" else "世界资料已保存，但旧草稿清除失败，请重试")
        }
    }

    fun retryDraft() {
        if (!_state.value.saving && !_state.value.loading && _state.value.recoverableDraft == null) persistDraft()
    }

    fun restoreDraft() {
        val draft = _state.value.recoverableDraft ?: return
        _state.value = _state.value.copy(recoverableDraft = null)
        update { it.copy(name = draft.name, description = draft.description, worldPrompt = draft.prompt, gameplayMode = draft.gameplay, antiCheatPrompt = draft.rules) }
    }

    fun discardDraft() {
        if (_state.value.saving) return
        try {
            draftStore.clear(worldId)
            _state.value = _state.value.copy(recoverableDraft = null, draftError = null)
        } catch (_: Exception) {
            _state.value = _state.value.copy(draftError = "草稿未能丢弃，请重试")
        }
    }

    fun updateName(v: String) = update { it.copy(name = v) }
    fun updateDescription(v: String) = update { it.copy(description = v) }
    fun updateWorldPrompt(v: String) = update { it.copy(worldPrompt = v) }
    fun updateGameplayMode(v: String) = update { it.copy(gameplayMode = v) }
    fun updateAntiCheatPrompt(v: String) = update { it.copy(antiCheatPrompt = v) }

    fun save() {
        val current = _state.value
        val world = current.world ?: return
        if (!current.dirty || current.saving || current.name.isBlank() || current.recoverableDraft != null) return
        _state.value = current.copy(saving = true, saveError = null)
        viewModelScope.launch {
            try {
                val saved = world.copy(
                    name = current.name.trim(), description = current.description,
                    worldPrompt = current.worldPrompt, gameplayMode = current.gameplayMode.trim().ifBlank { "自由剧情" },
                    antiCheatPrompt = current.antiCheatPrompt, updatedAt = System.currentTimeMillis(),
                )
                check(encyclopediaDao.updateWorldSettings(
                    saved.id, world.updatedAt, saved.name, saved.description, saved.worldPrompt,
                    saved.gameplayMode, saved.antiCheatPrompt, saved.updatedAt,
                ) == 1) { "世界已更新或删除，请返回后重新打开；当前输入仍保留" }
                applyLoaded(saved)
                _state.value = _state.value.copy(saved = true)
                persistDraft()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { _state.value = _state.value.copy(saving = false, saveError = e.message ?: "保存失败，请重试") }
        }
    }

    private fun snapshot(name: String, description: String, prompt: String, gameplay: String, antiCheat: String) = listOf(name, description, prompt, gameplay, antiCheat).joinToString("\u0000")
}
