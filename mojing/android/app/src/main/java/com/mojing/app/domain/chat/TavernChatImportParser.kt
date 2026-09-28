package com.mojing.app.domain.chat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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

    /** Visit one message at a time; large exported arrays and JSONL files stay out of heap. */
    suspend fun forEachRow(reader: Reader, onRow: suspend (Row) -> Unit) {
        val json = JsonReader(reader).apply { isLenient = true }
        while (json.peek() != JsonToken.END_DOCUMENT) {
            currentCoroutineContext().ensureActive()
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue()
                continue
            }
            json.beginObject()
            val single = JsonObject()
            var arrayKind: String? = null
            while (json.hasNext()) {
                val name = json.nextName()
                if (arrayKind == null && (name == "mes" || name == "messages") && json.peek() == JsonToken.BEGIN_ARRAY) {
                    arrayKind = name
                    json.beginArray()
                    while (json.hasNext()) {
                        currentCoroutineContext().ensureActive()
                        val item = JsonParser.parseReader(json) as? JsonObject ?: continue
                        val row = if (name == "mes") mesRow(item) else messageRow(item)
                        if (row != null) onRow(row)
                    }
                    json.endArray()
                } else if (arrayKind == null) {
                    single.add(name, JsonParser.parseReader(json))
                } else {
                    json.skipValue()
                }
            }
            json.endObject()
            if (arrayKind == null) {
                val row = when {
                    single.has("mes") || single.has("message") -> mesRow(single)
                    single.has("content") && (single.has("role") || single.has("is_user") || single.has("speakerType")) -> messageRow(single)
                    else -> null
                }
                if (row != null) onRow(row)
            }
        }
    }

    class Fingerprint {
        private val digest = MessageDigest.getInstance("SHA-256")
        var count: Int = 0
            private set

        fun add(row: Row) {
            digest.update("${row.speaker.length}:${row.speaker}".toByteArray(Charsets.UTF_8))
            digest.update("${row.content.length}:${row.content}".toByteArray(Charsets.UTF_8))
            count++
        }

        fun batchId(): String {
            require(count > 0)
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }

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
        val fingerprint = Fingerprint()
        rows.forEach(fingerprint::add)
        return fingerprint.batchId()
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
        mesRow(message)?.let(out::add)
    }

    private fun mesRow(message: JsonObject): Row? {
        val content = (message.get("mes")?.takeIf { it.isJsonPrimitive }?.asString
            ?: message.get("message")?.takeIf { it.isJsonPrimitive }?.asString).orEmpty().trim()
        if (content.isEmpty()) return null
        val isUser = message.get("is_user")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            message.get("role")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase() == "user"
        return Row(speaker = if (isUser) "user" else "character", content = content, raw = message)
    }

    /** 解析本应用导出的 messages[] / JSONL 消息，以及常见 role/content JSONL。 */
    private fun appendMessageItem(out: MutableList<Row>, message: JsonObject) {
        messageRow(message)?.let(out::add)
    }

    private fun messageRow(message: JsonObject): Row? {
        val content = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()
        if (content.isEmpty()) return null

        val speakerType = message.get("speakerType")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()
        val role = message.get("role")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()
        val isUser = message.get("is_user")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            speakerType == "user" || role == "user" || role == "human"
        return Row(speaker = if (isUser) "user" else "character", content = content, raw = message)
    }
}
