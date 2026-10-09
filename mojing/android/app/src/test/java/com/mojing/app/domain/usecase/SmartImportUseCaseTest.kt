package com.mojing.app.domain.usecase

import com.mojing.app.data.SecureStorage
import com.mojing.app.domain.engine.LlmRetry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartImportUseCaseTest {
    private val retry = mockk<LlmRetry>()
    private val storage = mockk<SecureStorage>(relaxed = true)

    @Test
    fun standardJsonIsPassedThroughWithoutCallingLlm() = runTest {
        val input = "  {\"version\":1,\"type\":\"characters\",\"data\":[]}  "

        assertEquals(input.trim(), SmartImportUseCase(retry, storage).parseToStructuredJson(input, "character"))
        coVerify(exactly = 0) { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun emptyModelResponseFailsWithoutReturningInput() = runTest {
        coEvery { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) } returns ""

        val failure = runCatching {
            SmartImportUseCase(retry, storage).parseToStructuredJson("原始设定", "character")
        }.exceptionOrNull()

        assertTrue(failure is SmartImportException)
        assertEquals("模型未返回有效 JSON，请重试", failure?.message)
        assertFalse(failure?.message?.contains("原始设定") == true)
    }

    @Test
    fun malformedModelResponseIsSafeAndDoesNotLeakResponseText() = runTest {
        coEvery { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) } returns
            "说明 {not-json} provider-secret"

        val failure = runCatching {
            SmartImportUseCase(retry, storage).parseToStructuredJson("输入", "template")
        }.exceptionOrNull()

        assertEquals("模型未返回有效 JSON，请重试", failure?.message)
        assertFalse(failure?.message?.contains("provider-secret") == true)
    }

    @Test
    fun authenticationAndTimeoutFailuresUseRecognizableSafeMessages() = runTest {
        val useCase = SmartImportUseCase(retry, storage)
        coEvery { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) } throws
            com.mojing.app.data.remote.LlmHttpException(401)
        val auth = runCatching { useCase.parseToStructuredJson("输入", "encyclopedia") }.exceptionOrNull()
        assertEquals("模型服务认证失败，请检查 API Key", auth?.message)

        coEvery { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) } throws
            java.net.SocketTimeoutException("secret timeout details")
        val timeout = runCatching { useCase.parseToStructuredJson("输入", "encyclopedia") }.exceptionOrNull()
        assertEquals("模型服务请求超时，请检查网络后重试", timeout?.message)
    }

    @Test
    fun cancellationPropagatesAndSameInputCanBeRetried() = runTest {
        val useCase = SmartImportUseCase(retry, storage)
        coEvery { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) } throws
            CancellationException("cancelled")
        val cancelled = runCatching { useCase.parseToStructuredJson("相同输入", "character") }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)

        coEvery { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) } returns
            "前缀 {\"version\":1,\"type\":\"characters\",\"data\":[] } 后缀"
        assertEquals(
            "{\"version\":1,\"type\":\"characters\",\"data\":[] }",
            useCase.parseToStructuredJson("相同输入", "character"),
        )
        coVerify(exactly = 2) { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any()) }
    }
}
