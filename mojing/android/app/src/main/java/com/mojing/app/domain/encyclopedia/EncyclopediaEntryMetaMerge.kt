package com.mojing.app.domain.encyclopedia

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** 百科条目扩展 meta 的解析与合并（队列处理器与详情页共用）。 */
object EncyclopediaEntryMetaMerge {

    fun parseMetaJson(raw: String): JsonObject =
        runCatching { JsonParser.parseString(raw.ifBlank { "{}" }).asJsonObject }.getOrElse { JsonObject() }

    fun mergeMetaPatch(target: JsonObject, patch: Map<String, Any>): Boolean {
        var changed = false
        for ((k, v) in patch) {
            if (k == "existing_meta_json") continue
            if (!shouldFillMetaKey(target, k)) continue
            val el = anyToJsonElement(v) ?: continue
            target.add(k, el)
            changed = true
        }
        return changed
    }

    fun shouldFillMetaKey(target: JsonObject, key: String): Boolean {
        if (!target.has(key)) return true
        val el = target.get(key) ?: return true
        if (el.isJsonNull) return true
        if (el.isJsonPrimitive) {
            val s = runCatching { el.asString }.getOrNull() ?: return false
            return s.isBlank()
        }
        if (el.isJsonArray) return el.asJsonArray.size() == 0
        return false
    }

    fun anyToJsonElement(v: Any?): JsonElement? = when (v) {
        null -> null
        is String -> if (v.isBlank()) null else JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> {
            val arr = JsonArray()
            v.forEach { item ->
                when (item) {
                    is String -> arr.add(item)
                    is Number -> arr.add(item)
                    is Boolean -> arr.add(item)
                    null -> arr.add("")
                    else -> arr.add(item.toString())
                }
            }
            if (arr.size() == 0) null else arr
        }
        else -> JsonPrimitive(v.toString())
    }
}
