package com.mojing.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {
    @Test
    fun splitsAtPunctuationWithoutExceedingLimit() {
        val chunks = SpeechChunks.split("第一句。第二句！第三句？", 5)
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), chunks)
        assertTrue(chunks.all { it.length <= 5 })
    }

    @Test
    fun neverSplitsSurrogatePair() {
        val chunks = SpeechChunks.split("甲😀乙😀丙", 2)
        assertEquals("甲😀乙😀丙", chunks.joinToString(""))
        assertTrue(chunks.none { it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test
    fun hardSplitsLongText() {
        val chunks = SpeechChunks.split("abcdefghij", 3)
        assertEquals(listOf("abc", "def", "ghi", "j"), chunks)
    }
}
