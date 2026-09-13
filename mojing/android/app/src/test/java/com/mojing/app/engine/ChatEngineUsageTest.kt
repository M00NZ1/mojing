package com.mojing.app.engine

import com.mojing.app.domain.engine.*
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.TokenUsage
import com.mojing.app.domain.billing.BillingRequestSnapshot
import com.mojing.app.domain.billing.CostRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
        every { prompts.buildForCharacter(any(), any()) } returns "系统提示"
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
    fun billingFailureStillEmitsDone() = runTest {
        every { api.streamChatCompletionWithUsage(any(), any(), any(), any()) } returns flow { emit("正文") }
        coEvery { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db")

        val states = engine.streamGenerate(1, character, emptyList(), "key", "https://api.test", "model", 0.7f, 100).toList()

        assertEquals("正文", (states.last() as StreamState.Done).fullText)
    }
}
