package com.mojing.app.domain.engine

import com.mojing.app.data.local.dao.WorldLoreEntryDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.google.gson.Gson
import com.google.gson.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoreSearcher @Inject constructor(
    private val templateDao: WorldTemplateDao,
    private val loreEntryDao: WorldLoreEntryDao,
) {
    data class LoreHit(val title: String, val content: String, val score: Double)

    private val gson = Gson()

    /**
     * 按会话世界上的 [com.mojing.app.data.local.entity.SessionWorldEntity.templateId]（字符串 slug）
     * 解析本地 [WorldTemplateEntity]，再从 [world_lore_entries] 召回命中条目。
     */
    suspend fun search(
        sessionWorldTemplateId: String?,
        userMessage: String,
        tokenBudget: Int = 800,
    ): List<LoreHit> {
        val slug = sessionWorldTemplateId?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("custom", ignoreCase = true) }
            ?: return emptyList()
        val template = templateDao.getByTemplateId(slug) ?: return emptyList()
        val entries = loreEntryDao.getByTemplate(template.id)
        if (entries.isEmpty()) return emptyList()

        val tokens = tokenize(userMessage).toSet()
        val scanLower = userMessage.lowercase()

        val scored = entries.map { entry ->
            val keys = parseKeywords(entry.keywordsJson)
            val titleTokens = tokenize(entry.title).toSet()
            val contentSlice = entry.content.take(500)
            val contentTokens = tokenize(contentSlice).toSet()

            val titleMatch = titleTokens.count { it in tokens }.toDouble() / maxOf(titleTokens.size, 1)
            val contentMatch = contentTokens.count { it in tokens }.toDouble() / maxOf(contentTokens.size, 1)
            val kwHit = keys.any { it.isNotEmpty() && it in scanLower }
            val keyTokenMatch = if (keys.isEmpty()) 0.0 else {
                keys.count { k -> tokenize(k).any { it in tokens } }.toDouble() / keys.size
            }

            var score = titleMatch * 3.0 + contentMatch * 1.5 + keyTokenMatch * 2.0
            if (kwHit) score += 1.0
            if (entry.isCore) score += 0.5

            LoreHit(entry.title, entry.content, score)
        }

        val sorted = scored.filter { it.score > 0.05 }.sortedByDescending { it.score }

        var used = 0
        val out = mutableListOf<LoreHit>()
        for (hit in sorted) {
            val line = "[${hit.title}] ${hit.content.take(200)}"
            val t = TokenCounter.estimate(line)
            if (used + t > tokenBudget) break
            out.add(hit)
            used += t
            if (out.size >= 12) break
        }
        return out
    }

    private fun parseKeywords(keywordsJson: String): List<String> {
        return try {
            val el: JsonElement = gson.fromJson(keywordsJson, JsonElement::class.java) ?: return emptyList()
            when {
                el.isJsonNull -> emptyList()
                el.isJsonArray -> el.asJsonArray.mapNotNull { j ->
                    if (j.isJsonPrimitive && j.asJsonPrimitive.isString) j.asString.trim().lowercase() else null
                }.filter { it.isNotEmpty() }
                el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                    listOf(el.asString.trim().lowercase()).filter { it.isNotEmpty() }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tokenize(text: String): List<String> =
        text.split(Regex("[\\s，。！？、；：\"'（）\\[\\]【】,.!?;:()\\[\\]{}]+"))
            .filter { it.length in 2..20 }
}
