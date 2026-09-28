package com.mojing.app.ui.encyclopedia

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.domain.usecase.EncyclopediaJsonStreamParser
import com.mojing.app.domain.usecase.ImportedEntryFields
import com.mojing.app.domain.usecase.ImportedWorldFields
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class EncyclopediaExportCodecTest {
    @Test
    fun exportCanBeReadByStreamingImporterWithWorldAndEntryFields() = runTest {
        val encyclopedia = EncyclopediaEntity(
            id = 7,
            name = "修仙世界",
            description = "九州",
            coverImagePath = "covers/world.png",
            genreTags = "修仙,宗门",
            worldPrompt = "灵气复苏",
            gameplayMode = "自由剧情",
            antiCheatPrompt = "不得越级",
            narratorConfigJson = "{\"tone\":\"克制\"}",
        )
        val entry = EncyclopediaEntryEntity(
            id = 11,
            encyclopediaId = 7,
            title = "青云宗",
            entryType = "world",
            summary = "山门",
            content = "位于云海之上",
            tags = "宗门,山门",
            confidence = "confirmed",
            isFeatured = true,
            changeNote = "初始资料",
            coverImagePath = "covers/entry.png",
        )

        val json = EncyclopediaExportCodec.toJson(listOf(encyclopedia), mapOf(7L to listOf(entry)))
        val imported = JsonParser.parseString(json).asJsonObject
            .getAsJsonArray("data").single().asJsonObject
        val importedEntry = imported.getAsJsonArray("entries").single().asJsonObject

        assertTrue(json.contains("青云宗"))
        assertEquals("修仙世界", imported.get("name").asString)
        assertEquals("covers/world.png", imported.get("coverImagePath").asString)
        assertEquals("修仙,宗门", imported.get("genreTags").asString)
        assertEquals("不得越级", imported.get("antiCheatPrompt").asString)
        assertEquals("{\"tone\":\"克制\"}", imported.get("narratorConfigJson").asString)
        assertEquals("位于云海之上", importedEntry.get("content").asString)
        assertEquals("宗门,山门", importedEntry.get("tags").asString)
        assertTrue(importedEntry.get("isFeatured").asBoolean)
        assertEquals("初始资料", importedEntry.get("changeNote").asString)
        assertEquals("covers/entry.png", importedEntry.get("coverImagePath").asString)

        var restoredWorld: ImportedWorldFields? = null
        var restoredEntry: ImportedEntryFields? = null
        val result = EncyclopediaJsonStreamParser.parse(JsonReader(StringReader(json)),
            object : EncyclopediaJsonStreamParser.Sink {
                override suspend fun beginWorld() = 1L
                override suspend fun addEntry(worldId: Long, entry: ImportedEntryFields) {
                    restoredEntry = entry
                }
                override suspend fun finishWorld(worldId: Long, world: ImportedWorldFields) {
                    restoredWorld = world
                }
            })
        assertEquals(1, result.worlds)
        assertEquals(1, result.entries)
        assertEquals("修仙世界", restoredWorld?.name)
        assertEquals("位于云海之上", restoredEntry?.content)
    }
}
