package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject

data class EncyclopediaImportResult(val worlds: Int, val entries: Int)

internal data class ImportedWorldFields(
    var name: String = "",
    var description: String = "",
    var coverImagePath: String = "",
    var genreTags: String = "",
    var worldPrompt: String = "",
    var gameplayMode: String = "自由剧情",
    var antiCheatPrompt: String = "",
    var narratorConfigJson: String = "{}",
)

internal data class ImportedEntryFields(
    var title: String = "",
    var entryType: String = "world",
    var summary: String = "",
    var content: String = "",
    var tags: String = "",
    var confidence: String = "confirmed",
    var isFeatured: Boolean = false,
    var changeNote: String = "",
    var coverImagePath: String = "",
)

/** Reads one world and one entry at a time; the caller owns the database transaction. */
internal object EncyclopediaJsonStreamParser {
    interface Sink {
        suspend fun beginWorld(): Long
        suspend fun addEntry(worldId: Long, entry: ImportedEntryFields)
        suspend fun finishWorld(worldId: Long, world: ImportedWorldFields)
    }

    suspend fun parse(
        reader: JsonReader,
        sink: Sink,
        onProgress: (EncyclopediaImportResult) -> Unit = {},
    ): EncyclopediaImportResult {
        var dataFound = false
        var worlds = 0
        var entries = 0
        suspend fun readData() {
            require(reader.peek() == JsonToken.BEGIN_ARRAY) { "百科数据不是数组" }
            reader.beginArray()
            while (reader.hasNext()) {
                currentCoroutineContext().ensureActive()
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                val worldId = sink.beginWorld()
                val world = ImportedWorldFields()
                reader.beginObject()
                var entriesFound = false
                while (reader.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    when (reader.nextName()) {
                        "name" -> world.name = scalar(reader)
                        "description" -> world.description = scalar(reader)
                        "coverImagePath" -> world.coverImagePath = scalar(reader)
                        "genreTags" -> world.genreTags = scalar(reader)
                        "worldPrompt" -> world.worldPrompt = scalar(reader)
                        "gameplayMode" -> world.gameplayMode = scalar(reader).ifBlank { "自由剧情" }
                        "antiCheatPrompt" -> world.antiCheatPrompt = scalar(reader)
                        "narratorConfigJson" -> world.narratorConfigJson = scalar(reader).ifBlank { "{}" }
                        "entries" -> {
                            require(!entriesFound) { "百科词条数组重复" }
                            entriesFound = true
                            require(reader.peek() == JsonToken.BEGIN_ARRAY) { "百科词条不是数组" }
                            reader.beginArray()
                            while (reader.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                    sink.addEntry(worldId, readEntry(reader))
                                    entries++
                                    if (entries % 32 == 0) onProgress(EncyclopediaImportResult(worlds, entries))
                                } else reader.skipValue()
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(world.name.trim().isNotEmpty()) { "百科名称为空，导入未保存" }
                sink.finishWorld(worldId, world)
                worlds++
                onProgress(EncyclopediaImportResult(worlds, entries))
            }
            reader.endArray()
        }

        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                dataFound = true
                readData()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    if (reader.nextName() == "data") {
                        require(!dataFound) { "百科数据数组重复" }
                        dataFound = true
                        readData()
                    } else reader.skipValue()
                }
                reader.endObject()
            }
            else -> throw IllegalArgumentException("未找到百科数据")
        }
        require(dataFound && reader.peek() == JsonToken.END_DOCUMENT) { "百科 JSON 不完整" }
        return EncyclopediaImportResult(worlds, entries)
    }

    private fun readEntry(reader: JsonReader): ImportedEntryFields {
        val entry = ImportedEntryFields()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> entry.title = scalar(reader)
                "entryType" -> entry.entryType = scalar(reader).ifBlank { "world" }
                "summary" -> entry.summary = scalar(reader)
                "content" -> entry.content = scalar(reader)
                "tags" -> entry.tags = scalar(reader)
                "confidence" -> entry.confidence = scalar(reader).ifBlank { "confirmed" }
                "isFeatured" -> entry.isFeatured = scalar(reader).equals("true", ignoreCase = true)
                "changeNote" -> entry.changeNote = scalar(reader)
                "coverImagePath" -> entry.coverImagePath = scalar(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return entry
    }

    private fun scalar(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> { reader.skipValue(); "" }
    }
}

/** A parse, storage, or cancellation failure rolls back every world and character mirror. */
class ImportEncyclopediaJsonUseCase @Inject constructor(
    private val database: AppDatabase,
    private val saveCharacterEntry: SaveCharacterEntryUseCase,
) {
    suspend fun import(
        input: InputStream,
        onProgress: (EncyclopediaImportResult) -> Unit = {},
        onCommitted: (EncyclopediaImportResult) -> Unit = {},
    ): EncyclopediaImportResult = withContext(Dispatchers.IO) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        val result = database.withTransaction {
            EncyclopediaJsonStreamParser.parse(reader, object : EncyclopediaJsonStreamParser.Sink {
                override suspend fun beginWorld(): Long = database.encyclopediaDao().upsert(
                    EncyclopediaEntity(name = "导入中"),
                )

                override suspend fun addEntry(worldId: Long, entry: ImportedEntryFields) {
                    saveCharacterEntry(EncyclopediaEntryEntity(
                        encyclopediaId = worldId,
                        title = entry.title,
                        entryType = entry.entryType,
                        summary = entry.summary,
                        content = entry.content,
                        tags = entry.tags,
                        confidence = entry.confidence,
                        isFeatured = entry.isFeatured,
                        changeNote = entry.changeNote,
                        coverImagePath = entry.coverImagePath,
                        metaJson = "{}",
                        sourceSessionId = null,
                        sourceMessageId = null,
                    ))
                }

                override suspend fun finishWorld(worldId: Long, world: ImportedWorldFields) {
                    database.encyclopediaDao().upsert(EncyclopediaEntity(
                        id = worldId,
                        name = world.name.trim(),
                        description = world.description,
                        coverImagePath = world.coverImagePath,
                        genreTags = world.genreTags,
                        worldPrompt = world.worldPrompt,
                        gameplayMode = world.gameplayMode,
                        antiCheatPrompt = world.antiCheatPrompt,
                        narratorConfigJson = world.narratorConfigJson,
                    ))
                }
            }, onProgress)
        }
        onCommitted(result)
        result
    }
}
