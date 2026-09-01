package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class StreamState {
    data class Generating(val partialText: String) : StreamState()
    data class Done(val fullText: String) : StreamState()
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

    private suspend fun recordStreamCost(
        sessionId: Long,
        character: CharacterEntity,
        model: String,
        messages: List<ChatMessage>,
        fullText: String,
        durationMs: Int,
        success: Boolean,
    ) {
        val cid = character.id.takeIf { it > 0L }
        val promptJoined = messages.joinToString("\n") { it.content }
        costRecorder.recordLlm(
            sessionId = sessionId,
            characterId = cid,
            modelName = model,
            provider = "llm_stream",
            promptTokens = 0,
            completionTokens = 0,
            durationMs = durationMs,
            success = success,
            promptTextFallback = promptJoined,
            completionTextFallback = fullText.ifBlank { null },
        )
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

        val fullText = StringBuilder()
        var hasError = false
        var errorMessage = ""
        val t0 = System.currentTimeMillis()
        val isAnthropic = OpenAiCompatibleRouting.isAnthropicHost(baseUrl)
        try {
            if (isAnthropic) {
                val anthropicMessages = messages.filter { it.role != "system" }
                anthropicAdapter.streamChat(
                    apiKey, baseUrl, model, systemPrompt, anthropicMessages, temperature, maxTokens
                ).collect { chunk: String ->
                    if (chunk.startsWith("__ERROR__")) {
                        errorMessage = chunk.removePrefix("__ERROR__")
                        hasError = true
                        return@collect
                    }
                    fullText.append(chunk)
                    emit(StreamState.Generating(fullText.toString()))
                }
            } else {
                val request = ChatRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    max_tokens = maxTokens,
                )
                llmApi.streamChatCompletion(apiKey, baseUrl, request).collect { chunk ->
                    fullText.append(chunk)
                    emit(StreamState.Generating(fullText.toString()))
                }
            }
            val elapsed = (System.currentTimeMillis() - t0).toInt()
            if (hasError) {
                recordStreamCost(sessionId, character, model, messages, "", elapsed, false)
                emit(StreamState.Error(errorMessage))
            } else {
                recordStreamCost(sessionId, character, model, messages, fullText.toString(), elapsed, true)
                emit(StreamState.Done(fullText.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - t0).toInt()
            recordStreamCost(sessionId, character, model, messages, fullText.toString(), elapsed, false)
            emit(StreamState.Error(describeThrowable(e)))
        }
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

        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = character.temperature,
            max_tokens = budget.reservedForOutput,
        )

        val fullText = StringBuilder()
        var hasError = false
        var errorMessage = ""
        val t0 = System.currentTimeMillis()
        val isAnthropic = OpenAiCompatibleRouting.isAnthropicHost(baseUrl)
        try {
            if (isAnthropic) {
                val anthropicMessages = messages.filter { it.role != "system" }
                anthropicAdapter.streamChat(
                    apiKey, baseUrl, model, systemPrompt, anthropicMessages, character.temperature, budget.reservedForOutput
                ).collect { chunk ->
                    if (chunk.startsWith("__ERROR__")) {
                        errorMessage = chunk.removePrefix("__ERROR__")
                        hasError = true
                        return@collect
                    }
                    fullText.append(chunk)
                    emit(StreamState.Generating(fullText.toString()))
                }
            } else {
                llmApi.streamChatCompletion(apiKey, baseUrl, request).collect { chunk ->
                    fullText.append(chunk)
                    emit(StreamState.Generating(fullText.toString()))
                }
            }
            val elapsed = (System.currentTimeMillis() - t0).toInt()
            if (hasError) {
                recordStreamCost(sessionId, character, model, messages, "", elapsed, false)
                emit(StreamState.Error(errorMessage))
            } else {
                recordStreamCost(sessionId, character, model, messages, fullText.toString(), elapsed, true)
                emit(StreamState.Done(fullText.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - t0).toInt()
            recordStreamCost(sessionId, character, model, messages, fullText.toString(), elapsed, false)
            emit(StreamState.Error(describeThrowable(e)))
        }
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
}
