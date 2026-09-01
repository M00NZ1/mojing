package com.mojing.app.domain.engine

import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncyclopediaSearcher @Inject constructor(
    private val entryDao: EncyclopediaEntryDao
) {
    data class HitEntry(val title: String, val content: String, val score: Double)

    suspend fun search(
        encyclopediaId: Long,
        userMessage: String,
        recentMessages: List<String> = emptyList(),
        tokenBudget: Int = 1600
    ): List<HitEntry> {
        val pool = LinkedHashMap<Long, EncyclopediaEntryEntity>()
        entryDao.listRecentForDigest(encyclopediaId, 320).forEach { pool[it.id] = it }
        for (kw in tokenize(userMessage).distinct().take(4)) {
            if (kw.length < 2) continue
            entryDao.search(encyclopediaId, kw).take(18).forEach { pool[it.id] = it }
        }
        val allEntries = pool.values.toList()
        if (allEntries.isEmpty()) return emptyList()

        val tokens = tokenize(userMessage).toSet()
        val recentTokens = recentMessages.flatMap { tokenize(it) }.toSet()

        val scored = allEntries.map { entry ->
            val titleTokens = tokenize(entry.title).toSet()
            val contentTokens = tokenize(entry.content).take(500).toSet()

            val titleMatch = titleTokens.count { it in tokens }.toDouble() / maxOf(titleTokens.size, 1)
            val contentMatch = contentTokens.count { it in tokens }.toDouble() / maxOf(contentTokens.size, 1)
            val recentMatch = contentTokens.count { it in recentTokens }.toDouble() / maxOf(contentTokens.size, 1)

            val meta = try {
                com.google.gson.Gson().fromJson(entry.metaJson, Map::class.java)
            } catch (_: Exception) { emptyMap<String, Any>() }

            val isFeatured = entry.isFeatured
            val priority = (meta["priority"] as? Double) ?: 1.0
            val triggerKeywords = (meta["trigger_keywords"] as? List<*>) ?: emptyList<Any>()

            var score = titleMatch * 3.0 + contentMatch * 1.5 + recentMatch * 0.5 + priority * 0.3
            if (isFeatured) score += 2.0

            val exactTriggerHit = triggerKeywords.any { kw ->
                userMessage.contains(kw.toString(), ignoreCase = true)
            }
            if (exactTriggerHit) score += 5.0

            HitEntry(title = entry.title, content = entry.content, score = score)
        }

        val sorted = scored.filter { it.score > 0.5 }.sortedByDescending { it.score }

        var currentTokens = 0
        val result = mutableListOf<HitEntry>()
        for (hit in sorted) {
            val t = estimateTokens(hit.title) + estimateTokens(hit.content.take(300))
            if (currentTokens + t > tokenBudget) break
            result.add(hit)
            currentTokens += t
        }

        return result
    }

    private fun tokenize(text: String): List<String> {
        return text.split(Regex("[\\s，。！？、；：\"'（）\\[\\]【】,.!?;:()\\[\\]{}]+"))
            .filter { it.length in 2..20 }
    }

    private fun estimateTokens(text: String): Int = TokenCounter.estimate(text)
}
