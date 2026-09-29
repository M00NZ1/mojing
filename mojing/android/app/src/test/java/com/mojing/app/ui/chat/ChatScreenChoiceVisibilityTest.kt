package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenChoiceVisibilityTest {
    @Test
    fun characterHistoryKeepsProseAndChoicesWithoutShowingEmptyPlaceholders() {
        fun message(content: String) = MessageEntity(sessionId = 1, speakerType = "character", content = content)

        assertTrue(shouldShowCharacterBubbleLine(message("长篇正文。".repeat(10000)), emptyList()))
        assertTrue(shouldShowCharacterBubbleLine(message("字面 <tag> 正文"), emptyList()))
        assertTrue(shouldShowCharacterBubbleLine(message("<CHOICES><OPTION>继续</OPTION></CHOICES>"), emptyList()))
        assertFalse(shouldShowCharacterBubbleLine(message("<NARRATION></NARRATION>"), emptyList()))
        assertFalse(shouldShowCharacterBubbleLine(message("（本条仅含自动配图/语音指令）"), emptyList()))
        assertTrue(shouldShowCharacterBubbleLine(message(""), listOf(MessageAttachmentEntity(messageId = 1))))
    }

    @Test
    fun currentChoicesStayVisibleWhileImeIsOpen() {
        val choices = listOf("推门进入", "先观察四周")

        assertTrue(shouldShowRoundChoices(isImeOpen = true, choices = choices, isGenerating = false))
        assertTrue(shouldShowRoundChoices(isImeOpen = false, choices = choices, isGenerating = false))
        assertFalse(shouldShowRoundChoices(isImeOpen = true, choices = emptyList(), isGenerating = false))
        assertFalse(shouldShowRoundChoices(isImeOpen = true, choices = choices, isGenerating = true))
    }
}
