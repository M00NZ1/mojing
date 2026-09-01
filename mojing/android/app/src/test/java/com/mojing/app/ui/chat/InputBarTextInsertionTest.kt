package com.mojing.app.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class InputBarTextInsertionTest {

    @Test
    fun insertsAtBeginning() {
        assertEquals(
            TextFieldValue("快捷词正文", TextRange(3)),
            insertTextAtSelection("正文", TextRange(0), "快捷词"),
        )
    }

    @Test
    fun insertsInMiddle() {
        assertEquals(
            TextFieldValue("前快捷词后", TextRange(4)),
            insertTextAtSelection("前后", TextRange(1), "快捷词"),
        )
    }

    @Test
    fun insertsAtEnd() {
        assertEquals(
            TextFieldValue("正文快捷词", TextRange(5)),
            insertTextAtSelection("正文", TextRange(2), "快捷词"),
        )
    }

    @Test
    fun replacesSelection() {
        assertEquals(
            TextFieldValue("前快捷词后", TextRange(4)),
            insertTextAtSelection("前旧内容后", TextRange(1, 4), "快捷词"),
        )
    }

    @Test
    fun clampsInvalidSelectionBeforeReplacing() {
        assertEquals(
            TextFieldValue("快捷词", TextRange(3)),
            insertTextAtSelection("原文", TextRange(0, 99), "快捷词"),
        )
    }

    @Test
    fun emojiReplacesSelectionAndKeepsCursorAfterFullSymbol() {
        assertEquals(
            TextFieldValue("前❤️后", TextRange(3)),
            insertTextAtSelection("前旧后", TextRange(1, 2), "❤️"),
        )
    }
}
