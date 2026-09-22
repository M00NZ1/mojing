package com.mojing.app.ui.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
)

@HiltViewModel
class WorldSettingsViewModel @Inject constructor(
    private val encyclopediaDao: EncyclopediaDao,
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
                if (worldId == id) applyLoaded(world)
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
        val next = transform(_state.value).copy(saved = false, saveError = null)
        _state.value = next.copy(dirty = snapshot(next.name, next.description, next.worldPrompt, next.gameplayMode, next.antiCheatPrompt) != savedSnapshot)
    }

    fun updateName(v: String) = update { it.copy(name = v) }
    fun updateDescription(v: String) = update { it.copy(description = v) }
    fun updateWorldPrompt(v: String) = update { it.copy(worldPrompt = v) }
    fun updateGameplayMode(v: String) = update { it.copy(gameplayMode = v) }
    fun updateAntiCheatPrompt(v: String) = update { it.copy(antiCheatPrompt = v) }

    fun save() {
        val current = _state.value
        val world = current.world ?: return
        if (!current.dirty || current.saving || current.name.isBlank()) return
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
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { _state.value = _state.value.copy(saving = false, saveError = e.message ?: "保存失败，请重试") }
        }
    }

    private fun snapshot(name: String, description: String, prompt: String, gameplay: String, antiCheat: String) = listOf(name, description, prompt, gameplay, antiCheat).joinToString("\u0000")
}
