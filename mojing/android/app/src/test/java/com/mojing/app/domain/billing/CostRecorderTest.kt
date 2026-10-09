package com.mojing.app.domain.billing

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.entity.CostRecordEntity
import com.mojing.app.domain.billing.BillingPriceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CostRecorderTest {
    private val dao = mockk<CostRecordDao>()
    private val preferences = mockk<BillingPriceRepository>(relaxed = true)
    private val storage = mockk<SecureStorage>()

    @Test
    fun apiUsageIsPreferredAndPriceSnapshotIsPersisted() = runTest {
        val price = ModelPricing("CNY", 2.0, 4.0)
        every { storage.modelPlatforms() } returns listOf(ModelPlatform("p", "平台", "https://api.test/v1", "key", listOf("m")))
        coEvery { preferences.price("p", "m") } returns price
        coEvery { dao.insertWithCurrentPrice(any(), any()) } answers { firstArg<CostRecordEntity>().copy(id = 9L) }
        val record = CostRecorder(dao, preferences, storage).recordLlm(
            sessionId = 1L, characterId = 2L, modelName = "m", provider = "平台",
            promptTokens = 100, completionTokens = 50, durationMs = 1200, success = true,
            request = BillingRequestSnapshot("p", "平台", price), usageProvided = true,
        )
        assertEquals(150, record.totalTokens)
        assertEquals("api", record.tokenSource)
        assertEquals("CNY", record.currency)
        assertTrue(record.costKnown)
        assertTrue(record.pricingSnapshotJson.contains("CNY"))
        assertEquals(9L, record.id)
    }

    @Test
    fun missingApiUsageFallsBackToTextAndUnknownPriceIsMarkedFalse() = runTest {
        coEvery { dao.insertWithCurrentPrice(any(), any()) } answers { firstArg<CostRecordEntity>().copy(id = 3L) }
        val record = CostRecorder(dao, preferences, storage).recordLlm(
            sessionId = null, characterId = null, modelName = "unknown", provider = "custom",
            promptTokens = 0, completionTokens = 0, durationMs = 10, success = false,
            promptTextFallback = "用户输入", completionTextFallback = "部分回复",
            request = BillingRequestSnapshot("p", "自定义", null), usageProvided = false,
            status = "failed",
        )
        assertFalse(record.costKnown)
        assertEquals("USD", record.currency)
        assertEquals("estimated", record.tokenSource)
        assertEquals("failed", record.status)
        assertEquals(0.0, record.estimatedCost, 0.0)
    }
    @org.junit.Test fun captureKeepsSameModelOnDifferentKeysAndNormalizedRoutesSeparate() = kotlinx.coroutines.test.runTest {
        val dao = io.mockk.mockk<com.mojing.app.data.local.dao.CostRecordDao>()
        val preferences = io.mockk.mockk<com.mojing.app.domain.billing.BillingPriceRepository>()
        val storage = io.mockk.mockk<com.mojing.app.data.SecureStorage>()
        io.mockk.every { storage.modelPlatforms() } returns listOf(
            com.mojing.app.data.ModelPlatform("a", "A", "https://api.test", "key-a", listOf("m")),
            com.mojing.app.data.ModelPlatform("b", "B", "https://api.test/v1", "key-b", listOf("m")))
        val price = ModelPricing("CNY", 1.0, 2.0)
        io.mockk.coEvery { preferences.price("b", "m") } returns price
        val snapshot = CostRecorder(dao, preferences, storage).capture("m", "https://api.test/v1", "key-b")
        org.junit.Assert.assertEquals("b", snapshot.platformId)
        org.junit.Assert.assertEquals(price, snapshot.price)
        io.mockk.coVerify(exactly = 0) { preferences.price("a", any()) }
    }

    @Test
    fun explicitPlatformIdWinsWhenPlatformsShareEndpointAndKey() = runTest {
        val platformA = ModelPlatform("a", "A", "https://api.test/v1", "same-key", listOf("m"))
        val platformB = ModelPlatform("b", "B", "https://api.test/v1", "same-key", listOf("m"))
        every { storage.modelPlatforms() } returns listOf(platformA, platformB)
        val price = ModelPricing("CNY", 3.0, 5.0)
        coEvery { preferences.price("b", "m") } returns price

        val snapshot = CostRecorder(dao, preferences, storage)
            .captureForPlatform("m", "https://api.test/v1", "same-key", "b")

        assertEquals("b", snapshot.platformId)
        assertEquals("B", snapshot.platformName)
        assertEquals(price, snapshot.price)
    }

    @Test
    fun unknownExplicitPlatformDoesNotGuessAnotherMatchingPlatform() = runTest {
        every { storage.modelPlatforms() } returns listOf(
            ModelPlatform("a", "A", "https://api.test/v1", "same-key", listOf("m")),
        )
        coEvery { preferences.price("missing", "m") } returns null

        val snapshot = CostRecorder(dao, preferences, storage)
            .captureForPlatform("m", "https://api.test/v1", "same-key", "missing")

        assertEquals("missing", snapshot.platformId)
        assertEquals("已选择平台", snapshot.platformName)
        assertNull(snapshot.price)
    }

}
