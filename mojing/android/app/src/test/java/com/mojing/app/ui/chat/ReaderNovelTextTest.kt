package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.dao.StoryContentsMessageProjection
import com.mojing.app.domain.story.NovelChapter
import com.mojing.app.ui.chat.contents.toContentsEntry
import org.junit.Assert.*
import org.junit.Test

class ReaderNovelTextTest {
    @Test fun namedNovelTitleMatchesOnlyItsFirstParagraphAndKeepsSource() {
        val raw = "<NARRATION>灯塔来信\n\n港口依旧安静。\n\n灯塔来信</NARRATION>"
        val message = MessageEntity(sessionId = 7, speakerType = "narrator", content = raw,
            structuredContentJson = NovelChapter.metadata("{}", 30, "灯塔来信"))
        val paragraphs = prepareReaderParagraphs(message)
        assertEquals(listOf("灯塔来信", "港口依旧安静。", "灯塔来信"), paragraphs)
        assertTrue(isReaderChapterTitle(message, 0, paragraphs[0]))
        assertFalse(isReaderChapterTitle(message, 1, paragraphs[1]))
        assertFalse(isReaderChapterTitle(message, 2, paragraphs[2]))
        assertFalse(isReaderChapterTitle(message, 0, "另一个标题"))
        assertEquals(raw, message.content)
    }

    @Test fun metadataDoesNotPromoteUserCharacterOrOrdinaryNarrationToNamedHeading() {
        val message = MessageEntity(sessionId = 7, content = "灯塔来信",
            structuredContentJson = NovelChapter.metadata("{}", 30, "灯塔来信"))
        assertFalse(isReaderChapterTitle(message, 0, message.content))
        assertFalse(isReaderChapterTitle(message.copy(speakerType = "character"), 0, message.content))
        assertFalse(isReaderChapterTitle(message.copy(speakerType = "narrator", structuredContentJson = "{}"), 0, message.content))
        assertFalse(isReaderChapterTitle(message.copy(speakerType = "narrator", structuredContentJson = "broken"), 0, message.content))
        assertFalse(isReaderChapterTitle(message.copy(speakerType = "narrator", structuredContentJson = "{\"chapter_number\":30,\"chapter_title\":\"\"}"), 0, message.content))
    }

    @Test fun numberedLegacyHeadingStillUsesExistingReaderStyle() {
        val message = MessageEntity(sessionId = 7, speakerType = "narrator")
        assertTrue(isReaderChapterTitle(message, 0, "第 30 章 灯塔来信"))
        assertTrue(isReaderChapterTitle(message, 0, "第 3 节 旧港"))
        assertFalse(isReaderChapterTitle(message, 1, "第 30 章 灯塔来信"))
    }

    @Test fun structuredNovelHasPlainHeadingAndQuotedParagraphWithoutChangingSource() {
        val raw = "<NARRATION>第 30 章 灯塔来信\n\n港口依旧安静。\n\n> 原约定仍在。</NARRATION><CHOICES><OPTION>继续</OPTION></CHOICES>"
        val message = MessageEntity(sessionId = 7, speakerType = "narrator", content = raw,
            structuredContentJson = NovelChapter.metadata("{}", 30, "灯塔来信"))
        assertEquals(listOf("第 30 章 灯塔来信", "港口依旧安静。", "> 原约定仍在。"), prepareReaderParagraphs(message))
        assertEquals(raw, message.content)
    }

    @Test fun literalUserMarkupAndConversationLabelsStayIntact() {
        val user = MessageEntity(sessionId = 7, content = "<NARRATION>用户原字</NARRATION>\n\n🎭 自己写的符号")
        assertEquals(listOf("<NARRATION>用户原字</NARRATION>", "🎭 自己写的符号"), prepareReaderParagraphs(user))
        val conversation = user.copy(speakerType = "narrator", content = "<NARRATION>普通对话</NARRATION>")
        assertEquals(listOf("🎭 普通对话"), prepareReaderParagraphs(conversation))
    }

    @Test fun truncatedDirectoryProjectionRemovesProtocolWithoutReadingMoreBody() {
        val prefix = "<NARRATION>第 30 章 灯塔来信\n\n港口原文<CHOICES><OPTION>不展示"
        val entry = StoryContentsMessageProjection(30, "narrator", "child", 0,
            NovelChapter.metadata("{}", 30, "灯塔来信"), prefix).toContentsEntry()
        assertEquals("第 30 章 灯塔来信 港口原文", entry.preview)
        assertEquals(30, entry.chapterNumber)
        assertEquals("灯塔来信", entry.title)
        assertEquals("child", entry.sourceBranchId)
    }
}
