package com.mojing.app.engine

import com.mojing.app.data.local.entity.*
import com.mojing.app.data.remote.*
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StructuredPromptBudgetTest {
    private val prompts = PromptBuilder()
    private val character = CharacterEntity(name = "守灯人", personaPrompt = "核心身份 {{char}}", maxTokens = 100)
    private val context = PromptBuilder.PromptContext(
        character = character, world = SessionWorldEntity(sessionId = 1, worldPrompt = "世界规则"),
        personaName = "旅人", userDescription = "用户身份", encyclopediaFoundation = "基础背景不得删除",
        memoryCorrections = listOf(SessionMemoryCorrectionEntity(sessionId = 1, content = "锁定纠正")),
        encyclopediaHits = listOf("百科检索"), loreHits = listOf("设定检索"), characterBookHits = listOf("常量角色书"),
        universalContextMemoryText = "秘密与用户设定的混合记忆",
        memorySummary = "自动摘要旧剧情".repeat(200), automaticSummary = true,
    )
    private val history = listOf(
        MessageEntity(sessionId = 1, speakerType = "user", content = "当前输入 {{user}}"),
        MessageEntity(sessionId = 1, speakerType = "character", content = "<SPEECH>另一角色的完整发言</SPEECH>"),
    )
    private fun engine(api: LlmApiService, costs: CostRecorder = mockk(relaxed = true)) =
        ChatEngine(api, mockk(relaxed = true), costs, prompts, mockk(relaxed = true))

    @Test fun derivedSummaryIsRemovedBeforeOriginalHistoryAndProtectedSourcesRemain() {
        val document = prompts.buildCharacterDocument(context, "model")
        val protected = document.copy(blocks = document.blocks.filter { it.kind == PromptBlock.Kind.PROTECTED })
        val old = ChatMessage("assistant", "旧原文仍有空间")
        val current = listOf(ChatMessage("user", "当前输入旅人"), ChatMessage("assistant", "完整角色链"))
        val messages = listOf(ChatMessage("system", document.render()), old) + current
        val expected = listOf(ChatMessage("system", protected.render()), old) + current
        val capacity = RequestContextBudget.estimateInput(expected).toInt() + 100
        assertTrue(RequestContextBudget.fit(messages, capacity, 100) is RequestContextBudget.Result.TooLarge)
        val result = RequestContextBudget.fit(messages, capacity, 100, document) as RequestContextBudget.Result.Ready
        assertEquals(expected, result.messages)
        assertEquals(1, result.removedPromptBlocks)
        assertEquals(0, result.removedMessages)
        listOf("核心身份", "世界规则", "锁定纠正", "基础背景", "百科检索", "设定检索", "常量角色书", "秘密", "用户身份")
            .forEach { assertTrue(it, result.messages.first().content.contains(it)) }
        assertFalse(result.messages.first().content.contains("近期记忆摘要："))
        assertEquals(context.memorySummary, "自动摘要旧剧情".repeat(200))
    }

    @Test fun unknownAmpleAndMismatchedDocumentsPreserveFullText() {
        val document = prompts.buildCharacterDocument(context)
        val messages = listOf(ChatMessage("system", document.render()), ChatMessage("user", "当前正文"))
        for (capacity in listOf(null, Int.MAX_VALUE)) {
            val result = RequestContextBudget.fit(messages, capacity, 100, document) as RequestContextBudget.Result.Ready
            assertEquals(messages, result.messages)
            assertEquals(0, result.removedPromptBlocks)
        }
        val mismatched = messages.map { it.copy(content = it.content + "额外保护") }
        assertTrue(RequestContextBudget.fit(mismatched, 1000, 100, document) is RequestContextBudget.Result.TooLarge)
        val unclassified = prompts.buildCharacterDocument(context.copy(automaticSummary = false))
        assertTrue(unclassified.blocks.all { it.kind == PromptBlock.Kind.PROTECTED })
    }

    @Test fun onlyExactProductionProvenanceQualifiesAndMixedOrEditedSummariesAreProtected() = runTest {
        val config = mockk<com.mojing.app.data.local.dao.ConfigDao>()
        val builder = ContextBuilder(prompts, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), config)
        val segment = SessionMemorySegmentEntity(id = 7, sessionId = 1, startMessageId = 3, endMessageId = 5, summary = "自动正文")
        coEvery { config.get(any()) } returns null
        assertFalse(builder.areAutomaticSummaries(listOf(segment)))
        coEvery { config.get(SummaryProvenance.key(segment)) } returns ConfigEntity(SummaryProvenance.key(segment), SummaryProvenance.fingerprint(segment))
        assertTrue(builder.areAutomaticSummaries(listOf(segment)))
        assertFalse(builder.areAutomaticSummaries(listOf(segment.copy(summary = "人工校正正文"))))
        assertFalse(builder.areAutomaticSummaries(listOf(segment.copy(branchId = "other"))))
        assertFalse(builder.areAutomaticSummaries(listOf(segment, segment.copy(id = 8))))
        assertFalse(builder.areAutomaticSummaries(emptyList()))
    }

    @Test fun narratorUnknownAndAmpleCapacityAreIdenticalToExistingStringWrapping() = runTest {
        val document = prompts.buildNarratorDocument(context, "方向 {{user}}", "model", false)
            .appendProtected("\n指定章节标题完整保留", separator = "")
        val narrator = character.copy(personaPrompt = document.render())
        val api = mockk<LlmApiService>()
        val requests = mutableListOf<ChatRequest>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(requests), any()) } returns flowOf("完成")
        val engine = engine(api)
        engine.streamGenerate(1, narrator, history, "synthetic", "https://example.test", "model", 0.8f, 100, "旅人", "用户身份").toList()
        for (capacity in listOf(null, Int.MAX_VALUE)) {
            engine.streamGenerate(1, narrator, history, "synthetic", "https://example.test", "model", 0.8f, 100, "旅人", "用户身份",
                contextWindow = capacity, promptDocument = document).toList()
            assertEquals(requests.first(), requests.last())
        }
    }

    @Test fun actualCharacterRequestTrimsSummaryButKeepsSnapshotAndFinalCurrentChain() = runTest {
        val builder = ContextBuilder(prompts, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val built = builder.buildFullContext(character, context.world, "旅人", "用户身份", "继续",
            memorySummary = context.memorySummary, memoryCorrections = context.memoryCorrections,
            universalContextMemoryText = context.universalContextMemoryText, activeCharacterNames = listOf(character.name),
            sessionId = 1, effectiveModelName = "model", automaticSummary = true)
        val api = mockk<LlmApiService>()
        val request = slot<ChatRequest>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flowOf("完成")
        val snapshot = CharacterSnapshot(mood = "状态锚点唯一标记")
        val states = engine(api).streamGenerateWithMemory(1, character, history, built.systemPrompt, snapshot,
            TokenBudget(0, 0, 0, 0, 100, 1400), "synthetic", "https://example.test", "model", "旅人", "用户身份",
            promptDocument = built.promptDocument).toList()
        assertTrue(states.last() is StreamState.Done)
        val system = request.captured.messages.first().content
        assertFalse(system.contains("自动摘要旧剧情"))
        assertTrue(system.contains("状态锚点唯一标记"))
        assertTrue(system.contains("锁定纠正"))
        assertTrue(system.contains("秘密与用户设定"))
        assertEquals(listOf("当前输入 旅人", "另一角色的完整发言"), request.captured.messages.drop(1).map { it.content })
        assertEquals(100, request.captured.max_tokens)
    }

    @Test fun narratorAndChapterWrappingRetainMacrosGuidanceAndChapterInstructions() = runTest {
        for (chapter in listOf(false, true)) {
            val world = context.world!!.copy(gameplayMode = if (chapter) "小说创作" else "角色扮演", choiceGenerationEnabled = false)
            var document = prompts.buildNarratorDocument(context.copy(world = world), "方向必须保留 {{user}}", "model", false, !chapter)
            if (chapter) document = document.appendProtected("\n第7章：指定标题；承接完整草稿", separator = "")
            val narrator = character.copy(personaPrompt = document.render())
            val api = mockk<LlmApiService>()
            val request = slot<ChatRequest>()
            every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flowOf("完成")
            val states = engine(api).streamGenerate(1, narrator, history, "synthetic", "https://example.test", "model",
                0.8f, 100, "旅人", "用户身份", contextWindow = 3000, promptDocument = document).toList()
            assertTrue(states.last() is StreamState.Done)
            val system = request.captured.messages.first().content
            assertTrue(system.contains("方向必须保留 旅人"))
            assertTrue(system.contains("基础背景不得删除"))
            assertTrue(system.contains("秘密与用户设定"))
            assertFalse(system.contains("自动摘要旧剧情"))
            assertEquals(1, Regex("用户身份").findAll(system).count())
            if (chapter) assertTrue(system.contains("第7章：指定标题；承接完整草稿"))
        }
    }

    @Test fun protectedMacroGrowthStillRejectsWithoutNetworkOrBilling() = runTest {
        val document = prompts.buildCharacterDocument(context)
        val api = mockk<LlmApiService>()
        val costs = mockk<CostRecorder>(relaxed = true)
        val states = engine(api, costs).streamGenerateWithMemory(1, character,
            listOf(MessageEntity(sessionId = 1, speakerType = "user", content = "{{user_description}}")), document.render(),
            CharacterSnapshot(mood = "完整锚点"), TokenBudget(0, 0, 0, 0, 100, 1400),
            "synthetic", "https://example.test", "model", userDescription = "完整输入不可截断".repeat(500),
            promptDocument = document).toList()
        assertTrue((states.single() as StreamState.Error).contextLimit)
        verify { api wasNot Called }
        coVerify(exactly = 0) { costs.capture(any(), any(), any()) }
        coVerify(exactly = 0) { costs.captureForPlatform(any(), any(), any(), any()) }
    }
}
