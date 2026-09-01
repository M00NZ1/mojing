package com.mojing.app.domain.engine

import com.mojing.app.data.local.dao.CharacterProfileDao
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色卡内嵌 SillyTavern character_book（与后端 iter_worldinfo_entries + 命中逻辑对齐的简化版）。
 */
@Singleton
class CharacterBookSearcher @Inject constructor(
    private val profileDao: CharacterProfileDao,
) {
    private val gson = Gson()

    suspend fun search(characterId: Long, queryText: String, tokenBudget: Int = 900): List<String> {
        val profile = profileDao.getByCharacter(characterId) ?: return emptyList()
        val bookEl = extractCharacterBook(profile.characterCardJson) ?: return emptyList()
        val entries = iterWorldinfoEntries(bookEl)
        if (entries.isEmpty()) return emptyList()

        val scanLower = queryText.lowercase()
        val queryTokens = tokenize(queryText).toSet()
        val scored = mutableListOf<Triple<Double, String, String>>()

        entries.forEachIndexed { idx, entry ->
            val keys = normalizeKeys(entry)
            val title = (entry.get("comment")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: entry.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: keys.joinToString(",").take(120).ifEmpty { "character_book-$idx" })
            val content = entry.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: return@forEachIndexed
            if (content.isEmpty()) return@forEachIndexed

            val const = truthyConstant(entry)
            val hay = "$title ${keys.joinToString(" ")} $content"
            val entryTokens = tokenize(hay).toSet()
            val overlap = if (queryTokens.isEmpty()) 0 else queryTokens.count { it in entryTokens }
            val kwHit = scanLower.isNotEmpty() && keys.any { it.isNotEmpty() && it in scanLower }
            if (overlap == 0 && !kwHit && !const) return@forEachIndexed

            var score = overlap.toDouble() + (if (const) 2.0 else 0.0) + (if (kwHit) 0.5 else 0.0)
            scored += Triple(score, title, content)
        }

        scored.sortByDescending { it.first }
        var used = 0
        val out = mutableListOf<String>()
        for ((_, title, content) in scored) {
            val line = "[$title] ${content.take(320)}"
            val t = TokenCounter.estimate(line)
            if (used + t > tokenBudget) continue
            out += line
            used += t
            if (out.size >= 24) break
        }
        return out
    }

    private fun extractCharacterBook(json: String): JsonElement? = try {
        val obj = gson.fromJson(json, JsonObject::class.java) ?: return null
        when {
            obj.has("character_book") -> obj.get("character_book")
            else -> {
                val v2 = obj.getAsJsonObject("tavern_chara_card_v2") ?: return null
                val data = v2.getAsJsonObject("data") ?: return null
                data.get("character_book")
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun iterWorldinfoEntries(payload: JsonElement?): List<JsonObject> {
        if (payload == null || payload.isJsonNull) return emptyList()
        if (payload.isJsonArray) {
            return payload.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
        }
        if (!payload.isJsonObject) return emptyList()
        val o = payload.asJsonObject
        for (k in listOf("entries", "lorebook", "data")) {
            val inner = o.get(k)
            if (inner != null && inner.isJsonArray) {
                return inner.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            }
        }
        val ent = o.get("entries")
        if (ent != null && ent.isJsonObject) {
            return ent.asJsonObject.entrySet().mapNotNull { (_, v) ->
                if (v.isJsonObject) v.asJsonObject else null
            }
        }
        return emptyList()
    }

    private fun normalizeKeys(e: JsonObject): List<String> {
        val keysEl = e.get("keys") ?: e.get("key") ?: return emptyList()
        return when {
            keysEl.isJsonArray -> keysEl.asJsonArray.mapNotNull {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString.trim().lowercase() else null
            }.filter { it.isNotEmpty() }
            keysEl.isJsonPrimitive && keysEl.asJsonPrimitive.isString -> {
                val s = keysEl.asString.trim().lowercase()
                if (s.isEmpty()) emptyList() else listOf(s)
            }
            else -> emptyList()
        }
    }

    private fun truthyConstant(e: JsonObject): Boolean {
        if (!e.has("constant")) return false
        val c = e.get("constant") ?: return false
        return when {
            c.isJsonPrimitive && c.asJsonPrimitive.isBoolean -> c.asBoolean
            c.isJsonPrimitive && c.asJsonPrimitive.isNumber -> c.asNumber.toInt() != 0
            c.isJsonPrimitive && c.asJsonPrimitive.isString -> {
                val s = c.asString.lowercase()
                s == "true" || s == "1" || s == "yes"
            }
            else -> false
        }
    }

    private fun tokenize(text: String): List<String> =
        text.split(Regex("[\\s，。！？、；：\"'（）\\[\\]【】,.!?;:()\\[\\]{}]+"))
            .filter { it.length in 2..20 }
}
