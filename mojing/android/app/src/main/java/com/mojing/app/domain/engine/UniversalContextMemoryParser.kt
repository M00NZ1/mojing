package com.mojing.app.domain.engine

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Normalizes lossless string/list variants of known fields; rejects malformed or incomplete structure. */
internal object UniversalContextMemoryParser {
    private val requiredFields = setOf(
        "globalSummary", "userState", "characterStates", "relationshipStates",
        "worldState", "recentCompressedTimeline", "openThreads", "continuityRules",
    )

    fun parse(raw: String, gson: Gson): UniversalContextMemory? {
        if (raw.length > 1_000_000) return null
        val root = parseElement(raw.trim()) ?: return null
        val memory = unwrap(root) ?: return null
        if (!requiredFields.all { memory.has(it) }) return null
        if (!memory.get("globalSummary").isJsonPrimitive ||
            !memory.getAsJsonPrimitive("globalSummary").isString ||
            !memory.get("userState").isJsonObject ||
            !memory.get("characterStates").isJsonArray ||
            !memory.get("relationshipStates").isJsonArray ||
            !memory.get("worldState").isJsonObject ||
            !memory.get("recentCompressedTimeline").isJsonArray ||
            !memory.get("openThreads").isJsonArray ||
            !memory.get("continuityRules").isJsonArray
        ) return null
        if (!normalizeObject(memory.getAsJsonObject("userState"),
                setOf("identity", "currentGoal"), setOf("knownFacts", "hiddenFacts", "commitments", "preferences")) ||
            !normalizeObject(memory.getAsJsonObject("worldState"),
                setOf("currentLocation", "currentTime"), setOf("activeRules", "changedFacts", "risks")) ||
            !normalizeObjects(memory.get("characterStates"), setOf("name", "identity", "currentGoal", "attitudeToUser"),
                setOf("knownFacts", "unknownFacts", "constraints")) ||
            !normalizeObjects(memory.get("relationshipStates"), setOf("subject", "objectName", "relation", "evidence", "stability")) ||
            !normalizeObjects(memory.get("recentCompressedTimeline"), setOf("event", "cause", "result", "impact")) ||
            !validStrings(memory.get("openThreads")) || !validStrings(memory.get("continuityRules"))) return null
        return runCatching { gson.fromJson(memory, UniversalContextMemory::class.java) }.getOrNull()
    }

    private fun validStrings(value: JsonElement): Boolean =
        value.isJsonArray && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }

    private fun normalizeObject(value: JsonObject, strings: Set<String>, lists: Set<String> = emptySet()): Boolean {
        for (name in strings) {
            val field = value.get(name) ?: continue
            when {
                field.isJsonPrimitive && field.asJsonPrimitive.isString -> Unit
                validStrings(field) -> value.addProperty(name, field.asJsonArray.joinToString("\n") { it.asString })
                else -> return false
            }
        }
        for (name in lists) {
            val field = value.get(name) ?: continue
            when {
                validStrings(field) -> Unit
                field.isJsonPrimitive && field.asJsonPrimitive.isString -> value.add(name,
                    com.google.gson.JsonArray().apply { if (field.asString.isNotBlank()) add(field.asString) })
                else -> return false
            }
        }
        return true
    }

    private fun normalizeObjects(value: JsonElement, strings: Set<String>, lists: Set<String> = emptySet()): Boolean =
        value.isJsonArray && value.asJsonArray.all { it.isJsonObject && normalizeObject(it.asJsonObject, strings, lists) }

    private fun unwrap(element: JsonElement): JsonObject? {
        if (!element.isJsonObject) return null
        val rootObject = element.asJsonObject
        if (requiredFields.all { rootObject.has(it) } && hasNoNulls(rootObject)) return rootObject
        // Accept only named response envelopes, never an arbitrary nested object.
        for (name in listOf("memory", "contextMemory", "data", "result")) {
            val child = rootObject.get(name) ?: continue
            val nested = when {
                child.isJsonObject -> child
                child.isJsonPrimitive && child.asJsonPrimitive.isString -> parseElement(child.asString)
                else -> null
            }
            if (nested != null && nested.isJsonObject && requiredFields.all { nested.asJsonObject.has(it) } && hasNoNulls(nested)) {
                return nested.asJsonObject
            }
        }
        return null
    }

    private fun hasNoNulls(element: JsonElement): Boolean = when {
        element.isJsonNull -> false
        element.isJsonArray -> element.asJsonArray.all(::hasNoNulls)
        element.isJsonObject -> element.asJsonObject.entrySet().all { hasNoNulls(it.value) }
        else -> true
    }

    private fun parseElement(text: String): JsonElement? {
        val candidates = sequence {
            yield(text.removePrefix("```").removeSuffix("```").trim().removePrefix("json").trim())
            var depth = 0
            var start = -1
            var quoted = false
            var escaped = false
            for (index in text.indices) {
                val char = text[index]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{' -> { if (depth++ == 0) start = index }
                    '}' -> if (depth > 0 && --depth == 0 && start >= 0) {
                        yield(text.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
        for (candidate in candidates) {
            val parsed = runCatching { JsonParser.parseString(candidate) }.getOrNull() ?: continue
            if (parsed.isJsonObject) return parsed
            if (parsed.isJsonPrimitive && parsed.asJsonPrimitive.isString) {
                val decoded = runCatching { JsonParser.parseString(parsed.asString) }.getOrNull()
                if (decoded?.isJsonObject == true) return decoded
            }
        }
        return null
    }
}
