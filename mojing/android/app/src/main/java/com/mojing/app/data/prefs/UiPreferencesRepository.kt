package com.mojing.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.moJingUiPreferences by preferencesDataStore(name = "mojing_ui_preferences")

@Singleton
class UiPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.moJingUiPreferences

    private val characterListLayoutKey = stringPreferencesKey("character_list_layout")
    private val encyclopediaListLayoutKey = stringPreferencesKey("encyclopedia_list_layout")
    private val workbenchListLayoutKey = stringPreferencesKey("workbench_list_layout")
    private val chatDensityKey = stringPreferencesKey("chat_density")
    private val quickStartGuideDismissedKey = booleanPreferencesKey("quick_start_guide_dismissed")

    /** `"list"` | `"grid"` */
    val characterListLayout: Flow<String> = dataStore.data.map { prefs ->
        when (prefs[characterListLayoutKey]) {
            "grid" -> "grid"
            else -> "list"
        }
    }

    suspend fun setCharacterListLayout(mode: String) {
        dataStore.edit { prefs ->
            prefs[characterListLayoutKey] = if (mode == "grid") "grid" else "list"
        }
    }

    /** `"list"` | `"grid"` — 百科列表；封面仅在网格展示 */
    val encyclopediaListLayout: Flow<String> = dataStore.data.map { prefs ->
        when (prefs[encyclopediaListLayoutKey]) {
            "grid" -> "grid"
            else -> "list"
        }
    }

    suspend fun setEncyclopediaListLayout(mode: String) {
        dataStore.edit { prefs ->
            prefs[encyclopediaListLayoutKey] = if (mode == "grid") "grid" else "list"
        }
    }

    /** `"list"` | `"grid"` — 工坊模板列表；封面仅在网格展示 */
    val workbenchListLayout: Flow<String> = dataStore.data.map { prefs ->
        when (prefs[workbenchListLayoutKey]) {
            "grid" -> "grid"
            else -> "list"
        }
    }

    suspend fun setWorkbenchListLayout(mode: String) {
        dataStore.edit { prefs ->
            prefs[workbenchListLayoutKey] = if (mode == "grid") "grid" else "list"
        }
    }

    /** `"comfortable"` | `"compact"` | `"reader"` — 与 Web `data-chat-density` 三档一致 */
    val chatDensity: Flow<String> = dataStore.data.map { prefs ->
        when (prefs[chatDensityKey]) {
            "compact" -> "compact"
            "reader" -> "reader"
            else -> "comfortable"
        }
    }

    suspend fun setChatDensity(mode: String) {
        dataStore.edit { prefs ->
            prefs[chatDensityKey] = when (mode) {
                "compact", "reader" -> mode
                else -> "comfortable"
            }
        }
    }

    /** 与 Web `GuidePanel` 对应：主对话 Tab 空列表时是否不再自动弹出「三步上手」 */
    val quickStartGuideDismissed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[quickStartGuideDismissedKey] == true
    }

    suspend fun dismissQuickStartGuide() {
        dataStore.edit { prefs ->
            prefs[quickStartGuideDismissedKey] = true
        }
    }

    suspend fun getLastChatBranch(sessionId: Long): String {
        if (sessionId <= 0L) return MAIN_BRANCH
        return dataStore.data.first()[lastChatBranchKey(sessionId)]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: MAIN_BRANCH
    }

    suspend fun setLastChatBranch(sessionId: Long, branchId: String) {
        if (sessionId <= 0L) return
        val normalized = branchId.trim().ifBlank { MAIN_BRANCH }
        dataStore.edit { prefs ->
            if (normalized == MAIN_BRANCH) {
                prefs.remove(lastChatBranchKey(sessionId))
            } else {
                prefs[lastChatBranchKey(sessionId)] = normalized
            }
        }
    }

    suspend fun clearLastChatBranch(sessionId: Long) {
        if (sessionId <= 0L) return
        dataStore.edit { prefs -> prefs.remove(lastChatBranchKey(sessionId)) }
    }

    private fun lastChatBranchKey(sessionId: Long) =
        stringPreferencesKey("chat_last_branch_$sessionId")

    private companion object {
        const val MAIN_BRANCH = "main"
    }
}
