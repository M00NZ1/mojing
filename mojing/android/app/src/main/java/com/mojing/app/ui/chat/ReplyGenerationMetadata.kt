package com.mojing.app.ui.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.CostRecordEntity
import java.util.Locale

internal object ReplyGenerationMetadata {
    fun record(json: String, elapsedMillis: Long, usage: CostRecordEntity? = null): String = objectOrEmpty(json).apply {
        addProperty("generation_duration_ms", elapsedMillis.coerceAtLeast(1L))
        usage?.let {
            add("generation_usage", JsonObject().apply {
                addProperty("record_id", it.id)
                addProperty("input_tokens", it.promptTokens)
                addProperty("output_tokens", it.completionTokens)
                addProperty("cached_tokens", it.cachedPromptTokens)
                addProperty("total_tokens", it.totalTokens)
                addProperty("token_source", it.tokenSource)
                addProperty("cost_known", it.costKnown)
                addProperty("cost", it.estimatedCost)
                addProperty("currency", it.currency)
                addProperty("platform", it.platformName)
                addProperty("platform_id", it.platformId)
                addProperty("model", it.modelName)
            })
        }
    }.toString()

    fun usage(json: String): JsonObject? = runCatching {
        objectOrEmpty(json).getAsJsonObject("generation_usage")
    }.getOrNull()

    fun preserve(original: String, edited: String): String = objectOrEmpty(original).apply {
        objectOrEmpty(edited).entrySet().forEach { (key, value) -> add(key, value) }
    }.toString()

    fun durationLabel(json: String): String? {
        val ms = runCatching { objectOrEmpty(json).get("generation_duration_ms")?.asLong }.getOrNull()
            ?.takeIf { it > 0 } ?: return null
        val seconds = ms / 1000.0
        return if (seconds < 60) String.format(Locale.ROOT, "生成 %.1f 秒", seconds)
        else "生成 ${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
    }

    private fun objectOrEmpty(json: String): JsonObject =
        runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { JsonObject() }
}
