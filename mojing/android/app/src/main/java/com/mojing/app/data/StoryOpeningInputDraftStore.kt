package com.mojing.app.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Durable request context used only while an opening request is in flight. */
data class StoryOpeningGenerationState(
    val requestId: String,
    val input: StoryOpeningInputDraft,
    val preview: String,
    val model: String,
    val stage: String,
    val receivedChars: Int,
    val elapsedMs: Long,
)

class UnreadableStoryInputDraft(val raw: String) : IllegalStateException("Story input draft cannot be read")

/** Unsubmitted opening settings; the completed story and saved-session receipt have a separate owner. */
@Singleton
class StoryOpeningInputDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("story_opening_input_draft_v1", Context.MODE_PRIVATE)
    private val generationMutex = Mutex()

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

    /** Records the exact request binding before any remote generation begins. */
    suspend fun beginGeneration(state: StoryOpeningGenerationState) = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            preferences.edit().putString(GENERATION_KEY, encodeGeneration(state)).commitOrThrow()
        }
    }

    /** True when this request's preview is durable (or a newer preview is already stored). */
    suspend fun persistGenerationPreview(state: StoryOpeningGenerationState): Boolean = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val existing = preferences.getString(GENERATION_KEY, null) ?: return@withLock false
            val current = decodeGeneration(existing)
            if (current.requestId != state.requestId) return@withLock false
            if (state.receivedChars < current.receivedChars ||
                (state.receivedChars == current.receivedChars && state.elapsedMs < current.elapsedMs)) return@withLock true
            preferences.edit().putString(GENERATION_KEY, encodeGeneration(state)).commitOrThrow()
            true
        }
    }

    fun loadGeneration(): StoryOpeningGenerationState? {
        val raw = preferences.getString(GENERATION_KEY, null) ?: return null
        return try { decodeGeneration(raw) } catch (_: Exception) { throw UnreadableStoryInputDraft(raw) }
    }

    suspend fun clearGeneration(requestId: String? = null) = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val raw = preferences.getString(GENERATION_KEY, null) ?: return@withLock
            if (requestId != null && runCatching { decodeGeneration(raw).requestId }.getOrNull() != requestId) return@withLock
            preferences.edit().remove(GENERATION_KEY).commitOrThrow()
        }
    }

    suspend fun discardUnreadable(expectedRaw: String) = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val generationRaw = preferences.getString(GENERATION_KEY, null)
            if (generationRaw == expectedRaw) {
                check(preferences.edit().remove(GENERATION_KEY).commit()) { "Story generation state could not be cleared" }
            } else {
                check(preferences.getString(KEY, null) == expectedRaw) { "Story input draft changed" }
                check(preferences.edit().remove(KEY).commit()) { "Story input draft could not be cleared" }
            }
        }
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

    private fun encodeGeneration(state: StoryOpeningGenerationState): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("requestId", state.requestId)
        add("input", JsonParser.parseString(encode(state.input)))
        addProperty("preview", state.preview)
        addProperty("model", state.model)
        addProperty("stage", state.stage)
        addProperty("receivedChars", state.receivedChars)
        addProperty("elapsedMs", state.elapsedMs)
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

    private fun decodeGeneration(raw: String): StoryOpeningGenerationState {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("version")?.asInt == 1)
        val requestId = root.get("requestId")?.asString?.takeIf { it.isNotBlank() } ?: error("Missing request id")
        val input = decode(root.getAsJsonObject("input").toString())
        val preview = root.get("preview")?.asString ?: ""
        val model = root.get("model")?.asString ?: ""
        val stage = root.get("stage")?.asString ?: "等待模型响应"
        val receivedChars = root.get("receivedChars")?.asInt ?: preview.length
        val elapsedMs = root.get("elapsedMs")?.asLong ?: 0L
        require(receivedChars >= 0 && elapsedMs >= 0)
        return StoryOpeningGenerationState(requestId, input, preview, model, stage, receivedChars, elapsedMs)
    }

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Story generation state could not be saved" }
    }

    private companion object {
        const val KEY = "input"
        const val GENERATION_KEY = "generation"
    }
}
