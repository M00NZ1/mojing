package com.mojing.app.ui.chat

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReplyGenerationMetadataTest {
    @Test fun completedUsageSurvivesEditingWithOriginalCurrencyAndRecordIdentity() {
        val record = com.mojing.app.data.local.entity.CostRecordEntity(id = 42, modelName = "model",
            platformName = "渠道", promptTokens = 10, completionTokens = 20, totalTokens = 30,
            estimatedCost = 0.0012, currency = "CNY", costKnown = true, tokenSource = "api")
        val generated = ReplyGenerationMetadata.record("{}", 2000, record)
        val edited = ReplyGenerationMetadata.preserve(generated, "{\"choices\":[\"继续\"]}")
        val usage = ReplyGenerationMetadata.usage(edited)!!
        assertEquals(42, usage.get("record_id").asInt)
        assertEquals(30, usage.get("total_tokens").asInt)
        assertEquals("CNY", usage.get("currency").asString)
        assertEquals(0.0012, usage.get("cost").asDouble, 0.0)
    }
    @Test fun oldOrInterruptedMessagesDoNotInventDuration() {
        listOf("{}", "", "invalid", "{\"generation_duration_ms\":-1}").forEach {
            assertNull(ReplyGenerationMetadata.durationLabel(it))
        }
    }

    @Test fun finishedReplyKeepsChoicesAndDisplaysTotal() {
        val json = ReplyGenerationMetadata.record("{\"choices\":[\"继续\"]}", 1250)
        assertEquals("生成 1.3 秒", ReplyGenerationMetadata.durationLabel(json))
        assertEquals("继续", JsonParser.parseString(json).asJsonObject.getAsJsonArray("choices")[0].asString)
        assertEquals("生成 2 分 5 秒", ReplyGenerationMetadata.durationLabel(ReplyGenerationMetadata.record("{}", 125_000)))
    }

    @Test fun editingKeepsTimingAndChapterIdentityWhileUpdatingChoices() {
        val edited = ReplyGenerationMetadata.preserve(
            "{\"chapter_title\":\"第一章\",\"generation_duration_ms\":3000,\"choices\":[\"旧\"]}",
            "{\"choices\":[\"新\"]}",
        )
        val json = JsonParser.parseString(edited).asJsonObject
        assertEquals("第一章", json.get("chapter_title").asString)
        assertEquals("生成 3.0 秒", ReplyGenerationMetadata.durationLabel(edited))
        assertEquals("新", json.getAsJsonArray("choices")[0].asString)
    }
}
