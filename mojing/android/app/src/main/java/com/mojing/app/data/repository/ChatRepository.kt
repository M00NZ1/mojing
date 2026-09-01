package com.mojing.app.data.repository

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.ChatEngine
import com.mojing.app.domain.engine.StructuredParser
import com.mojing.app.domain.engine.StreamState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val chatEngine: ChatEngine,
    private val secureStorage: SecureStorage,
) {
    suspend fun saveUserMessage(
        sessionId: Long,
        content: String,
        branchId: String = "main"
    ): Long {
        val msgId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "user",
                content = content,
                branchId = branchId
            )
        )
        val generatedTitle = content.trim().take(30).ifBlank { "新对话" }
        sessionDao.touchWithGeneratedTitle(sessionId, generatedTitle)
        return msgId
    }

    suspend fun saveAiMessage(
        sessionId: Long,
        characterId: Long,
        content: String,
        structuredContentJson: String = "{}",
        branchId: String = "main",
        parentMessageId: Long? = null
    ): Long {
        return messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                characterId = characterId,
                content = content,
                structuredContentJson = structuredContentJson,
                branchId = branchId,
                parentMessageId = parentMessageId
            )
        )
    }

    fun resolveApiConfig(character: CharacterEntity): Triple<String, String, String> {
        val apiKey = character.apiKey.ifBlank { secureStorage.publicApiKey }
        val baseUrl = character.apiBaseUrl.ifBlank { secureStorage.publicBaseUrl }
        val model = character.modelName.ifBlank { secureStorage.publicModel }
        return Triple(apiKey, baseUrl, model)
    }

    fun streamGenerate(
        sessionId: Long,
        character: CharacterEntity,
        branchId: String = "main",
        branchSourceMessageId: Long? = null,
    ): Flow<StreamState> = flow {
        val (apiKey, baseUrl, model) = resolveApiConfig(character)

        if (apiKey.isBlank()) {
            emit(StreamState.Error("未配置 API Key，请在设置页填写"))
            return@flow
        }

        val history = if (branchId == "main") {
            messageDao.getMainContextTail(sessionId, 400).asReversed()
        } else {
            messageDao.getVisibleContextTail(sessionId, branchId, 400).asReversed()
        }
        chatEngine.streamGenerate(
            sessionId = sessionId,
            character = character,
            historyMessages = history,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            temperature = character.temperature,
            maxTokens = character.maxTokens,
            personaName = secureStorage.userName,
            userDescription = secureStorage.userDescription,
        ).collect { state ->
            emit(state)

            if (state is StreamState.Done) {
                val structured = StructuredParser.parse(state.fullText)
                val jsonContent = try {
                    com.google.gson.Gson().toJson(
                        mapOf(
                            "narrations" to structured.narrations,
                            "thoughts" to structured.thoughts,
                            "speeches" to structured.speeches.map { mapOf("name" to it.characterName, "text" to it.text) },
                            "choices" to structured.choices
                        )
                    )
                } catch (_: Exception) { "{}" }

                saveAiMessage(
                    sessionId = sessionId,
                    characterId = character.id,
                    content = state.fullText,
                    structuredContentJson = jsonContent,
                    branchId = branchId,
                    parentMessageId = branchSourceMessageId,
                )
            }
        }
    }
}
