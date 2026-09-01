package com.mojing.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StoryLinePresentationTest {
    @Test
    fun `main branch always uses the product label`() {
        assertEquals("主线剧情", storyLineDisplayLabel("main", "internal-main-label"))
    }

    @Test
    fun `blank branch label does not expose its internal id`() {
        assertEquals("未命名故事线", storyLineDisplayLabel("branch_42_internal", "  "))
    }

    @Test
    fun `parent label falls back without exposing an unknown id`() {
        assertEquals(
            "上一条故事线",
            storyLineParentLabel("branch_missing_internal", mapOf("main" to "主线剧情")),
        )
    }

    @Test
    fun `known parent uses its visible story line name`() {
        assertEquals(
            "雨夜之后",
            storyLineParentLabel(
                "branch_rain",
                mapOf("main" to "主线剧情", "branch_rain" to "雨夜之后"),
            ),
        )
    }
}
