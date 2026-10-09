package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.remote.ChatMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SedimentEngine @Inject constructor(
    private val llmRetry: LlmRetry,
    private val store: SedimentStore,
) {
    suspend fun sedimentFromMessages(
        encyclopediaId: Long,
        sessionId: Long,
        branchId: String,
        messages: List<com.mojing.app.data.local.entity.MessageEntity>,
        apiKey: String,
        baseUrl: String,
        model: String,
        contextWindow: Int? = null,
    ) {
        if (messages.isEmpty()) return
        val snapshot = try { store.read(encyclopediaId, sessionId, branchId, messages.takeLast(10)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        if (snapshot == null) return

        val conversationText = snapshot.sources.joinToString("\n") { msg ->
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
                contextWindow = contextWindow,
            )
            val json = extractJson(result)
            @Suppress("UNCHECKED_CAST")
            val list = (Gson().fromJson(json, object : TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>) ?: emptyList()
            if (list.size > 8) return
            val allowedTypes = setOf("world", "character", "faction", "location", "item", "event", "skill", "concept")
            val entries = list.map { item ->
                val title = (item["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return
                val content = (item["content"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 6000 } ?: return
                val type = (item["entry_type"] as? String) ?: "concept"
                if (type !in allowedTypes) return
                val summary = (item["summary"] as? String).orEmpty()
                if (summary.length > 1000) return
                EncyclopediaEntryEntity(encyclopediaId = encyclopediaId, title = title,
                    entryType = type, content = content, summary = summary)
            }
            store.commit(snapshot, entries)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "[]"
    }
}
