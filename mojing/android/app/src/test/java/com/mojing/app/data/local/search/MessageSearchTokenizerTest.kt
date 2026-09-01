package com.mojing.app.data.local.search

import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSearchTokenizerTest {
    @Test
    fun normalizesCompatibilityCharactersAndCase() {
        assertEquals("abc 天气", MessageSearchTokenizer.normalize("ＡＢＣ 天气"))
    }

    @Test
    fun indexContainsSessionUnicodeUnigramsAndBigramsWithoutRawContent() {
        val indexed = MessageSearchTokenizer.index(
            MessageEntity(sessionId = 42L, content = "天气A🙂"),
        )

        assertEquals("天气a🙂", indexed.searchNormalized)
        assertTrue(indexed.searchTerms.contains("s2a"))
        assertTrue(indexed.searchTerms.contains("u005929"))
        assertTrue(indexed.searchTerms.contains("b005929006c14"))
        assertTrue(indexed.searchTerms.contains("b00006101f642"))
        assertFalse(indexed.searchTerms.contains("天气"))
    }

    @Test
    fun matchExpressionUsesSessionAndAllShortQueryBigrams() {
        val expression = MessageSearchTokenizer.matchExpression(42L, "天气预报")

        assertTrue(expression.contains("\"s2a\""))
        assertTrue(expression.contains("\"b005929006c14\""))
        assertTrue(expression.contains("\"b006c14009884\""))
        assertTrue(expression.contains("\"b0098840062a5\""))
    }

    @Test
    fun longQueryCapsFtsClausesButKeepsBothEnds() {
        val query = buildString {
            repeat(80) { index -> appendCodePoint(0x4E00 + index) }
        }
        val clauses = MessageSearchTokenizer.matchExpression(7L, query).split(' ')

        assertEquals(25, clauses.size)
        assertEquals("\"s7\"", clauses.first())
        assertTrue(clauses[1].contains("b004e00004e01"))
        assertTrue(clauses.last().contains("b004e4e004e4f"))
    }

    @Test
    fun generatedMessageIndexExcludesUnselectedChoices() {
        val indexed = MessageSearchTokenizer.index(
            MessageEntity(
                sessionId = 7L,
                speakerType = "character",
                content = "<SPEECH>门已经打开。</SPEECH>" +
                    "<CHOICES><OPTION>进入密室</OPTION><OPTION>转身离开</OPTION></CHOICES>",
            ),
        )

        assertTrue(indexed.searchNormalized.contains("门已经打开"))
        assertFalse(indexed.searchNormalized.contains("进入密室"))
        assertFalse(indexed.searchNormalized.contains("转身离开"))
    }

    @Test
    fun userMessageIndexPreservesLiteralOptionText() {
        val indexed = MessageSearchTokenizer.index(
            MessageEntity(
                sessionId = 7L,
                speakerType = "user",
                content = "请保留 <OPTION>用户原文</OPTION>",
            ),
        )

        assertTrue(indexed.searchNormalized.contains("用户原文"))
    }
}
