package com.mojing.app.domain.engine

import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

@Singleton
class LlmRetry @Inject constructor(
    private val llmApi: LlmApiService,
    private val costRecorder: CostRecorder,
) {
    suspend fun chatCompletionWithRetry(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<com.mojing.app.data.remote.ChatMessage>,
        temperature: Float = 0.8f,
        maxTokens: Int = 4000,
        maxRetries: Int = 3,
    ): String {
        var lastError: Exception? = null
        val promptFallback = messages.joinToString("\n") { it.content }
        val wallStart = System.currentTimeMillis()

        for (attempt in 0 until maxRetries) {
            try {
                val t0 = System.currentTimeMillis()
                val request = com.mojing.app.data.remote.ChatRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    max_tokens = maxTokens,
                )
                val res = llmApi.chatCompletion(apiKey, baseUrl, request)
                val elapsed = (System.currentTimeMillis() - t0).toInt()
                costRecorder.recordLlm(
                    sessionId = null,
                    characterId = null,
                    modelName = model,
                    provider = "llm_json",
                    promptTokens = res.promptTokens,
                    completionTokens = res.completionTokens,
                    durationMs = elapsed,
                    success = true,
                    promptTextFallback = promptFallback,
                    completionTextFallback = res.content,
                )
                return res.content
            } catch (e: Exception) {
                lastError = e
                val msg = e.message ?: ""
                if (msg.contains("429") || msg.contains("rate_limit") || msg.contains("请稍后重试")) {
                    val waitMs = min(1000L * 2.0.pow(attempt).toLong(), 30000L)
                    delay(waitMs)
                } else {
                    costRecorder.recordLlm(
                        sessionId = null,
                        characterId = null,
                        modelName = model,
                        provider = "llm_json",
                        promptTokens = 0,
                        completionTokens = 0,
                        durationMs = (System.currentTimeMillis() - wallStart).toInt(),
                        success = false,
                        promptTextFallback = promptFallback,
                        completionTextFallback = null,
                    )
                    throw e
                }
            }
        }

        costRecorder.recordLlm(
            sessionId = null,
            characterId = null,
            modelName = model,
            provider = "llm_json",
            promptTokens = 0,
            completionTokens = 0,
            durationMs = (System.currentTimeMillis() - wallStart).toInt(),
            success = false,
            promptTextFallback = promptFallback,
            completionTextFallback = null,
        )
        throw lastError ?: Exception("Retry exhausted")
    }
}
