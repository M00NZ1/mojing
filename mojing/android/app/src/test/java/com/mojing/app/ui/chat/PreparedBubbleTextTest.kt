package com.mojing.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedBubbleTextTest {
    @Test
    fun characterKeepsStructuredPartsAndPlainTail() {
        val text = prepareBubbleText(
            "<NARRATION>雨夜</NARRATION><THOUGHT>不安</THOUGHT>" +
                "<SPEECH>快走</SPEECH>门外有脚步声。<CHOICES><OPTION>开门</OPTION></CHOICES>",
            narrator = false,
        )

        assertTrue(text.structuredRenderable)
        assertEquals(listOf("雨夜"), text.narrations)
        assertEquals(listOf("不安"), text.thoughts)
        assertEquals(listOf("快走"), text.speeches)
        assertEquals(listOf("开门"), text.choices)
        assertEquals("门外有脚步声。", text.plainBody)
        assertEquals("", text.fallbackBody)
    }

    @Test
    fun narratorKeepsVisibleBodyAndChoices() {
        val text = prepareBubbleText(
            "<NARRATION>雨夜</NARRATION><CHOICES><OPTION>开门</OPTION></CHOICES>",
            narrator = true,
        )

        assertEquals("雨夜", text.fallbackBody)
        assertEquals(listOf("开门"), text.choices)
    }

    @Test
    fun unstructuredLongBodyRemainsComplete() {
        val raw = "长篇正文。".repeat(3000)
        val text = prepareBubbleText(raw, narrator = false)

        assertFalse(text.structuredRenderable)
        assertEquals(raw, text.fallbackBody)
    }

    @Test
    fun structuredLongSpeechIsNotTruncated() {
        val speech = "雨声渐近。".repeat(3000)
        val text = prepareBubbleText("<SPEECH>$speech</SPEECH>", narrator = false)

        assertEquals(listOf(speech), text.speeches)
    }

    @Test
    fun longUserMessageKeepsQuoteAndCompleteBody() {
        val body = "我马上来。".repeat(3000)
        val text = prepareUserBubbleText("> 林汐：码头见\n\n$body")

        assertEquals("林汐：码头见", text.quote)
        assertEquals(body, text.fallbackBody)
    }
}
