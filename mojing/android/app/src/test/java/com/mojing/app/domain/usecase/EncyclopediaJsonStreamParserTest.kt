package com.mojing.app.domain.usecase

import com.google.gson.stream.JsonReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class EncyclopediaJsonStreamParserTest {
    @Test
    fun parsesVersionTwoWorldsAndEntriesWithoutBuildingTheWholeDocument() = runTest {
        val json = """{"version":2,"type":"encyclopedias","data":[
            {"entries":[{"title":"青云宗","entryType":"world","content":"云海","isFeatured":true}],
             "name":"九州","worldPrompt":"灵气复苏","narratorConfigJson":"{\"tone\":\"克制\"}"},
            {"name":"空世界","entries":[]}
        ]}"""
        val saved = mutableListOf<Pair<ImportedWorldFields, List<ImportedEntryFields>>>()
        val entries = mutableMapOf<Long, MutableList<ImportedEntryFields>>()
        val progress = mutableListOf<EncyclopediaImportResult>()
        val result = EncyclopediaJsonStreamParser.parse(JsonReader(StringReader(json)),
            object : EncyclopediaJsonStreamParser.Sink {
                override suspend fun beginWorld(): Long = (saved.size + entries.size + 1).toLong().also {
                    entries[it] = mutableListOf()
                }
                override suspend fun addEntry(worldId: Long, entry: ImportedEntryFields) {
                    entries.getValue(worldId) += entry
                }
                override suspend fun finishWorld(worldId: Long, world: ImportedWorldFields) {
                    saved += world to entries.getValue(worldId)
                }
            }, progress::add)

        assertEquals(EncyclopediaImportResult(2, 1), result)
        assertEquals("九州", saved[0].first.name)
        assertEquals("灵气复苏", saved[0].first.worldPrompt)
        assertEquals("{\"tone\":\"克制\"}", saved[0].first.narratorConfigJson)
        assertEquals("云海", saved[0].second.single().content)
        assertEquals(true, saved[0].second.single().isFeatured)
        assertEquals("空世界", saved[1].first.name)
        assertEquals(listOf(EncyclopediaImportResult(1, 1), EncyclopediaImportResult(2, 1)), progress)
    }

    @Test
    fun malformedTailCannotBeReportedAsSuccessfulImport() {
        val json = """{"data":[{"name":"九州","entries":[{"title":"角色"}]},"""
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.test.runTest {
                EncyclopediaJsonStreamParser.parse(JsonReader(StringReader(json)),
                    object : EncyclopediaJsonStreamParser.Sink {
                        override suspend fun beginWorld() = 1L
                        override suspend fun addEntry(worldId: Long, entry: ImportedEntryFields) = Unit
                        override suspend fun finishWorld(worldId: Long, world: ImportedWorldFields) = Unit
                    })
            }
        }
    }
}
