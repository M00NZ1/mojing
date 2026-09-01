package com.mojing.app.engine

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.ConversationMessageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationMessageTextTest {
    @Test
    fun `generated message keeps story body and excludes thought and choices`() {
        val message = message(
            speakerType = "character",
            content = "<NARRATION>门缓缓打开。</NARRATION>" +
                "<THOUGHT>这是陷阱。</THOUGHT>" +
                "<SPEECH>我们进去。</SPEECH>" +
                "<CHOICES type=\"actions\"><OPTION id=\"leave\">转身离开</OPTION></CHOICES>",
        )

        val text = ConversationMessageText.forDerivedContext(message)

        assertEquals("门缓缓打开。\n\n我们进去。", text)
        assertFalse(text.contains("这是陷阱"))
        assertFalse(text.contains("转身离开"))
        assertFalse(text.contains("<OPTION"))
    }

    @Test
    fun `free form generated choices are excluded after normalization`() {
        val message = message(
            speakerType = "narrator",
            content = "雨停了。\n\n可选行动：\n1. 推门\n2. 等待",
        )

        assertEquals("雨停了。", ConversationMessageText.forDerivedContext(message))
    }

    @Test
    fun `user literal tags remain original input`() {
        val raw = "我输入的 <OPTION> 只是普通原文"

        assertEquals(raw, ConversationMessageText.forDerivedContext(message("user", raw)))
    }

    private fun message(speakerType: String, content: String) = MessageEntity(
        id = 1L,
        sessionId = 7L,
        speakerType = speakerType,
        content = content,
    )
}
