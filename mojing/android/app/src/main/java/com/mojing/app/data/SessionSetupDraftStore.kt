package com.mojing.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Input values only; worlds and characters are reloaded by the existing form. */
data class SessionSetupDraft(
    val requestId: String,
    val title: String,
    val templateId: Long?,
    val encyclopediaId: Long?,
    val characterIds: List<Long>,
    val narratorEnabled: Boolean,
    val narratorName: String,
    val choiceEnabled: Boolean,
    val maxChoices: String,
    val antiCheatEnabled: Boolean,
    val displayLimit: String,
    val maxCharacterIdBeforeCreation: Long?,
    val selectNewCharacter: Boolean,
    val formEdited: Boolean,
    val initializeCharacterSelection: Boolean,
    val version: Int = 1,
)

@Singleton
class SessionSetupDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("session_setup_draft_v1", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val gson = Gson()

    private fun read(): SessionSetupDraft? {
        val raw = preferences.getString("draft", null) ?: return null
        val record = JsonParser.parseString(raw).asJsonObject
        listOf("requestId", "title", "narratorName", "maxChoices", "displayLimit").forEach {
            require(record.get(it)?.let { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString } == true)
        }
        listOf("narratorEnabled", "choiceEnabled", "antiCheatEnabled", "selectNewCharacter", "formEdited", "initializeCharacterSelection").forEach {
            require(record.get(it)?.let { value -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean } == true)
        }
        require(record.get("characterIds")?.isJsonArray == true)
        record.getAsJsonArray("characterIds").forEach { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asLong > 0L) }
        return gson.fromJson(raw, SessionSetupDraft::class.java).also {
            require(it.version == 1 && !it.requestId.isNullOrBlank()) { "无法读取新对话设定" }
            require(it.characterIds != null && it.title != null && it.maxChoices != null && it.displayLimit != null)
            require(it.narratorName != null)
        }
    }

    suspend fun load(): SessionSetupDraft? = mutex.withLock { withContext(Dispatchers.IO) { read() } }

    suspend fun save(draft: SessionSetupDraft) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(draft.requestId.isNotBlank())
            check(preferences.edit().putString("draft", gson.toJson(draft)).commit()) { "新对话设定暂存失败" }
        }
    }

    suspend fun clear(requestId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (read()?.requestId == requestId) {
                check(preferences.edit().remove("draft").commit()) { "新对话设定清除失败" }
            }
        }
    }
}
