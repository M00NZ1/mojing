package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.LlmProtocolException
import com.mojing.app.data.remote.TokenUsage
import com.mojing.app.domain.billing.CostRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.SocketTimeoutException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class LlmRetry @Inject constructor(
    private val llmApi: LlmApiService,
    private val costRecorder: CostRecorder,
) {
    suspend fun chatCompletionWithRetry(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.8f,
        maxTokens: Int = 4000,
        maxRetries: Int = 3,
        jsonOutput: Boolean = false,
    ): String {
        val prompt = messages.joinToString("\n") { it.content }
        var last: Exception? = null
        repeat(maxRetries.coerceAtLeast(1)) { attempt ->
            val started = System.currentTimeMillis()
            val request = captureSafely(model, baseUrl, apiKey)
            var recorded = false
            try {
                val result = llmApi.chatCompletion(
                    apiKey, baseUrl, ChatRequest(model, messages, temperature, maxTokens, jsonOutput = jsonOutput),
                )
                if (result.finishReason == "length") {
                    recorded = true
                    recordSafely { costRecorder.recordLlm(
                        null, null, model, "llm_json", result.promptTokens, result.completionTokens,
                        elapsed(started), false, prompt, result.content, request,
                        usageProvided = result.usageProvided,
                        cachedPromptTokens = result.cachedPromptTokens,
                        status = "failed",
                    ) }
                    throw LlmProtocolException("output_limit")
                }
                recorded = true
                recordSafely { costRecorder.recordLlm(
                    null, null, model, "llm_json", result.promptTokens, result.completionTokens,
                    elapsed(started), true, prompt, result.content, request,
                    usageProvided = result.usageProvided,
                    cachedPromptTokens = result.cachedPromptTokens,
                    status = "success",
                ) }
                return result.content
            } catch (e: CancellationException) {
                if (!recorded) recordCancellationSafely(model, prompt, started, request, "llm_json", "", null)
                throw e
            } catch (e: Exception) {
                last = e
                if (!recorded) recordFailureSafely(model, prompt, started, request, "llm_json", "", null)
                if (!shouldRetry(e, attempt, maxRetries.coerceAtLeast(1))) {
                    throw e
                }
                delay(retryDelay(e, attempt))
            }
        }
        throw last ?: IOException("LLM request failed")
    }

    suspend fun chatCompletionStreamingWithRetry(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.8f,
        maxTokens: Int = 4000,
        maxRetries: Int = 3,
        onDelta: (String) -> Unit = {},
        onRetry: (nextAttempt: Int, delayMs: Long) -> Unit = { _, _ -> },
        onAttempt: (Int) -> Unit = {},
    ): String = try {
      withTimeout(STORY_TIMEOUT_MS) {
        val prompt = messages.joinToString("\n") { it.content }
        val attempts = maxRetries.coerceAtLeast(1)
        var last: Exception? = null
        repeat(attempts) { attempt ->
            onAttempt(attempt + 1)
            val started = System.currentTimeMillis()
            val request = captureSafely(model, baseUrl, apiKey)
            val output = StringBuilder()
            var usage: TokenUsage? = null
            var recorded = false
            try {
                llmApi.streamStoryCompletionWithUsage(
                    apiKey, baseUrl,
                    ChatRequest(model, messages, temperature, maxTokens, stream = true, jsonOutput = true),
                    onUsage = { usage = it },
                ).collect { chunk ->
                    output.append(chunk)
                    onDelta(chunk)
                }
                recorded = true
                recordSafely { costRecorder.recordLlm(
                    null, null, model, "llm_stream", usage?.promptTokens ?: 0,
                    usage?.completionTokens ?: 0, elapsed(started), true, prompt, output.toString(), request,
                    usageProvided = usage != null, cachedPromptTokens = usage?.cachedPromptTokens ?: 0,
                    status = "success",
                ) }
                return@withTimeout output.toString()
            } catch (e: CancellationException) {
                if (!recorded) recordCancellationSafely(model, prompt, started, request, "llm_stream", output.toString(), usage)
                throw e
            } catch (e: Exception) {
                last = e
                if (!recorded) recordFailureSafely(model, prompt, started, request, "llm_stream", output.toString(), usage)
                if (output.isNotEmpty() || !shouldRetry(e, attempt, attempts)) {
                    throw e
                }
                val wait = retryDelay(e, attempt)
                onRetry(attempt + 2, wait)
                delay(wait)
            }
        }
        throw last ?: IOException("LLM streaming request failed")
      }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw SocketTimeoutException("Story generation timed out")
    }

    private fun shouldRetry(error: Throwable, attempt: Int, attempts: Int): Boolean {
        if (attempt + 1 >= attempts) return false
        return when (error) {
            is LlmHttpException -> error.status == 429 || error.status in 500..599
            is IOException -> true
            else -> false
        }
    }

    private fun retryDelay(error: Throwable, attempt: Int): Long =
        (error as? LlmHttpException)?.retryAfterMs?.coerceIn(0L, 30_000L)
            ?: min(1000L * (1L shl attempt.coerceAtMost(4)), 30_000L)

    private suspend fun recordFailureSafely(
        model: String, prompt: String, started: Long, request: com.mojing.app.domain.billing.BillingRequestSnapshot?, provider: String,
        output: String, usage: TokenUsage?,
    ) {
        recordSafely {
            costRecorder.recordLlm(
                null, null, model, provider, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0,
                elapsed(started), false, prompt, output.ifBlank { null }, request,
                usageProvided = usage != null, cachedPromptTokens = usage?.cachedPromptTokens ?: 0, status = "failed",
            )
        }
    }

    private suspend fun recordCancellationSafely(
        model: String, prompt: String, started: Long,
        request: com.mojing.app.domain.billing.BillingRequestSnapshot?, provider: String, output: String, usage: TokenUsage?,
    ) {
        runCatching {
            withContext(NonCancellable) {
                withTimeout(CANCEL_RECORD_TIMEOUT_MS) {
                    costRecorder.recordLlm(
                        null, null, model, provider, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0,
                        elapsed(started), false, prompt, output.ifBlank { null }, request,
                        usageProvided = usage != null, cachedPromptTokens = usage?.cachedPromptTokens ?: 0, status = "cancelled",
                    )
                }
            }
        }
    }

    private suspend fun captureSafely(model: String, baseUrl: String, apiKey: String) =
        runCatching { costRecorder.capture(model, baseUrl, apiKey) }.getOrNull()

    private suspend fun recordSafely(block: suspend () -> Unit) {
        withContext(NonCancellable) {
            try { withTimeout(CANCEL_RECORD_TIMEOUT_MS) { block() } }
            catch (_: Exception) { /* Accounting cannot discard a completed response. */ }
        }
    }

    private fun elapsed(started: Long): Int = (System.currentTimeMillis() - started)
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        private const val STORY_TIMEOUT_MS = 5 * 60 * 1000L
        private const val CANCEL_RECORD_TIMEOUT_MS = 2_000L
    }
}
