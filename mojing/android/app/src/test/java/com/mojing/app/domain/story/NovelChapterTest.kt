package com.mojing.app.domain.story

import com.mojing.app.data.local.entity.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class NovelChapterTest {
    @Test fun renameOnlyChangesHeadingAndPreservesSameWordsInBody() {
        val original = "<NARRATION>夜雨\n\n她在夜雨中启程。</NARRATION>"
        val renamed = NovelChapter.renameContent(original, "夜雨", "渡口")
        assertEquals("<NARRATION>渡口\n\n她在夜雨中启程。</NARRATION>", renamed)
        assertEquals("<NARRATION>归航\n\n她在夜雨中启程。</NARRATION>",
            NovelChapter.renameContent(renamed, "渡口", "归航"))
    }

    @Test fun missingHeadingDoesNotReplaceMatchingStoryWords() {
        assertEquals("<NARRATION>渡口\n\n她在夜雨中启程。</NARRATION>",
            NovelChapter.renameContent("<NARRATION>她在夜雨中启程。</NARRATION>", "夜雨", "渡口"))
        assertEquals("渡口\n\n夜雨之后，她回到家。",
            NovelChapter.renameContent("夜雨之后，她回到家。", "夜雨", "渡口"))
    }

    @Test fun renamedChapterExportsOneHeadingAndUnchangedStory() = runTest {
        val message = MessageEntity(id = 1, sessionId = 1, speakerType = "narrator",
            content = NovelChapter.renameContent("<NARRATION>夜雨\n\n夜雨中的船。</NARRATION>", "夜雨", "渡口"),
            structuredContentJson = NovelChapter.metadata("{}", 2, "渡口"))
        val output = ByteArrayOutputStream()
        NovelChapter.export(output, "夜航", 1) { _, _ -> listOf(message) }
        assertEquals("夜航\n\n第 2 章 渡口\n\n夜雨中的船。\n\n", output.toString("UTF-8").replace("\r\n", "\n"))
    }

    @Test fun normalizedChapterKeepsTextWithoutChatDecoration() {
        val (heading, content) = NovelChapter.generated(2, "第二章 夜雨", "<NARRATION>第二章 渡口\n\n雨落在船上。</NARRATION><CHOICES><OPTION>上岸</OPTION></CHOICES>")
        val message = MessageEntity(sessionId = 1, speakerType = "narrator", content = content,
            structuredContentJson = NovelChapter.metadata("{}", 2, heading))
        assertEquals("第二章 夜雨", heading)
        assertEquals("雨落在船上。", NovelChapter.body(message))
        assertFalse(content.contains("CHOICES"))
    }
    @Test fun metadataPreservesUsageAndMarksDrafts() {
        val json = NovelChapter.draftMetadata("{\"generation_duration_ms\":2000}", 3, "第三章")
        assertTrue(NovelChapter.incomplete(json))
        assertEquals(3, NovelChapter.number(json))
        assertTrue(json.contains("generation_duration_ms"))
        assertNull(NovelChapter.number("broken"))
    }
    @Test fun exportUsesCursorAndSnapshotWithoutUserInstructions() = runTest {
        val messages = (1L..260L).map { id -> MessageEntity(id = id, sessionId = 1,
            speakerType = if (id == 2L) "user" else "narrator", content = if (id == 2L) "不要导出此指令" else "<NARRATION>正文$id</NARRATION>") }
        val cursors = mutableListOf<Long>()
        val output = ByteArrayOutputStream()
        val count = NovelChapter.export(output, "夜航", 259L) { after, limit ->
            cursors.add(after); messages.filter { it.id > after }.take(limit)
        }
        val text = output.toString("UTF-8")
        assertEquals(listOf(0L, 128L, 256L), cursors)
        assertEquals(258L, count)
        assertTrue(text.startsWith("夜航"))
        assertFalse(text.contains("不要导出"))
        assertFalse(text.contains("正文260"))
        assertFalse(text.contains("NARRATION"))
    }
}
