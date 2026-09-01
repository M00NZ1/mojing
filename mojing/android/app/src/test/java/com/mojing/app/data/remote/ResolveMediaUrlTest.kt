package com.mojing.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 百科封面等场景：后端返回相对路径时必须拼成绝对 URL。 */
class ResolveMediaUrlTest {

    @Test
    fun relativePathGetsHttpScheme() {
        val abs = resolveMediaUrlAgainstPublicBase(
            "https://api.example.com/v1",
            "/encyclopedia/entries/preview-cover-image/abc.png",
        )
        assertTrue(abs.startsWith("https://"))
        assertTrue(abs.contains("encyclopedia"))
    }

    @Test
    fun absoluteUrlUnchanged() {
        val url = "https://cdn.example.com/img.png"
        assertEquals(url, resolveMediaUrlAgainstPublicBase("https://api.example.com", url))
    }

    @Test
    fun emptyBaseReturnsRelativeAsIs() {
        val rel = "/encyclopedia/x.png"
        assertEquals(rel, resolveMediaUrlAgainstPublicBase("", rel))
    }
}
