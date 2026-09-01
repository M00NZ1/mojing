package com.mojing.app.domain.encyclopedia

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * SillyTavern WorldInfo JSON → 本地百科条目草稿（与后端 [iter_worldinfo_entries] / [worldinfo_entry_to_row] 语义对齐）。
 */
object WorldInfoImportParser {

    data class DraftEntry(
        val title: String,
        val entryType: String,
        val summary: String,
        val content: String,
        val tags: String,
        val metaJson: String,
    )

    fun parse(raw: String): List<DraftEntry> {
        val text = raw.trim()
        if (text.isEmpty()) throw IllegalArgumentException("JSON 为空")
        val root: JsonElement = JsonParser.parseString(text)
        val arr = collectEntryObjects(root)
        if (arr.size() == 0) throw IllegalArgumentException("数组为空")
        val out = ArrayList<DraftEntry>(arr.size())
        arr.forEachIndexed { idx, el ->
            if (!el.isJsonObject) return@forEachIndexed
            val o = el.asJsonObject
            val title = listOf("comment", "name", "uid")
                .mapNotNull { k ->
                    o.get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString?.trim()
                }
                .firstOrNull { it.isNotBlank() } ?: "WorldInfo #${idx + 1}"
            val content = o.get("content")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString?.trim().orEmpty()
            val keys = asStringList(o.get("keys"))
            val secondary = asStringList(o.get("secondary_keys"))
            val meta = JsonObject().apply {
                addProperty("worldinfo_import", true)
                add("wi_keys", JsonArray().also { ka -> keys.forEach { ka.add(it) } })
                add("wi_secondary_keys", JsonArray().also { ka -> secondary.forEach { ka.add(it) } })
                o.get("priority")?.let { add("wi_priority", it) }
                o.get("depth")?.let { add("wi_depth", it) }
                o.get("selective")?.let { add("wi_selective", it) }
                o.get("constant")?.let { add("wi_constant", it) }
                o.get("position")?.let { add("wi_position", it) }
                o.get("order")?.let { add("wi_order", it) }
                o.get("uid")?.let { add("wi_uid", it) }
                val ext = o.get("extensions")
                if (ext != null && ext.isJsonObject) add("wi_extensions", ext)
            }
            val tagList = LinkedHashSet<String>().apply { add("worldinfo"); addAll(keys.take(12)) }
            val summary = if (content.length > 280) content.take(280) + "…" else content
            out.add(
                DraftEntry(
                    title = title.take(300),
                    entryType = "concept",
                    summary = summary,
                    content = content,
                    tags = tagList.joinToString(", "),
                    metaJson = meta.toString(),
                )
            )
        }
        if (out.isEmpty()) throw IllegalArgumentException("未解析出任何条目")
        return out
    }

    /** 对齐后端 `iter_worldinfo_entries`：数组、entries/lorebook/data 数组、entries 对象 map、单对象包一层。 */
    private fun collectEntryObjects(root: JsonElement): JsonArray {
        when {
            root.isJsonArray -> return root.asJsonArray
            root.isJsonObject -> {
                val o = root.asJsonObject
                for (key in listOf("entries", "lorebook", "data")) {
                    val inner = o.get(key) ?: continue
                    if (inner.isJsonArray) return inner.asJsonArray
                    if (key == "entries" && inner.isJsonObject) {
                        val arr = JsonArray()
                        inner.asJsonObject.entrySet().forEach { (_, v) ->
                            if (v.isJsonObject) arr.add(v)
                        }
                        if (arr.size() > 0) return arr
                    }
                }
                return JsonArray().also { it.add(o) }
            }
            else -> throw IllegalArgumentException("WorldInfo 应为 JSON 数组或对象")
        }
    }

    private fun asStringList(el: JsonElement?): List<String> {
        if (el == null || el.isJsonNull) return emptyList()
        if (el.isJsonArray) {
            return el.asJsonArray.mapNotNull { j ->
                if (j.isJsonPrimitive) j.asJsonPrimitive.asString.trim().takeIf { it.isNotEmpty() } else null
            }
        }
        if (el.isJsonPrimitive) {
            return el.asJsonPrimitive.asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }
}
