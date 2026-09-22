package com.mojing.app.ui.chat.contents

import com.mojing.app.data.local.dao.StoryContentsMessageProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryContentsTest {
    @Test
    fun metadataTitleAndChapterNumberArePreferred() {
        val entry = projection("{\"chapter_number\":2,\"chapter_title\":\"雾中的钟声\"}", "正文").toContentsEntry()
        assertEquals("雾中的钟声", entry.title)
        assertEquals(2, entry.chapterNumber)
    }

    @Test
    fun plainChapterHeadingProvidesReadableFallback() {
        val entry = projection("{}", "#  第十二章 远方来信\n修钟师推开门").toContentsEntry()
        assertEquals("第十二章 远方来信", entry.title)
        assertEquals(12, entry.chapterNumber)
        assertTrue(entry.preview.startsWith("#"))
    }

    @Test
    fun malformedChapterNumberDoesNotCrashOrInventIndex() {
        val entry = projection("{\"chapter_number\":\"oops\"}", "旁白：余波仍在").toContentsEntry()
        assertEquals(null, entry.chapterNumber)
        assertTrue(entry.title.startsWith("片段 ·"))
    }

    @Test
    fun pageOrderIsPreservedForAppendingOlderRows() {
        val entries = listOf(
            projection("{\"chapter_number\":4}", "第四章").copy(id = 40L),
            projection("{\"chapter_number\":3}", "第三章").copy(id = 30L),
        ).toContentsEntries()
        assertEquals(listOf(40L, 30L), entries.map { it.messageId })
    }

    @Test
    fun chineseHundredsThousandsAndDigitStyleHeadingsKeepTheirNumber() {
        mapOf("一百二十三" to 123, "一百零二" to 102, "两百" to 200,
            "一千零一" to 1001, "九千九百九十九" to 9999, "二〇二四" to 2024,
            "十" to 10, "二十" to 20, "002" to 2).forEach { (text, expected) ->
            assertEquals(text, expected, projection("{}", "第${text}章 远行").toContentsEntry().chapterNumber)
        }
    }

    @Test
    fun invalidChapterMetadataFallsBackToHeading() {
        assertEquals(123, projection("{\"chapter_number\":0}", "第一百二十三章").toContentsEntry().chapterNumber)
        assertEquals(1001, projection("{\"chapter_number\":-1}", "第一千零一章").toContentsEntry().chapterNumber)
    }

    @Test
    fun malformedChineseUnitOrderDoesNotInventAChapterNumber() {
        listOf("十百", "一百百", "一二十", "零", "0").forEach { text ->
            assertEquals(text, null, projection("{}", "第${text}章").toContentsEntry().chapterNumber)
        }
    }

    private fun projection(json: String, content: String) = StoryContentsMessageProjection(
        id = 42L, speakerType = "narrator", branchId = "main", createdAt = 0L,
        structuredContentJson = json, contentPreview = content,
    )
}
