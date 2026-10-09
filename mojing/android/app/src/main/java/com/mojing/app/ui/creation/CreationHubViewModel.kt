package com.mojing.app.ui.creation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.entity.SessionWithListMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal sealed interface CreationProjects {
    data object Loading : CreationProjects
    data object Failed : CreationProjects
    data class Ready(val items: List<SessionWithListMeta>) : CreationProjects
}

@HiltViewModel
class CreationHubViewModel @Inject constructor(private val sessions: SessionDao) : ViewModel() {
    private val state = MutableStateFlow<CreationProjects>(CreationProjects.Loading)
    internal val projects = state.asStateFlow()
    private var job: Job? = null
    init { retry() }
    fun retry() {
        job?.cancel()
        state.value = CreationProjects.Loading
        job = viewModelScope.launch {
            sessions.observeRecentProjectsWithMeta().catch { state.value = CreationProjects.Failed }
                .collect { state.value = CreationProjects.Ready(it) }
        }
    }
}
