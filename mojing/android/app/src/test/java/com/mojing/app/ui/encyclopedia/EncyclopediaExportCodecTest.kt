package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncyclopediaExportCodecTest {
    @Test
    fun exportAndImportRoundTripKeepsEntries() {
        val encyclopedia = EncyclopediaEntity(
            id = 7,
            name = "修仙世界",
            description = "九州",
            worldPrompt = "灵气复苏",
            gameplayMode = "自由剧情",
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
        )

        val json = EncyclopediaExportCodec.toJson(listOf(encyclopedia), mapOf(7L to listOf(entry)))
        val imported = EncyclopediaExportCodec.fromJson(json)

        assertTrue(json.contains("青云宗"))
        assertEquals(1, imported.size)
        assertEquals("修仙世界", imported.single().name)
        assertEquals(1, imported.single().entries.size)
        assertEquals("位于云海之上", imported.single().entries.single().content)
        assertEquals("宗门,山门", imported.single().entries.single().tags)
    }
}
