package com.mojing.app.engine

import com.mojing.app.domain.engine.TokenCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCounterTest {

    @Test
    fun emptyTextReturnsZero() {
        assertEquals(0, TokenCounter.estimate(""))
    }

    @Test
    fun chineseTextEstimation() {
        val n = TokenCounter.estimate("你好世界")
        assertTrue(n > 0)
    }

    @Test
    fun englishTextEstimation() {
        val n = TokenCounter.estimate("hello world")
        assertTrue(n > 0)
    }

    @Test
    fun mixedTextEstimation() {
        val n = TokenCounter.estimate("你好 world")
        assertTrue(n > 0)
    }
}
