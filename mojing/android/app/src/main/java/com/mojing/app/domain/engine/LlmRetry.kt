package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.LlmProtocolException
import com.mojing.app.domain.billing.CostRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
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
        val started = System.currentTimeMillis()
        var last: Exception? = null
        repeat(maxRetries.coerceAtLeast(1)) { attempt ->
            try {
                val result = llmApi.chatCompletion(
                    apiKey, baseUrl, ChatRequest(model, messages, temperature, maxTokens, jsonOutput = jsonOutput),
                )
                if (result.finishReason == "length") throw LlmProtocolException("output_limit")
                recordSafely {
                    costRecorder.recordLlm(
                        null, null, model, "llm_json", result.promptTokens,
                        result.completionTokens, elapsed(started), true, prompt, result.content,
                    )
                }
                return result.content
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (!shouldRetry(e, attempt, maxRetries.coerceAtLeast(1))) {
                    recordFailureSafely(model, prompt, started)
                    throw e
                }
                delay(retryDelay(e, attempt))
            }
        }
        recordFailureSafely(model, prompt, started)
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
        val started = System.currentTimeMillis()
        val attempts = maxRetries.coerceAtLeast(1)
        var last: Exception? = null
        repeat(attempts) { attempt ->
            onAttempt(attempt + 1)
            val output = StringBuilder()
            try {
                llmApi.streamStoryCompletion(
                    apiKey, baseUrl,
                    ChatRequest(model, messages, temperature, maxTokens, stream = true, jsonOutput = true),
                ).collect { chunk ->
                    output.append(chunk)
                    onDelta(chunk)
                }
                recordSafely {
                    costRecorder.recordLlm(
                        null, null, model, "llm_stream", 0, 0, elapsed(started), true,
                        prompt, output.toString(),
                    )
                }
                return@withTimeout output.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (output.isNotEmpty() || !shouldRetry(e, attempt, attempts)) {
                    recordFailureSafely(model, prompt, started)
                    throw e
                }
                val wait = retryDelay(e, attempt)
                onRetry(attempt + 2, wait)
                delay(wait)
            }
        }
        recordFailureSafely(model, prompt, started)
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

    private suspend fun recordFailureSafely(model: String, prompt: String, started: Long) {
        recordSafely {
            costRecorder.recordLlm(
                null, null, model, "llm_json", 0, 0, elapsed(started), false, prompt, null,
            )
        }
    }

    private suspend fun recordSafely(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Accounting cannot discard a completed response. */ }
    }

    private fun elapsed(started: Long): Int = (System.currentTimeMillis() - started)
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        private const val STORY_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
