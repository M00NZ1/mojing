package com.mojing.app.ui.chat

import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.CostRecordEntity
import org.junit.Assert.*
import org.junit.Test

class ReplyUsageDisplayTest {
    private val saved = JsonParser.parseString("""{"record_id":7,"platform_id":"a","platform":"平台","model":"m","cost":1,"cost_known":false,"total_tokens":123}""").asJsonObject
    private val record = CostRecordEntity(id = 7, sessionId = 2, platformId = "a", platformName = "平台", modelName = "m", estimatedCost = 3.0, currency = "CNY")

    @Test fun updatesPriceWithoutChangingSavedTokensOrOriginalMetadata() {
        val updated = mergeReplyUsage(saved, record, 2)!!
        assertEquals(3.0, updated.get("cost").asDouble, 0.0)
        assertEquals("CNY", updated.get("currency").asString)
        assertEquals(123, updated.get("total_tokens").asInt)
        assertEquals(1, saved.get("cost").asInt)
    }

    @Test fun rejectsDifferentRequestSessionPlatformAndModel() {
        listOf(record.copy(id = 8), record.copy(sessionId = 3), record.copy(platformId = "b"), record.copy(modelName = "other")).forEach {
            assertEquals(saved, mergeReplyUsage(saved, it, 2))
        }
    }

    @Test fun legacyNamesRequireExactRequestAndNullFieldsDoNotCrash() {
        val legacy = saved.deepCopy().apply { remove("platform_id") }
        assertEquals(3.0, mergeReplyUsage(legacy, record, 2)!!.get("cost").asDouble, 0.0)
        assertEquals(legacy, mergeReplyUsage(legacy, record.copy(platformName = "其他平台"), 2))
        listOf("model", "platform_id", "record_id", "platform").forEach { name ->
            val broken = legacy.deepCopy().apply { add(name, com.google.gson.JsonNull.INSTANCE) }
            val result = mergeReplyUsage(broken, record, 2)
            assertNotNull(result)
            if (name != "platform_id") assertEquals(broken, result)
        }
    }
}
