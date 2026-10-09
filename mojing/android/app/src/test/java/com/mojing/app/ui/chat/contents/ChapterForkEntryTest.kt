package com.mojing.app.ui.chat.contents

import com.mojing.app.data.local.dao.StoryContentsMessageProjection
import org.junit.Assert.*
import org.junit.Test

class ChapterForkEntryTest {
    private fun entry(json: String) = StoryContentsMessageProjection(id = 1, createdAt = 0, speakerType = "narrator", branchId = "main",
        structuredContentJson = json, contentPreview = "第一章 标题\n正文").toContentsEntry()
    @Test fun validIncompleteMetadataOffersFork() {
        assertTrue(entry("{\"chapter_number\":1,\"chapter_incomplete\":true}").canForkChapter)
    }
    @Test fun textHeadingCannotAuthorizeForkWithoutMetadataNumber() {
        val e = entry("{\"chapter_incomplete\":true}")
        assertEquals(1, e.chapterNumber)
        assertFalse(e.canForkChapter)
    }
    @Test fun completedMalformedAndNonpositiveNumberHaveNoFork() {
        listOf("{\"chapter_number\":1}", "{\"chapter_number\":0,\"chapter_incomplete\":true}", "bad").forEach {
            assertFalse(entry(it).canForkChapter)
        }
    }
}
