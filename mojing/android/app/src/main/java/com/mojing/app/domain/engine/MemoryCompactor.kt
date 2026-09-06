package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.remote.ChatMessage
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class MemoryCompactor @Inject constructor(
    private val llmRetry: LlmRetry,
    private val store: MemoryCompactionStore,
) {
    /**
     * 从当前故事线最早的未覆盖位置读取一个有界批次。
     * 模型近期上下文窗口不是摘要数据源，否则长会话会永久跳过窗口之前的原文。
     */
    suspend fun compactIfNeeded(
        sessionId: Long,
        branchId: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        threshold: Int = 20
    ): Boolean {
        require(threshold in 1..2000)
        val snapshot = try { store.read(sessionId, branchId, threshold) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        val recentSegments = snapshot.previous
        val lastCoveredMessageId = snapshot.afterMessageId
        val candidates = snapshot.sources
        val recentMessages = MemoryCompactionPlanner.nextBatch(
            messages = candidates,
            lastCoveredMessageId = lastCoveredMessageId,
            threshold = threshold,
        )
        if (recentMessages.isEmpty()) return false

        val startMsg = recentMessages.firstOrNull() ?: return false
        val endMsg = recentMessages.lastOrNull() ?: return false

        val conversationText = recentMessages.joinToString("\n") { msg ->
            val content = ConversationMessageText.forDerivedContext(msg).take(200)
            when (msg.speakerType) {
                "user" -> "用户: $content"
                "character" -> "角色: $content"
                else -> content
            }
        }

        val contextSummary = recentSegments.sortedBy { it.endMessageId }.joinToString("\n") { it.summary }

        val prompt = buildString {
            appendLine("请将以下对话内容压缩为一段简短摘要（80字以内）。")
            appendLine("对话内容：")
            appendLine(conversationText)
            if (contextSummary.isNotBlank()) {
                appendLine("之前摘要：$contextSummary")
            }
            appendLine("返回JSON：{\"summary\":\"...\", \"emotional_tone\":\"中性/紧张/温馨/悲伤/战斗/浪漫\", \"key_facts\":[\"...\"]}")
        }

        val llmMessages = listOf(
            ChatMessage("system", "你是对话摘要专家，将多轮对话压缩为简洁摘要并提取关键事实。"),
            ChatMessage("user", prompt)
        )

        return try {
            val result = llmRetry.chatCompletionWithRetry(
                apiKey, baseUrl, model, llmMessages,
                temperature = 0.5f,
                maxTokens = 400,
            )
            val json = extractJson(result)
            val map: Map<*, *> = Gson().fromJson(json, Map::class.java) ?: return false
            val summary = (map["summary"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            if (summary.length > 2000) return false
            val tone = (map["emotional_tone"] as? String)?.take(40) ?: "中性"
            val facts = (map["key_facts"] as? List<*>)?.filterIsInstance<String>()?.take(20)?.map { it.take(500) } ?: emptyList()

            store.commit(snapshot, SessionMemorySegmentEntity(
                sessionId = sessionId, branchId = branchId,
                startMessageId = startMsg.id, endMessageId = endMsg.id,
                summary = summary, keyFactsJson = Gson().toJson(facts), emotionalTone = tone
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "{}"
    }
}
