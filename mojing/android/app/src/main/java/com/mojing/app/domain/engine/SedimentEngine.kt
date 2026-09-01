package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.remote.ChatMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SedimentEngine @Inject constructor(
    private val llmRetry: LlmRetry,
    private val saveCharacterEntry: SaveCharacterEntryUseCase,
) {
    suspend fun sedimentFromMessages(
        encyclopediaId: Long,
        sessionId: Long,
        messages: List<com.mojing.app.data.local.entity.MessageEntity>,
        apiKey: String,
        baseUrl: String,
        model: String
    ) {
        if (messages.isEmpty()) return
        val sourceMessageId = messages.lastOrNull()?.id?.takeIf { it > 0 }

        val conversationText = messages.takeLast(30).joinToString("\n") { msg ->
            "${msg.speakerType}: ${ConversationMessageText.forDerivedContext(msg).take(300)}"
        }

        val prompt = buildString {
            appendLine("从以下对话中提取新的事实性信息，用于更新百科库。")
            appendLine(conversationText)
            appendLine("返回 JSON 数组：[{\"title\":\"条目名\", \"entry_type\":\"world/character/faction/location/item/event/skill\", \"content\":\"条目内容\", \"summary\":\"一句话摘要\"}]")
            appendLine("只提取对话中新出现的事实信息，不要重复已有内容。")
        }

        val messages_llm = listOf(
            ChatMessage("system", "你是百科沉淀专家。从对话中提取事实信息。"),
            ChatMessage("user", prompt)
        )

        try {
            val result = llmRetry.chatCompletionWithRetry(
                apiKey, baseUrl, model, messages_llm,
                temperature = 0.4f,
                maxTokens = 2000,
            )
            val json = extractJson(result)
            @Suppress("UNCHECKED_CAST")
            val list = (Gson().fromJson(json, object : TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>) ?: emptyList()
            for (item in list) {
                saveCharacterEntry(
                    EncyclopediaEntryEntity(
                        encyclopediaId = encyclopediaId,
                        title = (item["title"] as? String) ?: "新条目",
                        entryType = (item["entry_type"] as? String) ?: "concept",
                        content = (item["content"] as? String) ?: "",
                        summary = (item["summary"] as? String) ?: "",
                        confidence = "inferred",
                        sourceSessionId = sessionId,
                        sourceMessageId = sourceMessageId
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "[]"
    }
}
