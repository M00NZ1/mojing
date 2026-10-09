package com.mojing.app.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
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
        val EMPTY = StoryOpeningInputDraft("", "", DEFAULT_TONE, 3, null, null, emptySet())
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
    /** v1 records leave this null and are explicitly preview-only on restore. */
    val contentFileName: String? = null,
    val contentChars: Long = 0L,
    val completedChapterFileName: String? = null,
    val completedChapterCount: Int = 0,
)

class UnreadableStoryInputDraft(val raw: String) : IllegalStateException("Story input draft cannot be read")

/** Unsubmitted opening settings; the completed story and saved-session receipt have a separate owner. */
@Singleton
class StoryOpeningInputDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("story_opening_input_draft_v1", Context.MODE_PRIVATE)
    /** Single owner for the generation journal and both sidecar files. */
    private val generationMutex = Mutex()
    /** Character cursors for the active request's batch files; avoids rereading the body on every delta. */
    private val generationOffsets = mutableMapOf<String, Int>()
    private val appContext = context

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
            generationOffsets.clear()
            val previous = preferences.getString(GENERATION_KEY, null)?.let { raw ->
                runCatching { decodeGeneration(raw) }.getOrNull()
            }
            val safeId = state.requestId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val durable = state.copy(
                contentFileName = state.contentFileName ?: "$safeId.raw",
                completedChapterFileName = state.completedChapterFileName ?: "$safeId.chapters.json",
            )
            // A repeated request id is a fresh generation. Remove only its old sidecars
            // before publishing the new journal record; a different request is cleaned
            // only after the new record is durable so its source remains recoverable.
            if (previous?.requestId == state.requestId) {
                deleteGenerationFiles(previous.requestId, previous.contentFileName, previous.completedChapterFileName)
            }
            preferences.edit().putString(GENERATION_KEY, encodeGeneration(durable)).commitOrThrow()
            if (previous != null && previous.requestId != state.requestId) {
                val previousNames = setOf(previous.contentFileName, previous.completedChapterFileName)
                val currentNames = setOf(durable.contentFileName, durable.completedChapterFileName)
                if (previousNames.intersect(currentNames).isEmpty()) {
                    deleteGenerationFiles(previous.requestId, previous.contentFileName, previous.completedChapterFileName)
                }
            }
        }
    }

    /** Append the provider response to an app file; SharedPreferences only keeps its small cursor. */
    suspend fun appendGenerationDelta(requestId: String, delta: String, batchIndex: Int = 0, deduplicate: Boolean = false, offset: Int? = null): Boolean = withContext(Dispatchers.IO) {
        if (delta.isEmpty()) return@withContext true
        generationMutex.withLock {
            val raw = preferences.getString(GENERATION_KEY, null) ?: return@withLock false
            val current = runCatching { decodeGeneration(raw) }.getOrNull() ?: return@withLock false
            if (current.requestId != requestId || current.contentFileName == null) return@withLock false
            val file = if (batchIndex == 0) generationFile(current.contentFileName)
            else generationFile("${requestId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.batch$batchIndex.raw")
            file.parentFile?.mkdirs()
            val encoded = delta.toByteArray(StandardCharsets.UTF_8)
            if (offset != null) {
                require(offset >= 0) { "Generation delta offset must be non-negative" }
                val existingLength = generationOffsets[file.path] ?: if (file.isFile) {
                    file.readText(Charsets.UTF_8).length.also { generationOffsets[file.path] = it }
                } else 0
                when {
                    existingLength < offset -> return@withLock false
                    existingLength >= offset + delta.length -> {
                        val existing = file.readText(Charsets.UTF_8)
                        check(existing.regionMatches(offset, delta, 0, delta.length)) { "Generation delta conflicts with already written content" }
                        return@withLock true
                    }
                    existingLength != offset -> return@withLock false
                }
            }
            val lengthBeforeWrite = file.length()
            try {
                FileOutputStream(file, true).use { output ->
                    output.write(encoded)
                    output.fd.sync()
                }
            } catch (error: Throwable) {
                runCatching {
                    java.io.RandomAccessFile(file, "rw").use { output -> output.setLength(lengthBeforeWrite) }
                }
                generationOffsets.remove(file.path)
                throw error
            }
            if (offset != null) generationOffsets[file.path] = offset + delta.length
            true
        }
    }

    /** Replace the parsed chapters file for this request. It is safe to repeat after a retry. */
    suspend fun persistCompletedChapters(requestId: String, chapters: List<com.mojing.app.domain.story.StoryChapter>): Boolean = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val raw = preferences.getString(GENERATION_KEY, null) ?: return@withLock false
            val current = runCatching { decodeGeneration(raw) }.getOrNull() ?: return@withLock false
            if (current.requestId != requestId || current.completedChapterFileName == null) return@withLock false
            val file = generationFile(current.completedChapterFileName)
            file.parentFile?.mkdirs()
            atomicWrite(file, com.google.gson.Gson().toJson(chapters))
            val next = current.copy(completedChapterCount = chapters.size)
            preferences.edit().putString(GENERATION_KEY, encodeGeneration(next)).commitOrThrow()
            true
        }
    }

    suspend fun loadGenerationContent(state: StoryOpeningGenerationState): String? = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val name = state.contentFileName ?: return@withLock null
            val file = generationFile(name)
            if (!file.isFile) return@withLock null
            file.readText(Charsets.UTF_8)
        }
    }

    suspend fun loadGenerationBatches(state: StoryOpeningGenerationState): List<String> = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val directory = generationDirectory()
            if (!directory.isDirectory) return@withLock emptyList()
            val safeId = state.requestId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            directory.listFiles()?.filter { it.isFile && (it.name == state.contentFileName || it.name.startsWith("$safeId.batch") && it.name.endsWith(".raw")) }
                ?.sortedWith(compareBy { if (it.name == state.contentFileName) -1 else Regex(".*\\.batch(\\d+)\\.raw").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE })
                ?.map { it.readText(Charsets.UTF_8) }.orEmpty()
        }
    }

    suspend fun loadCompletedChapters(state: StoryOpeningGenerationState): List<com.mojing.app.domain.story.StoryChapter> = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val name = state.completedChapterFileName ?: return@withLock emptyList()
            val file = generationFile(name)
            if (!file.isFile) return@withLock emptyList()
            runCatching {
                com.google.gson.Gson().fromJson(file.readText(Charsets.UTF_8), Array<com.mojing.app.domain.story.StoryChapter>::class.java)
                    ?.toList().orEmpty()
            }.getOrDefault(emptyList())
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
            preferences.edit().putString(GENERATION_KEY, encodeGeneration(state.copy(
                contentFileName = current.contentFileName,
                contentChars = current.contentFileName?.let { generationFile(it).length() } ?: current.contentChars,
                completedChapterFileName = current.completedChapterFileName,
                completedChapterCount = current.completedChapterCount,
            ))).commitOrThrow()
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
            val state = runCatching { decodeGeneration(raw) }.getOrNull() ?: return@withLock
            if (requestId != null && state.requestId != requestId) return@withLock
            preferences.edit().remove(GENERATION_KEY).commitOrThrow()
            deleteGenerationFiles(state.requestId, state.contentFileName, state.completedChapterFileName)
            generationOffsets.clear()
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
        addProperty("version", 2)
        addProperty("requestId", state.requestId)
        add("input", JsonParser.parseString(encode(state.input)))
        addProperty("preview", state.preview)
        addProperty("model", state.model)
        addProperty("stage", state.stage)
        addProperty("receivedChars", state.receivedChars)
        addProperty("elapsedMs", state.elapsedMs)
        state.contentFileName?.let { addProperty("contentFileName", it) }
        addProperty("contentChars", state.contentChars)
        state.completedChapterFileName?.let { addProperty("completedChapterFileName", it) }
        addProperty("completedChapterCount", state.completedChapterCount)
    }.toString()

    private fun decode(raw: String): StoryOpeningInputDraft {
        val root = JsonParser.parseString(raw).asJsonObject
        val version = root.get("version")?.asInt ?: error("Missing generation version")
        require(version == 1)
        fun string(name: String): String = root.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: error("Missing story input field")
        fun optionalId(name: String): Long? = root.get(name)?.asLong?.also { require(it > 0L) }
        val count = root.get("chapterCount")?.asInt ?: error("Missing chapter count")
        require(count in 1..10)
        val ids = root.getAsJsonArray("characterIds")?.map { it.asLong.also { id -> require(id > 0L) } }?.toSet()
            ?: error("Missing character IDs")
        return StoryOpeningInputDraft(string("premise"), string("direction"), string("tone"), count,
            optionalId("templateId"), optionalId("encyclopediaId"), ids)
    }

    private fun decodeGeneration(raw: String): StoryOpeningGenerationState {
        val root = JsonParser.parseString(raw).asJsonObject
        val version = root.get("version")?.asInt ?: error("Missing generation version")
        require(version == 1 || version == 2)
        val requestId = root.get("requestId")?.asString?.takeIf { it.isNotBlank() } ?: error("Missing request id")
        val input = decode(root.getAsJsonObject("input").toString())
        val preview = root.get("preview")?.asString ?: ""
        val model = root.get("model")?.asString ?: ""
        val stage = root.get("stage")?.asString ?: "等待模型响应"
        val receivedChars = root.get("receivedChars")?.asInt ?: preview.length
        val elapsedMs = root.get("elapsedMs")?.asLong ?: 0L
        require(receivedChars >= 0 && elapsedMs >= 0)
        val contentFileName = if (version >= 2) root.get("contentFileName")?.asString else null
        val completedFileName = if (version >= 2) root.get("completedChapterFileName")?.asString else null
        return StoryOpeningGenerationState(requestId, input, preview, model, stage, receivedChars, elapsedMs,
            contentFileName = contentFileName,
            contentChars = root.get("contentChars")?.asLong ?: 0L,
            completedChapterFileName = completedFileName,
            completedChapterCount = root.get("completedChapterCount")?.asInt ?: 0)
    }

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Story generation state could not be saved" }
    }

    private companion object {
        const val KEY = "input"
        const val GENERATION_KEY = "generation"
    }

    private fun generationDirectory(): File = File(appContext.filesDir, "story-opening-generation")
    private fun generationFile(name: String): File = File(generationDirectory(), name)
    private fun deleteGenerationFiles(requestId: String, contentName: String? = null, completedName: String? = null) {
        val safeId = requestId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val batchFiles = generationDirectory().listFiles()?.filter { it.isFile && it.name.startsWith("$safeId.batch") && it.name.endsWith(".raw") }.orEmpty()
        val files = listOf(generationFile(contentName ?: "$safeId.raw"), generationFile(completedName ?: "$safeId.chapters.json")) + batchFiles
        files.forEach { file -> check(!file.exists() || file.delete()) { "Story generation file could not be cleared" } }
        files.forEach { generationOffsets.remove(it.path) }
    }

    private fun atomicWrite(target: File, value: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
