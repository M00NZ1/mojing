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

    private fun projection(json: String, content: String) = StoryContentsMessageProjection(
        id = 42L, speakerType = "narrator", branchId = "main", createdAt = 0L,
        structuredContentJson = json, contentPreview = content,
    )
}
