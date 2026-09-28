package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.CostRecordEntity
import com.mojing.app.data.remote.TokenUsage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.emitAll
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmProtocolException
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

sealed class StreamState {
    data class Generating(val partialText: String) : StreamState()
    data class Done(val fullText: String, val usage: CostRecordEntity? = null) : StreamState()
    data class Error(val message: String) : StreamState()
}

@Singleton
class ChatEngine @Inject constructor(
    private val llmApi: LlmApiService,
    private val llmRetry: LlmRetry,
    private val costRecorder: CostRecorder,
    private val promptBuilder: PromptBuilder,
    private val anthropicAdapter: AnthropicAdapter,
) {

    private fun describeThrowable(e: Throwable): String {
        if (e is LlmProtocolException) return when (e.reason) {
            "output_limit" -> "模型输出达到上限，回复未完整结束。"
            "incomplete_output", "provider_stream_error" -> "模型回复未完整结束，请检查平台状态。"
            else -> "模型回复格式异常，请检查平台配置。"
        }
        val parts = mutableListOf<String>()
        parts += e::class.java.simpleName
        e.message?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        when (e) {
            is HttpException -> {
                parts += "code=${e.code()}"
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty().trim()
                if (body.isNotEmpty()) parts += "body=$body"
            }
            is IOException -> parts += "io"
        }
        e.cause?.message?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "cause=$it" }
        return parts.joinToString(" | ")
    }

    private fun streamRequest(
        sessionId: Long, character: CharacterEntity, messages: List<ChatMessage>,
        apiKey: String, baseUrl: String, model: String, temperature: Float, maxTokens: Int,
    ): Flow<StreamState> = flow {
        val billing = costRecorder.capture(model, baseUrl, apiKey)
        val text = StringBuilder()
        val started = System.nanoTime()
        var usage: TokenUsage? = null
        var recorded = false
        suspend fun record(success: Boolean, status: String): CostRecordEntity? {
            if (recorded) return null
            recorded = true
            // Accounting must not discard an otherwise completed response.
            return withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    try {
                        costRecorder.recordLlm(
                            sessionId = sessionId, characterId = character.id.takeIf { it > 0 },
                            modelName = model, provider = "llm_stream",
                            promptTokens = usage?.promptTokens ?: 0,
                            completionTokens = usage?.completionTokens ?: 0,
                            durationMs = ((System.nanoTime() - started) / 1_000_000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                            success = success, promptTextFallback = messages.joinToString("\n") { it.content },
                            completionTextFallback = text.toString(), request = billing,
                            usageProvided = usage != null, cachedPromptTokens = usage?.cachedPromptTokens ?: 0,
                            status = status,
                        )
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
            }
        }
        val result: StreamState = try {
            val source = if (OpenAiCompatibleRouting.isAnthropicHost(baseUrl)) {
                anthropicAdapter.streamChatWithUsage(
                    apiKey, baseUrl, model, messages.filter { it.role == "system" }.joinToString("\n") { it.content },
                    messages.filter { it.role != "system" }, temperature, maxTokens,
                    onUsage = { usage = it }, strictErrors = true,
                )
            } else {
                llmApi.streamChatCompletionWithUsage(apiKey, baseUrl,
                    ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = maxTokens),
                    onUsage = { usage = it })
            }
            coroutineScope {
                val contentSignal = Channel<Unit>(Channel.CONFLATED)
                val idleWatchdog = launch {
                    while (true) {
                        try { withTimeout(CHAT_CONTENT_IDLE_TIMEOUT_MS) { contentSignal.receive() } }
                        catch (_: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            throw SocketTimeoutException("Chat stream received no content")
                        }
                    }
                }
                try {
                    source.collect { chunk ->
                        if (chunk.startsWith("__ERROR__")) throw IOException(chunk.removePrefix("__ERROR__"))
                        text.append(chunk)
                        contentSignal.trySend(Unit)
                        emit(StreamState.Generating(text.toString()))
                    }
                } finally {
                    idleWatchdog.cancel()
                    contentSignal.close()
                }
            }
            StreamState.Done(text.toString(), record(true, "success"))
        } catch (cancelled: CancellationException) {
            record(false, "cancelled")
            throw cancelled
        } catch (error: Exception) {
            record(false, "failed")
            StreamState.Error(describeThrowable(error))
        }
        emit(result)
    }

    fun streamGenerate(
        sessionId: Long,
        character: CharacterEntity,
        historyMessages: List<MessageEntity>,
        apiKey: String,
        baseUrl: String,
        model: String,
        temperature: Float,
        maxTokens: Int,
        personaName: String = "玩家",
        userDescription: String = "",
    ): Flow<StreamState> = flow {
        val systemPrompt = promptBuilder.buildForCharacter(
            PromptBuilder.PromptContext(
                character = character,
                personaName = personaName,
                userDescription = userDescription,
                sessionId = sessionId,
            ),
            effectiveModelName = model,
        )

        val userMacros = MacroBindings.forCharacterSession(character, personaName, userDescription, sessionId, model)

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))

        for (msg in historyMessages) {
            val role = when (msg.speakerType) {
                "user" -> "user"
                "character" -> "assistant"
                "narrator" -> "assistant"
                else -> "user"
            }
            val cleanContent = if (msg.speakerType == "user") {
                MacroReplacer.replace(
                    AntiCheatGuard.normalizeUserMessage(msg.content, true),
                    userMacros,
                )
            } else {
                StructuredParser.stripTags(msg.content)
            }
            messages.add(ChatMessage(role, cleanContent))
        }

        emitAll(streamRequest(sessionId, character, messages, apiKey, baseUrl, model, temperature, maxTokens))
    }

    fun streamGenerateWithMemory(
        sessionId: Long,
        character: CharacterEntity,
        historyMessages: List<MessageEntity>,
        contextText: String,
        snapshot: CharacterSnapshot?,
        budget: TokenBudget,
        apiKey: String,
        baseUrl: String,
        model: String,
        personaName: String = "玩家",
        userDescription: String = "",
    ): Flow<StreamState> = flow {
        var systemPrompt = contextText
        if (snapshot != null) {
            systemPrompt += """

                【角色状态锚点 —— 你必须保持以下状态的连续性】
                当前情绪：${snapshot.mood}
                对用户态度：${snapshot.attitudeToUser}
                当前目标：${snapshot.currentGoal}
                近期行为：${snapshot.recentKeyActions.joinToString("；")}
                已知事实：${snapshot.knownFacts.joinToString("；")}
                警告：不得无故突然转变情绪或态度，变化必须有剧情触发。
            """.trimIndent()
        }

        val userMacros = MacroBindings.forCharacterSession(character, personaName, userDescription, sessionId, model)

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))

        for (msg in historyMessages) {
            val role = when (msg.speakerType) {
                "user" -> "user"
                "character" -> "assistant"
                "narrator" -> "assistant"
                else -> "user"
            }
            val cleanContent = if (msg.speakerType == "user") {
                MacroReplacer.replace(
                    AntiCheatGuard.normalizeUserMessage(msg.content, true),
                    userMacros,
                )
            } else {
                StructuredParser.stripTags(msg.content)
            }
            messages.add(ChatMessage(role, cleanContent))
        }

        emitAll(streamRequest(sessionId, character, messages, apiKey, baseUrl, model,
            character.temperature, budget.reservedForOutput))
    }

    suspend fun nonStreamingCall(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        temperature: Float = 0.8f,
        maxTokens: Int = 4000,
    ): String {
        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt),
        )
        return llmRetry.chatCompletionWithRetry(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
        )
    }

    companion object {
        private const val CHAT_CONTENT_IDLE_TIMEOUT_MS = 5 * 60 * 1_000L
    }
}
