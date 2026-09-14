package com.mojing.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MessageSearchHighlightTest {
    @Test fun mapsFullWidthAndRepeatedChineseMatchesToOriginal() {
        assertEquals(listOf(2..4), messageSearchRanges("你好ＡＢＣ世界", "abc"))
        assertEquals(listOf(0..1, 3..4), messageSearchRanges("夜雨，夜雨", "夜雨"))
    }
    @Test fun mapsCombinedUnicodeAndLiteralPunctuation() {
        assertEquals(listOf(0..1), messageSearchRanges("e\u0301船", "é"))
        assertEquals(listOf(2..4), messageSearchRanges("🌙a.b", "a.b"))
        assertTrue(messageSearchRanges("正文", "  ").isEmpty())
        assertTrue(messageSearchRanges("正文", "不存在").isEmpty())
    }
    @Test fun highlightRetainsOriginalBodyAndAppliesVisibleSpan() {
        val text = "前文 夜雨 后文"
        val marked = highlightedMessageText(text, messageSearchRanges(text, "夜雨"))
        assertEquals(text, marked.text)
        assertEquals(3, marked.spanStyles.single().start)
        assertEquals(5, marked.spanStyles.single().end)
        assertNotEquals(marked.spanStyles.single().item.color, marked.spanStyles.single().item.background)
    }
}
