package com.mojing.app.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoryOpeningInputDraft(
    val premise: String,
    val direction: String,
    val tone: String,
    val chapterCount: Int,
    val templateId: Long?,
    val encyclopediaId: Long?,
    val characterIds: Set<Long>,
) {
    companion object {
        const val DEFAULT_TONE = "有画面感、人物动机清楚、适合连续长篇创作"
        val EMPTY = StoryOpeningInputDraft("", "", DEFAULT_TONE, 2, null, null, emptySet())
    }
}

class UnreadableStoryInputDraft(val raw: String) : IllegalStateException("Story input draft cannot be read")

/** Unsubmitted opening settings; the completed story and saved-session receipt have a separate owner. */
@Singleton
class StoryOpeningInputDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("story_opening_input_draft_v1", Context.MODE_PRIVATE)

    suspend fun load(): StoryOpeningInputDraft? = withContext(Dispatchers.IO) {
        val raw = preferences.getString(KEY, null) ?: return@withContext null
        try { decode(raw) } catch (_: Exception) { throw UnreadableStoryInputDraft(raw) }
    }

    fun save(draft: StoryOpeningInputDraft) {
        val editor = preferences.edit()
        if (draft == StoryOpeningInputDraft.EMPTY) editor.remove(KEY).apply()
        else editor.putString(KEY, encode(draft)).apply()
    }

    /** Finish the latest input write before leaving this screen or starting a remote request. */
    suspend fun commit(draft: StoryOpeningInputDraft) = withContext(Dispatchers.IO) {
        val editor = preferences.edit()
        if (draft == StoryOpeningInputDraft.EMPTY) editor.remove(KEY)
        else editor.putString(KEY, encode(draft))
        check(editor.commit()) { "Story input draft could not be saved" }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(KEY).commit()) { "Story input draft could not be cleared" }
    }

    suspend fun discardUnreadable(expectedRaw: String) = withContext(Dispatchers.IO) {
        check(preferences.getString(KEY, null) == expectedRaw) { "Story input draft changed" }
        check(preferences.edit().remove(KEY).commit()) { "Story input draft could not be cleared" }
    }

    private fun encode(draft: StoryOpeningInputDraft): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("premise", draft.premise)
        addProperty("direction", draft.direction)
        addProperty("tone", draft.tone)
        addProperty("chapterCount", draft.chapterCount)
        draft.templateId?.let { addProperty("templateId", it) }
        draft.encyclopediaId?.let { addProperty("encyclopediaId", it) }
        add("characterIds", JsonArray().also { ids -> draft.characterIds.sorted().forEach { ids.add(it) } })
    }.toString()

    private fun decode(raw: String): StoryOpeningInputDraft {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("version")?.asInt == 1)
        fun string(name: String): String = root.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: error("Missing story input field")
        fun optionalId(name: String): Long? = root.get(name)?.asLong?.also { require(it > 0L) }
        val count = root.get("chapterCount")?.asInt ?: error("Missing chapter count")
        require(count in 1..3)
        val ids = root.getAsJsonArray("characterIds")?.map { it.asLong.also { id -> require(id > 0L) } }?.toSet()
            ?: error("Missing character IDs")
        return StoryOpeningInputDraft(string("premise"), string("direction"), string("tone"), count,
            optionalId("templateId"), optionalId("encyclopediaId"), ids)
    }

    private companion object { const val KEY = "input" }
}
