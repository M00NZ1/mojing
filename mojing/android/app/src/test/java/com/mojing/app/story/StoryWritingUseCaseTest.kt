package com.mojing.app.story

import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.story.StoryWritingException
import com.mojing.app.domain.story.StoryWritingUseCase
import com.mojing.app.domain.story.StoryCanon
import com.google.gson.JsonParser
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryWritingUseCaseTest {
    private val useCase = StoryWritingUseCase(mockk<LlmRetry>())

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
}
