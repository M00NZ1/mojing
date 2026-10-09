package com.mojing.app.story

import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.story.StoryWritingException
import com.mojing.app.domain.story.StoryWritingUseCase
import com.mojing.app.domain.story.StoryCanon
import com.mojing.app.data.remote.ChatMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import com.google.gson.JsonParser
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.coroutines.CancellationException
import android.content.Context
import android.content.SharedPreferences

class StoryWritingUseCaseTest {
    private val llmRetry = mockk<LlmRetry>()
    private val useCase = StoryWritingUseCase(llmRetry)

    @Test
    fun writeIncludesSavedUserProfileInStoryRequest() = kotlinx.coroutines.runBlocking {
        val messages = slot<List<ChatMessage>>()
        coEvery {
            llmRetry.chatCompletionStreamingWithRetry(
                apiKey = any(), baseUrl = any(), model = any(), messages = capture(messages),
                temperature = any(), maxTokens = any(), onDelta = any(), onRetry = any(), onAttempt = any(),
            )
        } returns """{"title":"故事","chapters":[{"title":"第一章","content":"正文"}],"next_choices":["向东","向西"]}"""

        useCase.write(
            "key", "https://api.example.com", "model",
            com.mojing.app.domain.story.StoryWritingRequest(
                premise = "雾港来信", chapterCount = 1, personaName = "沈砚",
                userDescription = "喜欢慢节奏推理",
            ),
        )

        val prompt = messages.captured.joinToString("\n") { it.content }
        assertTrue(prompt.contains("姓名：沈砚"))
        assertTrue(prompt.contains("喜欢慢节奏推理"))
    }

    @Test
    fun singleChapterObjectIsAcceptedWithoutLosingItsBody() {
        val result = useCase.parse(
            """{"title":"雾港","chapters":{"title":"来信","content":"灯塔下出现了一封信。"},"next_choices":["拆开信封","寻找送信人"]}""",
            1,
        )
        assertEquals("灯塔下出现了一封信。", result.chapters.single().content)
        assertEquals("来信", result.chapters.single().title)
    }

    @Test
    fun malformedFieldsProduceStoryErrorsInsteadOfInternalCastErrors() {
        listOf(
            """{"chapters":null}""",
            """{"chapters":"一章正文"}""",
            """{"chapters":[{"content":"正文"}],"next_choices":{}}""",
            """{"chapters":[{"content":"正文"}],"next_choices":null}""",
        ).forEach { raw ->
            val error = runCatching { useCase.parse(raw, 1) }.exceptionOrNull()
            assertTrue("Expected a story format error, got $error", error is StoryWritingException)
        }
    }

    @Test(expected = StoryWritingException::class)
    fun singleChapterObjectCannotSatisfyTwoChapterRequest() {
        useCase.parse("""{"chapters":{"content":"正文"},"next_choices":["向东","向西"]}""", 2)
    }

    @Test
    fun parseAcceptsWrappedJsonAndLegacyNarrativeField() {
        val result = useCase.parse(
            """结果如下：{"title":"十八岁系统","chapters":[
                {"title":"觉醒","narrative":"林默听见系统提示。"},
                {"title":"任务","content":"第一个任务随即出现。"}
            ],"next_choices":["调查系统来源","先完成任务","先完成任务"]} 完毕""",
            expectedChapterCount = 2,
        )

        assertEquals("十八岁系统", result.title)
        assertEquals(listOf("林默听见系统提示。", "第一个任务随即出现。"), result.chapters.map { it.content })
        assertEquals(listOf("调查系统来源", "先完成任务"), result.nextChoices)
    }

    @Test(expected = StoryWritingException::class)
    fun parseRejectsMissingChapter() {
        useCase.parse(
            """{"chapters":[{"title":"唯一一章","content":"正文"}],"next_choices":["A","B"]}""",
            expectedChapterCount = 2,
        )
    }

    @Test(expected = StoryWritingException::class)
    fun parseRejectsMissingDynamicChoices() {
        useCase.parse(
            """{"chapters":[{"title":"第一章","content":"正文"}],"next_choices":["唯一选项"]}""",
            expectedChapterCount = 1,
        )
    }

