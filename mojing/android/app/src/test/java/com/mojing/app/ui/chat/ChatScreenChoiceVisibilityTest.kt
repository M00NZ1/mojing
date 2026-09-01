package com.mojing.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenChoiceVisibilityTest {
    @Test
    fun currentChoicesStayVisibleWhileImeIsOpen() {
        val choices = listOf("推门进入", "先观察四周")

        assertTrue(shouldShowRoundChoices(isImeOpen = true, choices = choices, isGenerating = false))
        assertTrue(shouldShowRoundChoices(isImeOpen = false, choices = choices, isGenerating = false))
        assertFalse(shouldShowRoundChoices(isImeOpen = true, choices = emptyList(), isGenerating = false))
        assertFalse(shouldShowRoundChoices(isImeOpen = true, choices = choices, isGenerating = true))
    }
}
