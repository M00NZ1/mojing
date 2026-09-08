package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.remote.ChatMessage
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        threshold: Int = 20,
        onProgress: (Int) -> Unit = {},
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

        return try {
            var carried = MemoryCompactionInput.previous(recentSegments.sortedBy { it.endMessageId }.map { it.summary })
            var finalSegment: SessionMemorySegmentEntity? = null
            val chunks = MemoryCompactionInput.chunks(recentMessages).iterator()
            var index = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val conversationText = withContext(Dispatchers.Default) {
                    if (chunks.hasNext()) chunks.next() else null
                } ?: break
                onProgress(++index)
                val prompt = buildString {
                    appendLine("整合已有摘要与本段对话，更新剧情摘要和关键事实。保留已确立的事实、秘密与未完成事项，按新剧情更新变化。")
                    appendLine("摘要控制在80字以内，关键事实简洁列出。消息标记中的接续表示同一条消息的后续正文。")
                    appendLine("对话内容：")
                    appendLine(conversationText)
                    if (carried.isNotBlank()) appendLine("之前摘要：$carried")
                    appendLine("返回JSON：{\"summary\":\"...\", \"emotional_tone\":\"中性/紧张/温馨/悲伤/战斗/浪漫\", \"key_facts\":[\"...\"]}")
                }
                val llmMessages = listOf(
                    ChatMessage("system", "你是对话摘要专家，将多轮对话压缩为简洁摘要并提取关键事实。"),
                    ChatMessage("user", prompt),
                )
                val result = llmRetry.chatCompletionWithRetry(
                    apiKey, baseUrl, model, llmMessages, temperature = 0.5f, maxTokens = 400,
                )
                val map: Map<*, *> = Gson().fromJson(extractJson(result), Map::class.java) ?: return false
                val summary = (map["summary"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
                if (summary.length > 2000) return false
                val tone = (map["emotional_tone"] as? String)?.take(40) ?: "中性"
                val facts = (map["key_facts"] as? List<*>)?.filterIsInstance<String>()?.take(20)?.map { it.take(500) } ?: emptyList()
                carried = Gson().toJson(mapOf("summary" to summary, "key_facts" to facts))
                if (MemoryCompactionInput.weight(carried) > MemoryCompactionInput.MEMORY_BUDGET) return false
                finalSegment = SessionMemorySegmentEntity(
                    sessionId = sessionId, branchId = branchId,
                    startMessageId = startMsg.id, endMessageId = endMsg.id,
                    summary = summary, keyFactsJson = Gson().toJson(facts), emotionalTone = tone,
                )
            }
            currentCoroutineContext().ensureActive()
            store.commit(snapshot, finalSegment ?: return false)
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