    @Test
    fun messageContentKeepsChapterAndClickableChoices() {
        val result = useCase.parse(
            """{"title":"故事","chapters":[{"title":"第一章","content":"正文"}],"next_choices":["向东","向西"]}""",
            1,
        )
        val content = useCase.toMessageContent(result.chapters.single(), result.nextChoices)
        val structured = JsonParser.parseString(useCase.toStructuredJson(result.chapters.single(), result.nextChoices)).asJsonObject

        assertTrue(content.contains("<NARRATION>"))
        assertTrue(content.contains("<OPTION>向东</OPTION>"))
        assertEquals("story_writing", structured.get("mode").asString)
        assertEquals(2, structured.getAsJsonArray("choices").size())
    }

    @Test
    fun explicitSecretAndMundaneWorldConstraintsFilterLeakingChoices() {
        val premise = "普通都市世界，没有额外力量。只有主角拥有系统，别人都不知道。"
        val result = useCase.parse(
            """{"title":"故事","chapters":[{"title":"第一章","content":"正文"}],"next_choices":["告诉好友系统真相","觉醒魔法","继续隐藏系统完成任务","独自调查系统来源"]}""",
            expectedChapterCount = 1,
            premise = premise,
        )

        assertEquals(listOf("继续隐藏系统完成任务", "独自调查系统来源"), result.nextChoices)
    }

    @Test
    fun continuationSanitizerRemovesChoiceThatPresupposesSecretDisclosure() {
        val content = "<NARRATION>正文</NARRATION><CHOICES><OPTION>女主得知主角的系统秘密</OPTION><OPTION>主角继续隐瞒系统</OPTION></CHOICES>"

        val sanitized = StoryCanon.sanitizeMessageChoices(
            content,
            "普通都市，只有主角知道系统，其他人不知道。",
        )

        assertTrue(!sanitized.contains("女主得知"))
        assertTrue(sanitized.contains("主角继续隐瞒系统"))
    }

    @Test
    fun deepSeekProfileUsesConsistencyFocusedSampling() {
        assertTrue(StoryCanon.modelInstruction("deepseek-chat").contains("DeepSeek 写作适配"))
        assertEquals(0.76f, StoryCanon.temperatureFor("deepseek-chat"))
        assertEquals(0.82f, StoryCanon.temperatureFor("gpt-4o"))
    }

    @Test
    fun fiveChapterWritingUsesThreeThenTwoBatchesAndReportsContinuity() = kotlinx.coroutines.runBlocking {
        val prompts = mutableListOf<String>()
        val progress = mutableListOf<com.mojing.app.domain.story.StoryWritingProgress>()
        var calls = 0
        coEvery {
            llmRetry.chatCompletionStreamingWithRetry(
                apiKey = any(), baseUrl = any(), model = any(), messages = any(),
                temperature = any(), maxTokens = any(), onDelta = any(), onRetry = any(), onAttempt = any(),
            )
        } coAnswers {
            calls += 1
            prompts += arg<List<ChatMessage>>(3).joinToString("\n") { it.content }
            when (calls) {
                1 -> """{"title":"雾港","chapters":[{"title":"一","content":"第一章正文"},{"title":"二","content":"第二章正文"},{"title":"三","content":"第三章正文"}],"next_choices":["继续调查","返回灯塔"]}"""
                2 -> """{"title":"雾港","chapters":[{"title":"四","content":"第四章正文"},{"title":"五","content":"第五章正文"}],"next_choices":["追踪线索","等待回信"]}"""
                else -> error("unexpected batch")
            }
        }

        val result = useCase.write("key", "https://api.example.com", "model",
            com.mojing.app.domain.story.StoryWritingRequest("雾港来信", chapterCount = 5),
        ) { progress += it }

        assertEquals(2, calls)
        assertEquals(listOf(1, 2, 3, 4, 5), result.chapters.map { it.number })
        assertTrue(prompts[1].contains("第一章正文"))
        assertTrue(prompts[1].contains("第三章正文"))
        assertTrue(progress.any { it.completedChapters == 3 && it.totalChapters == 5 })
        assertTrue(progress.any { it.completedChapters == 5 && it.totalChapters == 5 })
    }

