package com.mojing.app.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncyclopediaBatchContextTest {

    @Test
    fun preGenNotesOnly() {
        val out = combinedUserContextForBatch(
            EncyclopediaBatchPayload(
                encyclopediaId = 1,
                encyclopediaName = "测试",
                worldBackground = "",
                entryType = "character",
                count = 1,
                minWords = 100,
                maxWords = 200,
                userContext = "",
                preGenNotes = "  突出主线  ",
            ),
        )
        assertTrue(out.contains("【用户生成前补充】"))
        assertTrue(out.contains("突出主线"))
    }

    @Test
    fun userContextOnly() {
        val out = combinedUserContextForBatch(
            EncyclopediaBatchPayload(
                encyclopediaId = 1,
                encyclopediaName = "测试",
                worldBackground = "bg",
                entryType = "character",
                count = 1,
                minWords = 100,
                maxWords = 200,
                userContext = "修仙世界观",
            ),
        )
        assertEquals("修仙世界观", out)
    }

    @Test
    fun mergesPreGenAndUserContext() {
        val out = combinedUserContextForBatch(
            EncyclopediaBatchPayload(
                encyclopediaId = 1,
                encyclopediaName = "测试",
                worldBackground = "",
                entryType = "character",
                count = 1,
                minWords = 100,
                maxWords = 200,
                userContext = "世界观段落",
                preGenNotes = "补充要求",
            ),
        )
        assertTrue(out.contains("【用户生成前补充】"))
        assertTrue(out.contains("补充要求"))
        assertTrue(out.contains("【世界观 / 其它上下文】"))
        assertTrue(out.contains("世界观段落"))
    }
}
