package com.mojing.app.engine

import com.mojing.app.domain.engine.*
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmProtocolException
import com.mojing.app.data.remote.TokenUsage
import com.mojing.app.domain.billing.BillingRequestSnapshot
import com.mojing.app.domain.billing.CostRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEngineUsageTest {
    private val api = mockk<LlmApiService>()
    private val retry = mockk<LlmRetry>(relaxed = true)
    private val costs = mockk<CostRecorder>(relaxed = true)
    private val prompts = mockk<PromptBuilder>()
    private val anthropic = mockk<AnthropicAdapter>(relaxed = true)
    private val engine = ChatEngine(api, retry, costs, prompts, anthropic)
    private val character = CharacterEntity(id = 9, name = "角色")
    private val snapshot = BillingRequestSnapshot("platform-1", "测试平台", null)

    init {
        every { prompts.buildCharacterDocument(any(), any(), any()) } returns PromptDocument.protected("系统提示")
        coEvery { costs.capture(any(), any(), any()) } returns snapshot
    }

    @Test
    fun streamDoneCarriesProviderUsageIntoCostRecord() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } answers {
            val callback = arg<(TokenUsage?) -> Unit>(3)
            flow {
                callback(TokenUsage(12, 7, 3))
                emit("完成")
            }
        }

        val states = engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()

        assertTrue(states.last() is StreamState.Done)
        coVerify(exactly = 1) {
            costs.recordLlm(1L, 9L, "model", "llm_stream", 12, 7, any(), true, any(), "完成", snapshot, true, 3, "success")
        }
        coVerify(exactly = 1) { costs.capture("model", "https://api.test", "key") }
    }

    @Test
    fun selectedPlatformIdIsFrozenAtStreamStart() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow { emit("完成") }
        coEvery { costs.captureForPlatform("model", "https://api.test", "key", "platform-1") } returns snapshot

        val states = engine.streamGenerate(
            1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100,
            platformId = "platform-1",
        ).toList()

        assertTrue(states.last() is StreamState.Done)
        coVerify(exactly = 1) { costs.captureForPlatform("model", "https://api.test", "key", "platform-1") }
        coVerify(exactly = 0) { costs.capture("model", "https://api.test", "key") }
    }

    @Test
    fun cancellationRecordsCancelledAndRethrows() = runTest {
        val cancelled = CancellationException("stop")
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow { throw cancelled }

        val error = runCatching {
            engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        coVerify(exactly = 1) {
            costs.recordLlm(1L, 9L, "model", "llm_stream", 0, 0, any(), false, any(), any(), snapshot, false, 0, "cancelled")
        }
    }

    @Test
    fun partialFailureIsRecordedAndDoesNotBecomeSuccess() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            emit("部分")
            throw java.io.IOException("断流")
        }

        val states = engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()

        assertTrue(states.last() is StreamState.Error)
        coVerify(exactly = 1) {
            costs.recordLlm(1L, 9L, "model", "llm_stream", 0, 0, any(), false, any(), "部分", snapshot, false, 0, "failed")
        }
    }

    @Test
    fun outputLimitShowsRecoverableErrorWithReceivedText() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            emit("已收到的回复")
            throw LlmProtocolException("output_limit")
        }

        val states = engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()

        assertEquals("已收到的回复", (states[0] as StreamState.Generating).partialText)
        assertEquals("模型输出达到上限，回复未完整结束。", (states.last() as StreamState.Error).message)
        coVerify(exactly = 1) {
            costs.recordLlm(1L, 9L, "model", "llm_stream", 0, 0, any(), false, any(), "已收到的回复", snapshot, false, 0, "failed")
        }
    }

    @Test
    fun activeChatStreamCanContinueBeyondFiveMinutes() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            repeat(7) { index ->
                emit("${index + 1}")
                delay(60_000L)
            }
        }

        val states = engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()

        assertEquals("1234567", (states.last() as StreamState.Done).fullText)
    }

    @Test
    fun chatContentIdleTimeoutRetainsPartialReply() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            emit("已收到正文")
            delay(5 * 60 * 1_000L + 1)
            emit("不应到达")
        }

        val states = engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()

        assertEquals("已收到正文", (states.first() as StreamState.Generating).partialText)
        assertTrue((states.last() as StreamState.Error).message.contains("timeout", ignoreCase = true))
        coVerify(exactly = 1) {
            costs.recordLlm(1L, 9L, "model", "llm_stream", 0, 0, any(), false, any(), "已收到正文", snapshot, false, 0, "failed")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun userStopStillCancelsChatInsteadOfReportingIdleFailure() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            emit("停止前正文")
            delay(Long.MAX_VALUE)
        }
        val states = mutableListOf<StreamState>()
        val job = launch {
            engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100)
                .collect { states += it }
        }
        runCurrent()
        job.cancelAndJoin()

        assertEquals("停止前正文", (states.single() as StreamState.Generating).partialText)
        coVerify(exactly = 1) {
            costs.recordLlm(1L, 9L, "model", "llm_stream", 0, 0, any(), false, any(), "停止前正文", snapshot, false, 0, "cancelled")
        }
    }

    @Test
    fun billingFailureStillEmitsDone() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow { emit("正文") }
        coEvery { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db")

        val states = engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()

        assertEquals("正文", (states.last() as StreamState.Done).fullText)
    }

    @Test
    fun streamGenerateSendsCharacterSamplingAndKeepsOriginalSnapshot() = runTest {
        var currentCharacter = character.copy(
            temperature = 0.0f,
            maxTokens = 777,
            topP = 0.000001f,
            frequencyPenalty = -2.5f,
            presencePenalty = -0.0f,
        )
        val request = slot<com.mojing.app.data.remote.ChatRequest>()
        val release = CompletableDeferred<Unit>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flow {
            release.await()
            emit("完成")
        }

        val job = launch {
            engine.streamGenerate(
                1, currentCharacter, emptyList(), "key", "https://api.test", "model", 0.45f, 321,
            ).toList()
        }
        runCurrent()
        currentCharacter = currentCharacter.copy(
            temperature = 1.8f,
            maxTokens = 9999,
            topP = 0.9f,
            frequencyPenalty = 4.0f,
            presencePenalty = 3.0f,
        )
        release.complete(Unit)
        job.join()

        assertEquals(0.45f, request.captured.temperature)
        assertEquals(321, request.captured.max_tokens)
        assertEquals(0.000001f, request.captured.top_p)
        assertEquals(-2.5f, request.captured.frequency_penalty)
        assertEquals(-0.0f, request.captured.presence_penalty)
    }

    @Test
    fun streamGenerateWithMemorySendsZeroAndNegativeCharacterSampling() = runTest {
        val request = slot<com.mojing.app.data.remote.ChatRequest>()
        val release = CompletableDeferred<Unit>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flow {
            release.await()
            emit("完成")
        }
        var memoryCharacter = character.copy(
            temperature = 0.2f,
            topP = 0.0f,
            frequencyPenalty = -1.25f,
            presencePenalty = 0.0f,
        )

        val job = launch {
            engine.streamGenerateWithMemory(
                1,
                memoryCharacter,
                emptyList(),
                "记忆上下文",
                null,
                TokenBudget(0, 0, 0, 0, 654, 2000),
                "key",
                "https://api.test",
                "model",
            ).toList()
        }
        runCurrent()
        memoryCharacter = memoryCharacter.copy(topP = 0.9f, frequencyPenalty = 4.0f, presencePenalty = 3.0f)
        release.complete(Unit)
        job.join()

        assertEquals(0.2f, request.captured.temperature)
        assertEquals(654, request.captured.max_tokens)
        assertEquals(0.0f, request.captured.top_p)
        assertEquals(-1.25f, request.captured.frequency_penalty)
        assertEquals(0.0f, request.captured.presence_penalty)
    }

    @Test
    fun ephemeralNarratorCharacterDoesNotEmitRoleSamplingFields() = runTest {
        val request = slot<com.mojing.app.data.remote.ChatRequest>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flow { emit("旁白") }
        val narrator = CharacterEntity(
            id = 0L,
            name = "临时旁白",
            temperature = 0.8f,
            maxTokens = 900,
            topP = 0.2f,
            frequencyPenalty = -1.0f,
            presencePenalty = 0.5f,
        )

        val states = engine.streamGenerate(
            1L,
            narrator,
            emptyList(),
            "key",
            "https://api.test",
            "model",
            0.65f,
            444,
        ).toList()

        assertTrue(states.last() is StreamState.Done)
        assertEquals(0.65f, request.captured.temperature)
        assertEquals(444, request.captured.max_tokens)
        assertEquals(null, request.captured.top_p)
        assertEquals(null, request.captured.frequency_penalty)
        assertEquals(null, request.captured.presence_penalty)
    }
}
