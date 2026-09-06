package com.mojing.app.domain.engine

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.remote.ChatMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class MemoryV2Manager @Inject constructor(
    private val llmRetry: LlmRetry,
    private val messageDao: MessageDao,
) {
    suspend fun extractEventNodes(
        sessionId: Long,
        branchId: String,
        characterId: Long?,
        messages: List<MessageEntity>,
        apiKey: String,
        baseUrl: String,
        model: String
    ): List<SessionEventNodeEntity> {
        if (messages.isEmpty()) return emptyList()

        val sourceMessages = messages.takeLast(20)
        if (sourceMessages.any { it.sessionId != sessionId || it.id <= 0L }) return emptyList()
        val sourceMessageIds = sourceMessages.map { it.id }.filter { it > 0L }.toSet()
        val fallbackMessageId = sourceMessages.lastOrNull()?.id?.takeIf { it > 0L }
        val conversationText = sourceMessages.joinToString("\n") { msg ->
            "[#${msg.id}] ${msg.speakerType}: ${ConversationMessageText.forDerivedContext(msg).take(150)}"
        }

        val prompt = buildString {
            appendLine("从以下对话中提取关键事件节点，返回JSON数组：")
            appendLine(conversationText)
            appendLine("每个事件格式：{\"event_type\":\"action/discovery/relationship_change\",\"title\":\"事件标题\",\"description\":\"事件描述\",\"importance\":1-5,\"message_id\":对应原文编号}")
            appendLine("只提取有实际剧情进展的事件，不要提取日常闲聊。")
        }

        val messages_llm = listOf(
            ChatMessage("system", "你是剧情事件提取专家。"),
            ChatMessage("user", prompt)
        )

        return try {
            val result = llmRetry.chatCompletionWithRetry(
                apiKey, baseUrl, model, messages_llm,
                temperature = 0.3f,
                maxTokens = 1000,
            )
            val json = extractJson(result)
            @Suppress("UNCHECKED_CAST")
            val list = (Gson().fromJson(json, object : TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>) ?: emptyList()
            val entities = mutableListOf<SessionEventNodeEntity>()
            for (item in list.take(5)) {
                val title = (item["title"] as? String)?.trim()?.take(200).orEmpty()
                if (title.isBlank()) continue
                val parsedMessageId = when (val rawId = item["message_id"]) {
                    is Number -> rawId.toLong()
                    is String -> rawId.toLongOrNull()
                    else -> null
                }
                val sourceMessageId = parsedMessageId?.takeIf { it in sourceMessageIds }
                    ?: fallbackMessageId
                val entity = SessionEventNodeEntity(
                    sessionId = sessionId,
                    characterId = characterId,
                    branchId = branchId,
                    eventType = (item["event_type"] as? String) ?: "action",
                    title = title,
                    description = ((item["description"] as? String) ?: "").take(2000),
                    importance = ((item["importance"] as? Number)?.toInt() ?: 1).coerceIn(1, 5),
                    messageId = sourceMessageId,
                )
                entities.add(entity)
            }
            currentCoroutineContext().ensureActive()
            messageDao.commitDerivedEvents(sessionId, branchId, sourceMessages, entities)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "[]"
    }
}