    @Test
    fun tenChapterWritingUsesThreeThreeThreeOneBatches() = kotlinx.coroutines.runBlocking {
        val batchSizes = mutableListOf<Int>()
        var calls = 0
        coEvery {
            llmRetry.chatCompletionStreamingWithRetry(
                apiKey = any(), baseUrl = any(), model = any(), messages = any(),
                temperature = any(), maxTokens = any(), onDelta = any(), onRetry = any(), onAttempt = any(),
            )
        } coAnswers {
            calls += 1
            val onDelta = arg<(String) -> Unit>(7)
            val count = when (calls) { 1, 2, 3 -> 3; 4 -> 1; else -> error("unexpected batch") }
            batchSizes += count
            onDelta.invoke("batch$calls")
            val chapters = (1..count).joinToString(",") { index ->
                "{\"title\":\"章节\",\"content\":\"第${calls}_${index}章\"}"
            }
            "{\"title\":\"长篇\",\"chapters\":[$chapters],\"next_choices\":[\"继续\",\"暂停\"]}"
        }

        val progress = mutableListOf<com.mojing.app.domain.story.StoryWritingProgress>()
        val result = useCase.write("key", "https://api.example.com", "model",
            com.mojing.app.domain.story.StoryWritingRequest("长篇背景", chapterCount = 10),
        ) { progress += it }

        assertEquals(listOf(3, 3, 3, 1), batchSizes)
        assertEquals((1..10).toList(), result.chapters.map { it.number })
        assertTrue(progress.any { it.completedChapters == 9 && it.totalChapters == 10 })
        assertTrue(progress.any { it.completedChapters == 10 && it.totalChapters == 10 })
    }

    @Test
    fun cancellationDuringLaterBatchKeepsCompletedPreviewAndStopsAtCancelledBatch() = kotlinx.coroutines.runBlocking {
        val progress = mutableListOf<com.mojing.app.domain.story.StoryWritingProgress>()
        var calls = 0
        coEvery {
            llmRetry.chatCompletionStreamingWithRetry(
                apiKey = any(), baseUrl = any(), model = any(), messages = any(),
                temperature = any(), maxTokens = any(), onDelta = any(), onRetry = any(), onAttempt = any(),
            )
        } coAnswers {
            calls += 1
            if (calls == 2) throw CancellationException("user stopped")
            """{"title":"雾港","chapters":[{"title":"一","content":"已完成正文一"},{"title":"二","content":"已完成正文二"},{"title":"三","content":"已完成正文三"}],"next_choices":["继续","返回"]}"""
        }

        val error = runCatching {
            useCase.write("key", "https://api.example.com", "model",
                com.mojing.app.domain.story.StoryWritingRequest("雾港", chapterCount = 5),
            ) { progress += it }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(2, calls)
        assertTrue(progress.any { it.completedChapters == 3 && it.preview.contains("已完成正文三") })
        assertFalse(progress.any { it.completedChapters >= 5 })
    }

    @Test
    fun inputDraftCodecAcceptsTenChaptersAndKeepsLegacyTwoChapterDrafts() = kotlinx.coroutines.runBlocking {
        val prefs = mockk<SharedPreferences>()
        val context = mockk<Context> { every { getSharedPreferences(any(), any()) } returns prefs }
        val store = com.mojing.app.data.StoryOpeningInputDraftStore(context)
        val tenChapter = """{"version":1,"premise":"长篇","direction":"继续","tone":"克制","chapterCount":10,"characterIds":[]}"""
        val legacyTwoChapter = """{"version":1,"premise":"旧稿","direction":"开场","tone":"温暖","chapterCount":2,"characterIds":[]}"""
        every { prefs.getString("input", null) } returns tenChapter
        assertEquals(10, store.load()!!.chapterCount)
        every { prefs.getString("input", null) } returns legacyTwoChapter
        assertEquals(2, store.load()!!.chapterCount)
    }

    @Test
    fun streamingProgressExposesEveryLargeRawDeltaWithoutUsingThePreviewAsTheBody() = kotlinx.coroutines.runBlocking {
        val deltas = listOf("{" + "x".repeat(8_000), "y".repeat(8_000) + "}")
        val progress = mutableListOf<com.mojing.app.domain.story.StoryWritingProgress>()
        coEvery {
            llmRetry.chatCompletionStreamingWithRetry(
                apiKey = any(), baseUrl = any(), model = any(), messages = any(),
                temperature = any(), maxTokens = any(), onDelta = any(), onRetry = any(), onAttempt = any(),
            )
        } coAnswers {
            val onDelta = arg<(String) -> Unit>(7)
            deltas.forEach(onDelta)
            "{" + "\"title\":\"长篇\",\"chapters\":[{\"title\":\"一\",\"content\":\"正文\"}],\"next_choices\":[\"继续\",\"暂停\"]}"
        }
        useCase.write("key", "https://api.example.com", "model",
            com.mojing.app.domain.story.StoryWritingRequest("背景", chapterCount = 1)) { progress += it }
        assertEquals(deltas, progress.mapNotNull { it.rawDelta })
        assertTrue(progress.last { it.rawDelta != null }.preview.length <= 12_000)
    }
}
