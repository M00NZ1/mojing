package com.mojing.app.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A credential belongs to one saved platform, never to the platform picker. */
data class ModelPlatform(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String>,
    val selectedModel: String = models.firstOrNull().orEmpty(),
    /** User-confirmed combined input/output capacity; absent means unknown. */
    val modelContextWindows: Map<String, Int> = emptyMap(),
)

object ModelPlatformCodec {
    fun parseContextWindow(text: String): Int? {
        if (text.isBlank()) return null
        return text.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("上下文总容量须为正整数（不超过2147483647），留空表示未设置")
    }

    fun contextWindows(names: List<String>, drafts: Map<String, String>): Map<String, Int> =
        names.mapNotNull { name -> parseContextWindow(drafts[name].orEmpty())?.let { name to it } }.toMap()

    fun hasDraftChanges(original: ModelPlatform, draft: ModelPlatform, modelText: String): Boolean =
        draft.copy(models = modelNames(modelText)) != original

    fun mergeDiscovered(existing: List<String>, discovered: List<String>): List<String> =
        (existing + discovered).map(String::trim).filter(String::isNotEmpty).distinct()

    fun modelNames(text: String): List<String> = text.split(Regex("[,，\\n\\r]+"))
        .map(String::trim).filter(String::isNotEmpty).distinct()

    fun encode(platforms: List<ModelPlatform>): String = JsonObject().apply {
        addProperty("version", 1)
        add("platforms", JsonArray().apply {
            platforms.forEach { p -> add(JsonObject().apply {
                require(p.modelContextWindows.all { (name, limit) -> name in p.models && limit > 0 }) {
                    "模型上下文容量配置无效"
                }
                addProperty("id", p.id)
                addProperty("name", p.name)
                addProperty("baseUrl", p.baseUrl)
                addProperty("apiKey", p.apiKey)
                addProperty("selectedModel", p.selectedModel)
                add("models", JsonArray().apply { p.models.forEach { add(it) } })
                if (p.modelContextWindows.isNotEmpty()) add("modelContextWindows", JsonObject().apply {
                    p.modelContextWindows.forEach { (model, limit) -> addProperty(model, limit) }
                })
            }) }
        })
    }.toString()

    fun decode(raw: String): List<ModelPlatform> {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("version").asInt == 1) { "平台配置版本暂不支持" }
        return root.getAsJsonArray("platforms").map { item ->
            val p = item.asJsonObject
            val names = p.getAsJsonArray("models").map { it.asString }.distinct()
            val windows = p.getAsJsonObject("modelContextWindows")?.entrySet()?.associate { (model, value) ->
                require(model in names && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                    "模型上下文容量配置无效"
                }
                val limit = try { value.asBigDecimal.intValueExact() } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("模型上下文容量配置无效")
                }
                require(limit > 0) { "模型上下文容量配置无效" }
                model to limit
            }.orEmpty()
            ModelPlatform(p.get("id").asString, p.get("name").asString,
                p.get("baseUrl").asString, p.get("apiKey").asString,
                names, p.get("selectedModel").asString, windows)
        }
    }
}
