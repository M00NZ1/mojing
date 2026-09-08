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
)

object ModelPlatformCodec {
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
                addProperty("id", p.id)
                addProperty("name", p.name)
                addProperty("baseUrl", p.baseUrl)
                addProperty("apiKey", p.apiKey)
                addProperty("selectedModel", p.selectedModel)
                add("models", JsonArray().apply { p.models.forEach { add(it) } })
            }) }
        })
    }.toString()

    fun decode(raw: String): List<ModelPlatform> {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("version").asInt == 1) { "平台配置版本暂不支持" }
        return root.getAsJsonArray("platforms").map { item ->
            val p = item.asJsonObject
            ModelPlatform(p.get("id").asString, p.get("name").asString,
                p.get("baseUrl").asString, p.get("apiKey").asString,
                p.getAsJsonArray("models").map { it.asString }, p.get("selectedModel").asString)
        }
    }
}
