package com.mojing.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class WorldEditDraft(
    val name: String,
    val description: String,
    val prompt: String,
    val gameplay: String,
    val rules: String,
)

/** World editor drafts are separate from saved world records and chat drafts. */
@Singleton
class WorldEditDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("world_edit_drafts_v1", Context.MODE_PRIVATE)

    suspend fun load(id: Long): WorldEditDraft? = withContext(Dispatchers.IO) {
        if (id <= 0L) return@withContext null
        if (!preferences.getBoolean("${id}_present", false)) return@withContext null
        fun text(field: String) = preferences.getString("${id}_$field", "").orEmpty()
        WorldEditDraft(text("name"), text("description"), text("prompt"), text("gameplay"), text("rules"))
    }

    suspend fun save(id: Long, draft: WorldEditDraft) = withContext(Dispatchers.IO) {
        require(id > 0L)
        check(preferences.edit().putBoolean("${id}_present", true)
            .putString("${id}_name", draft.name).putString("${id}_description", draft.description)
            .putString("${id}_prompt", draft.prompt).putString("${id}_gameplay", draft.gameplay)
            .putString("${id}_rules", draft.rules).commit()) { "世界草稿暂存失败" }
    }

    suspend fun clear(id: Long) = withContext(Dispatchers.IO) {
        if (id <= 0L) return@withContext
        val editor = preferences.edit()
        listOf("present", "name", "description", "prompt", "gameplay", "rules").forEach { editor.remove("${id}_$it") }
        check(editor.commit()) { "世界草稿清除失败" }
    }
}
