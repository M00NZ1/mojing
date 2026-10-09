package com.mojing.app.domain.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRecoveryParserTest {
    @Test
    fun parsesIndependentBatchesAndKeepsFullPartialContent() {
        val first = "{\"title\":\"书\",\"chapters\":[{\"title\":\"一\",\"content\":\"A\"},{\"title\":\"二\",\"content\":\"B\"},{\"title\":\"三\",\"content\":\"C\"}]}"
        val long = "D".repeat(20_001)
        val second = "{\"title\":\"书\",\"chapters\":[{\"title\":\"四\",\"content\":\"$long"
        val result = StoryRecoveryParser.parse(listOf(first, second), 3)
        assertEquals(listOf(4), result.partialChapter?.number?.let { listOf(it) })
        assertEquals(long, result.partialChapter?.content)
        assertTrue(result.chapters.isEmpty())
    }

    @Test
    fun doesNotDuplicatePersistedChaptersWhenACompleteBatchWasNotSnapshotted() {
        val batch = "{\"title\":\"书\",\"chapters\":[{\"title\":\"一\",\"content\":\"A\"},{\"title\":\"二\",\"content\":\"B\"}]}"
        val result = StoryRecoveryParser.parse(listOf(batch), 2)
        assertTrue(result.chapters.isEmpty())
    }

    @Test
    fun keepsTrailingUnclosedRootAfterACompleteRoot() {
        val raw = "{" + "\"chapters\":[{\"title\":\"第一章\",\"content\":\"A\"}]}" +
            "{\"chapters\":[{\"title\":\"第二章\",\"content\":\"${"B".repeat(20_000)}"
        val result = StoryRecoveryParser.parse(listOf(raw), 1, listOf(StoryChapter(1, "第一章", "A")))
        assertEquals("第二章", result.partialChapter?.title)
        assertEquals(20_000, result.partialChapter?.content?.length)
    }

    @Test
    fun keepsAllClosedLargeChaptersBeforeAnUnclosedLargeChapter() {
        val first = "A".repeat(20_001)
        val second = "B".repeat(20_001)
        val third = "C".repeat(20_001)
        val raw = "{\"chapters\":[" +
            "{\"title\":\"第一章\",\"content\":\"$first\"}," +
            "{\"title\":\"第二章\",\"content\":\"$second\"}," +
            "{\"title\":\"第三章\",\"content\":\"$third"
        val result = StoryRecoveryParser.parse(listOf(raw), 0)
        assertEquals(first, result.chapters[0].content)
        assertEquals(second, result.chapters[1].content)
        assertEquals(third, result.partialChapter?.content)
    }

    @Test
    fun incompleteUnicodeEscapeDoesNotConsumeEarlierClosedChapters() {
        val raw = "{\"chapters\":[{\"title\":\"一\",\"content\":\"完整\"},{\"title\":\"二\",\"content\":\"坏\\u12"
        val result = StoryRecoveryParser.parse(listOf(raw), 0)
        assertEquals("完整", result.chapters.single().content)
        assertEquals("坏", result.partialChapter?.content)
    }

    @Test
    fun completeChapterBatchDoesNotProducePartialRecovery() {
        val raw = """{"title":"书","chapters":[{"title":"第一章","content":"${"A".repeat(20_001)}"},{"title":"第二章","content":"${"B".repeat(20_001)}"}]}"""

        val result = StoryRecoveryParser.parse(listOf(raw), 0)

        assertEquals(2, result.chapters.size)
        assertEquals(null, result.partialChapter)
    }

    @Test
    fun persistedPartialWithSameTitleButDifferentBodyIsKept() {
        val raw = "{\"chapters\":[{\"title\":\"同名\",\"content\":\"新正文"
        val result = StoryRecoveryParser.parse(
            listOf(raw), 1, listOf(StoryChapter(1, "同名", "旧正文")),
        )
        assertEquals("新正文", result.partialChapter?.content)
    }

    @Test
    fun keepsNumberedUnseenBatchWhenSnapshotCountIsAhead() {
        val batch = """{"chapters":[{"title":"第三章","content":"C"},{"title":"第四章","content":"D"}]}"""
        val result = StoryRecoveryParser.parse(listOf(batch), 4, (1..4).map { StoryChapter(it, "已存$it", "已存正文$it") })
        assertEquals(listOf("C", "D"), result.chapters.map { it.content })
    }
}
