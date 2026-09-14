package com.mojing.app.ui.chat

import com.google.gson.JsonObject
import com.mojing.app.data.local.entity.CostRecordEntity

/** Refresh only the price of the exact saved request; imported legacy fields may be absent. */
internal fun mergeReplyUsage(saved: JsonObject?, record: CostRecordEntity?, sessionId: Long): JsonObject? {
    val result = saved?.deepCopy() ?: return null
    if (record == null) return result
    fun field(name: String): String? = saved.get(name)?.takeIf { it.isJsonPrimitive }?.asString
    val recordId = field("record_id")?.toLongOrNull()
    val platformId = field("platform_id")?.takeIf { it.isNotBlank() }
    val matchesPlatform = if (platformId != null) record.platformId == platformId
        else field("platform")?.takeIf { it.isNotBlank() } == record.platformName
    if (recordId != record.id || record.sessionId != sessionId ||
        field("model") != record.modelName || !matchesPlatform) return result
    result.addProperty("cost_known", record.costKnown)
    result.addProperty("cost", record.estimatedCost)
    result.addProperty("currency", record.currency)
    return result
}
