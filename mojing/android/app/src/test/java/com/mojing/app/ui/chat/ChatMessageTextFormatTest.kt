package com.mojing.app.ui.chat

import com.mojing.app.media.TtsSpeakText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTextFormatTest {
    @Test fun actionPreviewShowsLongChapterLeadWithoutProtocolTags() {
        val chapter = "<NARRATION>" + "雨夜里的渡口。".repeat(600) +
            "</NARRATION><CHOICES><OPTION>继续</OPTION></CHOICES>"

        val preview = ChatMessageTextFormat.actionPreview(chapter, "character")

        assertTrue(preview.startsWith("雨夜里的渡口。"))
        assertFalse(preview.contains("<NARRATION>"))
        assertFalse(preview.contains("<OPTION>"))
        assertTrue(preview.length <= 240)
        assertEquals("此消息包含非文本内容", ChatMessageTextFormat.actionPreview(
            "<CHOICES><OPTION>继续</OPTION></CHOICES>".repeat(300), "character"))
    }

    @Test fun unchangedEditorBodyDoesNotCreateAStructuredReplyBranch() {
        val raw = "<NARRATION>雨落在窗上。</NARRATION><SPEECH>别回头。</SPEECH>" +
            "<CHOICES><OPTION>继续</OPTION></CHOICES>"
        val initial = ChatMessageTextFormat.visibleBody(raw, "character")
        assertFalse(ChatMessageTextFormat.hasEditChanges(raw, "character", initial))
        assertFalse(ChatMessageTextFormat.hasEditChanges(raw, "character", "  $initial\n"))
        org.junit.Assert.assertTrue(ChatMessageTextFormat.hasEditChanges(raw, "character", "$initial\n门开了。"))
    }

    @Test fun editComparisonUsesDisplayedWhitespaceAndPreservesUserTags() {
        val raw = "第一段。  空格。\n\n\n第二段。"
        assertFalse(ChatMessageTextFormat.hasEditChanges(raw, "user", ChatMessageTextFormat.visibleBody(raw, "user")))
        assertFalse(ChatMessageTextFormat.hasEditChanges(raw, "user", "  "))
        val literal = "<SPEECH>字面文本</SPEECH>"
        assertFalse(ChatMessageTextFormat.hasEditChanges(literal, "user", literal))
        org.junit.Assert.assertTrue(ChatMessageTextFormat.hasEditChanges(literal, "user", "字面文本"))
    }
    @Test fun userActionsPreserveLiteralModelTagsWhileGeneratedActionsUseProse() {
        val raw = "<SPEECH>字面文本</SPEECH><CHOICES><OPTION>选项原文</OPTION></CHOICES>"
        assertEquals(raw, ChatMessageTextFormat.visibleBody(raw, "user"))
        assertEquals(raw, ChatMessageTextFormat.forClipboard(raw, "user"))
        assertEquals(raw, ChatMessageTextFormat.quoteSnippet(raw, 120, "user"))
        assertEquals("字面文本", ChatMessageTextFormat.visibleBody(raw, "character"))
        assertEquals("字面文本", ChatMessageTextFormat.forClipboard(raw, "character"))
    }

    @Test fun userChoiceOnlyLiteralRemainsQuotableButGeneratedOptionsDoNot() {
        val raw = "<CHOICES><OPTION>保留</OPTION></CHOICES>"
        assertEquals(raw, ChatMessageTextFormat.quoteSnippet(raw, 120, "user"))
        assertEquals("", ChatMessageTextFormat.quoteSnippet(raw, 120, "character"))
        assertEquals("", ChatMessageTextFormat.quoteSnippet("> 林汐：旧引用\n\n", 120, "user"))
    }
    @Test fun quotingReplyUsesItsOwnBodyAndPreservesUserLiteralTags() {
        val reply = "> 林汐：码头见\n\n我马上来"
        assertEquals("我马上来", ChatMessageTextFormat.quoteSnippet(reply, 120, "user"))
        val literal = "我输入 <CHOICES><OPTION>保留原文</OPTION></CHOICES>"
        assertEquals(literal, ChatMessageTextFormat.quoteSnippet(literal, 120, "user"))
        assertEquals("> 风起了", ChatMessageTextFormat.quoteSnippet("> 风起了\n\n他关上窗。", 120, "character"))
    }

    @Test fun quoteLengthLimitKeepsEmojiWhole() {
        assertEquals("甲", ChatMessageTextFormat.quoteSnippet("甲😀乙", 2, "user"))
        assertEquals("甲😀", ChatMessageTextFormat.quoteSnippet("甲😀乙", 3, "user"))
        assertEquals("", ChatMessageTextFormat.quoteSnippet("😀", 1, "user"))
        assertEquals("", ChatMessageTextFormat.quoteSnippet("正文", -1, "user"))
    }
    @org.junit.Test
    fun quoteCardSeparatesSourceFromBodyAndPreservesOrdinaryText() {
        val quote = ChatMessageTextFormat.splitQuote("> 林汐：码头见\n\n我马上来")
        org.junit.Assert.assertEquals("林汐：码头见", quote.quote)
        org.junit.Assert.assertEquals("我马上来", quote.body)
        org.junit.Assert.assertNull(ChatMessageTextFormat.splitQuote("普通消息").quote)
        org.junit.Assert.assertEquals("> 未完成", ChatMessageTextFormat.splitQuote("> 未完成").body)
    }

    @Test fun searchPreviewIncludesLateMatchAndKeepsUnicodeBoundaries() {
        val raw = "😀".repeat(10000) + "灯塔线索" + "🌊".repeat(100)
        val preview = ChatMessageTextFormat.searchPreview(raw, "user", "灯塔线索")
        org.junit.Assert.assertTrue(preview.contains("灯塔线索"))
        org.junit.Assert.assertTrue(preview.length <= 122)
        assertEquals(preview, String(preview.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertEquals("hello WORLD", ChatMessageTextFormat.searchPreview("hello WORLD", "user", "world"))
        assertEquals("（无正文）", ChatMessageTextFormat.searchPreview("  ", "user", "x"))
    }

    @Test fun searchPreviewHidesOpenTagInNameOnlyChapterPrefix() {
        val prefix = ("<NARRATION>" + "雨夜里的渡口。".repeat(400)).take(2048)
        val preview = ChatMessageTextFormat.searchPreview(prefix, "character", "阿沅")
        assertTrue(preview.startsWith("雨夜里的渡口。"))
        assertFalse(preview.contains("<NARRATION>"))
        assertTrue(preview.length <= 121)
    }


    @Test
    fun `clipboard text follows visible structured body and excludes choices`() {
        val raw = "<NARRATION>夜色降临。</NARRATION>" +
            "<THOUGHT>不能让他知道。</THOUGHT>" +
            "<SPEECH name=\"林云\">快走。</SPEECH>" +
            "门外传来脚步声。" +
            "<CHOICES type=\"actions\"><OPTION id=\"wait\">留在原地</OPTION></CHOICES>"

        val copied = ChatMessageTextFormat.forClipboard(raw)

        assertEquals("🎭 夜色降临。\n\n💭 不能让他知道。\n\n快走。\n\n门外传来脚步声。", copied)
        assertFalse(copied.contains("<OPTION"))
        assertFalse(copied.contains("留在原地"))
    }

    @Test
    fun `clipboard text keeps ordinary prose formatting`() {
        val raw = "第一段。  有多余空格。\n\n\n\n第二段。"

        assertEquals("第一段。 有多余空格。\n\n第二段。", ChatMessageTextFormat.forClipboard(raw))
    }

    @Test
    fun `choice only message has no copyable body`() {
        val raw = "<CHOICES><OPTION>继续</OPTION><OPTION>离开</OPTION></CHOICES>"

        assertEquals("", ChatMessageTextFormat.forClipboard(raw))
    }

    @Test
    fun `quote and speech reuse visible body without choices`() {
        val raw = "<NARRATION>雨落在窗上。</NARRATION>\n" +
            "<SPEECH>别回头。</SPEECH>\n" +
            "<CHOICES type=\"actions\"><OPTION id=\"turn\">回头</OPTION></CHOICES>"

        assertEquals("🎭 雨落在窗上。", ChatMessageTextFormat.quoteSnippet(raw, 120))
        assertEquals(
            "雨落在窗上。 别回头。",
            TtsSpeakText.normalizeForSpeech(ChatMessageTextFormat.visibleBody(raw)),
        )
    }

    @Test
    fun `generated preview keeps visible prose and excludes unselected choices`() {
        val raw = "<NARRATION>雨落在窗上。</NARRATION>" +
            "<THOUGHT>必须尽快离开。</THOUGHT>" +
            "<SPEECH>别回头。</SPEECH>" +
            "<CHOICES type=\"actions\"><OPTION id=\"turn\">回头</OPTION></CHOICES>"

        val preview = ChatMessageTextFormat.preview(raw, speakerType = "character", maxChars = 120)

        assertEquals("雨落在窗上。 必须尽快离开。 别回头。", preview)
        assertFalse(preview.contains("<OPTION"))
        assertFalse(preview.contains("CHOICES"))
    }

    @Test
    fun `story library preview handles a truncated structured chapter`() {
        val rawPrefix = ("<NARRATION>" + "雨夜里的渡口。".repeat(300)).take(1024)

        assertEquals("雨夜里的渡口。雨夜里的渡口。", ChatMessageTextFormat.sessionListPreview(
            rawPrefix, speakerType = "narrator", maxChars = 14,
        ))
        assertEquals("门开了。", ChatMessageTextFormat.sessionListPreview(
            "<SPEECH name=\"林汐\">门开了。</SPEECH><CHOICES><OPTION>进去</OPTION>",
            speakerType = "character", maxChars = 72,
        ))
        assertEquals("", ChatMessageTextFormat.sessionListPreview(
            "<CHOICES><OPTION>进去</OPTION></CHOICES>", speakerType = "character", maxChars = 72,
        ))
    }

    @Test
    fun `story library preview keeps user supplied protocol text literal`() {
        val raw = "我输入 <CHOICES><OPTION>原样保留</OPTION></CHOICES>"
        assertEquals(raw, ChatMessageTextFormat.sessionListPreview(raw, speakerType = "user", maxChars = 72))
    }

    @Test
    fun `user preview preserves literal structured text`() {
        val raw = "我输入 <CHOICES><OPTION>原样保留</OPTION></CHOICES>"

        assertEquals(
            raw,
            ChatMessageTextFormat.preview(raw, speakerType = "user", maxChars = 120),
        )
    }
}
