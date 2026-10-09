package com.mojing.app.data

import android.content.Context
import android.content.SharedPreferences
import com.mojing.app.domain.story.StoryChapter
import com.mojing.app.domain.story.StoryRecoveryParser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoryOpeningInputDraftStoreTest {
    @Test
    fun rawDeltasOverTwelveThousandCharactersRemainExactAndOrdered() = runBlocking {
        val fixture = Fixture()
        val input = StoryOpeningInputDraft("背景", "", "风格", 3, null, null, emptySet())
        fixture.store.beginGeneration(StoryOpeningGenerationState("request", input, "", "model", "等待", 0, 0))
        val first = "{" + "x".repeat(8_000)
        val second = "y".repeat(8_000) + "}"
        fixture.store.appendGenerationDelta("request", first)
        fixture.store.appendGenerationDelta("request", second)

        val state = fixture.store.loadGeneration()!!
        assertEquals(first + second, fixture.store.loadGenerationContent(state))
        assertTrue(state.contentFileName!!.isNotBlank())
    }

    @Test
    fun parsedBatchesAreReplacedWithoutDuplicatingEarlierChapters() = runBlocking {
        val fixture = Fixture()
        val input = StoryOpeningInputDraft("背景", "", "风格", 5, null, null, emptySet())
        fixture.store.beginGeneration(StoryOpeningGenerationState("request", input, "", "model", "等待", 0, 0))
        fixture.store.persistCompletedChapters("request", listOf(StoryChapter(1, "一", "正文一"), StoryChapter(2, "二", "正文二")))
        fixture.store.persistCompletedChapters("request", listOf(
            StoryChapter(1, "一", "正文一"), StoryChapter(2, "二", "正文二"), StoryChapter(3, "三", "正文三"),
        ))
        val state = fixture.store.loadGeneration()!!
        assertEquals(listOf(1, 2, 3), fixture.store.loadCompletedChapters(state).map { it.number })
    }

    @Test
    fun legacyV1RecordLoadsAsPreviewOnly() {
        val fixture = Fixture()
        fixture.values["generation"] = """
            {"version":1,"requestId":"legacy","input":{"version":1,"premise":"背景","direction":"","tone":"风格","chapterCount":1,"characterIds":[]},"preview":"旧预览","model":"model","stage":"已停止","receivedChars":3,"elapsedMs":1}
        """.trimIndent()
        val state = fixture.store.loadGeneration()!!
        assertEquals("旧预览", state.preview)
        assertEquals(null, state.contentFileName)
    }

    @Test
    fun requestMismatchRejectsLateDelta() = runBlocking {
        val fixture = Fixture()
        fixture.store.beginGeneration(StoryOpeningGenerationState("new", StoryOpeningInputDraft.EMPTY, "", "", "", 0, 0))
        assertFalse(fixture.store.appendGenerationDelta("old", "late"))
        assertEquals(null, fixture.store.loadGenerationContent(fixture.store.loadGeneration()!!))
    }

    @Test
    fun offsetRetryIsIdempotentEvenWhenDeltaRepeatsAtTheFileEnd() = runBlocking {
        val fixture = Fixture()
        fixture.store.beginGeneration(StoryOpeningGenerationState("offset", StoryOpeningInputDraft.EMPTY, "", "", "", 0, 0))
        assertTrue(fixture.store.appendGenerationDelta("offset", "aaaa", offset = 0))
        assertTrue(fixture.store.appendGenerationDelta("offset", "aaaa", offset = 0))
        assertTrue(fixture.store.appendGenerationDelta("offset", "aa", offset = 4))
        assertEquals("aaaaaa", fixture.store.loadGenerationContent(fixture.store.loadGeneration()!!))
    }

    @Test
    fun offsetGapIsReportedAsUnwritten() = runBlocking {
        val fixture = Fixture()
        fixture.store.beginGeneration(StoryOpeningGenerationState("gap", StoryOpeningInputDraft.EMPTY, "", "", "", 0, 0))
        assertFalse(fixture.store.appendGenerationDelta("gap", "late", offset = 5))
        assertEquals(null, fixture.store.loadGenerationContent(fixture.store.loadGeneration()!!))
    }

    @Test
    fun clearIsRequestScopedAndConcurrentLateDeltasCannotRecreateTheJournal() = runBlocking {
        val fixture = Fixture()
        fixture.store.beginGeneration(StoryOpeningGenerationState("new", StoryOpeningInputDraft.EMPTY, "", "", "", 0, 0))
        awaitAll(
            async { fixture.store.appendGenerationDelta("old", "late-old") },
            async { fixture.store.appendGenerationDelta("new", "正文") },
            async { fixture.store.clearGeneration("new") },
        )
        assertEquals(null, fixture.store.loadGeneration())
        assertEquals(false, fixture.store.appendGenerationDelta("new", "late-new"))
    }

    @Test
    fun replacingCompletedChaptersIsAtomicAndRetryable() = runBlocking {
        val fixture = Fixture()
        fixture.store.beginGeneration(StoryOpeningGenerationState("request", StoryOpeningInputDraft.EMPTY, "", "", "", 0, 0))
        val first = listOf(StoryChapter(1, "一", "正文一"))
        val second = listOf(StoryChapter(1, "一", "正文一"), StoryChapter(2, "二", "正文二"))
        assertTrue(fixture.store.persistCompletedChapters("request", first))
        assertTrue(fixture.store.persistCompletedChapters("request", second))
        assertEquals(second, fixture.store.loadCompletedChapters(fixture.store.loadGeneration()!!))
        assertTrue(fixture.store.persistCompletedChapters("request", second))
        assertEquals(second, fixture.store.loadCompletedChapters(fixture.store.loadGeneration()!!))
    }

    @Test
    fun multiBatchJournalKeepsFourLargeChaptersAndAnUnclosedLargeCurrentChapter() = runBlocking {
        val fixture = Fixture()
        fixture.store.beginGeneration(StoryOpeningGenerationState("large", StoryOpeningInputDraft.EMPTY, "", "", "", 0, 0))
        fun root(start: Int, count: Int): String = "{\"title\":\"书\",\"chapters\":[" +
            (0 until count).joinToString(",") { index ->
                "{\"title\":\"第 ${start + index} 章\",\"content\":\"${('A'.code + index).toChar().toString().repeat(20_001)}\"}"
            } + "]}"
        fixture.store.appendGenerationDelta("large", root(1, 2), 0)
        fixture.store.appendGenerationDelta("large", root(3, 2), 1)
        val completed = (1..4).map { StoryChapter(it, "第 $it 章", "完整正文$it".repeat(20_001)) }
        fixture.store.persistCompletedChapters("large", completed)
        val partial = "{\"title\":\"书\",\"chapters\":[{\"title\":\"第五章\",\"content\":\"${"Z".repeat(20_001)}"
        fixture.store.appendGenerationDelta("large", partial, 2)

        val state = fixture.store.loadGeneration()!!
        val parsed = StoryRecoveryParser.parse(fixture.store.loadGenerationBatches(state), 4)
        assertEquals(4, fixture.store.loadCompletedChapters(state).size)
        assertEquals(20_001, parsed.partialChapter!!.content.length)
        assertEquals(5, parsed.partialChapter.number)
        assertTrue(fixture.store.loadGenerationBatches(state).all { it.isNotEmpty() })
    }

    @Test
    fun diskFailureIsReportedWithoutClaimingTheDeltaWasSaved() = runBlocking {
        val fixture = Fixture()
        File(fixture.root, "story-opening-generation").writeText("blocked")
        fixture.store.beginGeneration(StoryOpeningGenerationState("request", StoryOpeningInputDraft.EMPTY, "", "", "", 0, 0))
        val failure = runCatching { fixture.store.appendGenerationDelta("request", "正文") }.exceptionOrNull()
        assertTrue(failure is Exception)
        assertEquals("request", fixture.store.loadGeneration()!!.requestId)
        File(fixture.root, "story-opening-generation").delete()
        assertTrue(fixture.store.appendGenerationDelta("request", "正文"))
        assertEquals("正文", fixture.store.loadGenerationContent(fixture.store.loadGeneration()!!))
    }

    private class Fixture {
        val values = mutableMapOf<String, String?>()
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        private val preferences = mockk<SharedPreferences>(relaxed = true)
        val root = Files.createTempDirectory("story-opening-store").toFile()
        val store: StoryOpeningInputDraftStore

        init {
            every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { preferences.edit() } returns editor
            every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
            every { editor.remove(any()) } answers { values.remove(firstArg()); editor }
            every { editor.commit() } returns true
            val context = mockk<Context>(relaxed = true)
            every { context.filesDir } returns root
            every { context.getSharedPreferences(any(), any()) } returns preferences
            store = StoryOpeningInputDraftStore(context)
        }
    }
}
