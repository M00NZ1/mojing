package com.mojing.app.domain.chat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * 酒馆式聊天记录解析（与后端 [parse_tavern_chat_file] 一致）：根级 `mes[]`、本应用
 * 导出的 `messages[]` 或 JSONL。
 */
object TavernChatImportParser {

    data class Row(
        val speaker: String,
        val content: String,
        val raw: JsonObject,
    )

    fun parse(text: String): List<Row> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val out = ArrayList<Row>()
        val root: JsonElement? = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
        if (root is JsonObject) {
            when {
                root.get("mes") is JsonArray -> {
                    appendArray(out, root.getAsJsonArray("mes"), ::appendMesItem)
                    return out
                }
                root.get("messages") is JsonArray -> {
                    appendArray(out, root.getAsJsonArray("messages"), ::appendMessageItem)
                    return out
                }
            }
        }

        for (line in trimmed.lineSequence()) {
            val lineTrim = line.trim()
            if (lineTrim.isEmpty() || lineTrim.startsWith("#")) continue
            val obj = runCatching { JsonParser.parseString(lineTrim) }.getOrNull() as? JsonObject ?: continue
            when {
                obj.has("mes") || obj.has("message") -> appendMesItem(out, obj)
                obj.has("content") && (obj.has("role") || obj.has("is_user") || obj.has("speakerType")) -> {
                    appendMessageItem(out, obj)
                }
            }
        }
        return out
    }

    /** 相同角色顺序与正文得到相同批次 ID，不受 JSON 缩进或无关导出字段影响。 */
    fun stableBatchId(rows: List<Row>): String {
        require(rows.isNotEmpty())
        val canonical = buildString {
            rows.forEach { row ->
                append(row.speaker.length).append(':').append(row.speaker)
                append(row.content.length).append(':').append(row.content)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun appendArray(
        out: MutableList<Row>,
        array: JsonArray,
        append: (MutableList<Row>, JsonObject) -> Unit,
    ) {
        for (element in array) {
            if (element is JsonObject) append(out, element)
        }
    }

    private fun appendMesItem(out: MutableList<Row>, message: JsonObject) {
        val content = (message.get("mes")?.takeIf { it.isJsonPrimitive }?.asString
            ?: message.get("message")?.takeIf { it.isJsonPrimitive }?.asString).orEmpty().trim()
        if (content.isEmpty()) return
        val isUser = message.get("is_user")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            message.get("role")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase() == "user"
        out.add(Row(speaker = if (isUser) "user" else "character", content = content, raw = message))
    }

    /** 解析本应用导出的 messages[] / JSONL 消息，以及常见 role/content JSONL。 */
    private fun appendMessageItem(out: MutableList<Row>, message: JsonObject) {
        val content = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()
        if (content.isEmpty()) return

        val speakerType = message.get("speakerType")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()
        val role = message.get("role")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()
        val isUser = message.get("is_user")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            speakerType == "user" || role == "user" || role == "human"
        out.add(Row(speaker = if (isUser) "user" else "character", content = content, raw = message))
    }
}
