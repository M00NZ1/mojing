package com.mojing.app.domain.billing

import com.google.gson.Gson
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.entity.CostRecordEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BillingHistoryPolicyTest {
    private val price = ModelPricing("CNY", 2.0, 4.0, 0.5)
    private val snapshot = Gson().toJson(price)
    private fun dao(hasHistory: Boolean): CostRecordDao = mockk<CostRecordDao>(relaxed = true).also { dao ->
        coEvery { dao.hasPricedHistory("p", "m") } returns hasHistory
        coEvery { dao.savePriceAndHistory(any(), any(), any(), any(), any(), any()) } coAnswers { callOriginal() }
    }

    @Test fun firstPriceBackfillsUnknownHistoryAutomatically() = runTest {
        val dao = dao(false)
        dao.savePriceAndHistory("key", "p", "m", price, snapshot, false)
        coVerify(exactly = 1) { dao.repriceHistory("p", "m", 2.0, 4.0, 0.5, "CNY", snapshot, false) }
    }

    @Test fun changingPriceWithoutSwitchNeverRewritesHistory() = runTest {
        val dao = dao(true)
        dao.savePriceAndHistory("key", "p", "m", price, snapshot, false)
        coVerify(exactly = 1) { dao.writePrice(match { it.key == "key" && it.valueJson == snapshot }) }
        coVerify(exactly = 0) { dao.repriceHistory(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun explicitSwitchRepricesKnownAndUnknownRecords() = runTest {
        val dao = dao(true)
        dao.savePriceAndHistory("key", "p", "m", price, snapshot, true)
        coVerify(exactly = 1) { dao.repriceHistory("p", "m", 2.0, 4.0, 0.5, "CNY", snapshot, true) }
    }

    @Test fun unpricedRequestFinishingAfterPriceSaveGetsTheNewPrice() = runTest {
        val dao = dao(false)
        val record = CostRecordEntity(platformId = "p", modelName = "m", costKnown = false,
            promptTokens = 1_000_000, completionTokens = 500_000, cachedPromptTokens = 200_000)
        coEvery { dao.storedPrice("key") } returns snapshot
        coEvery { dao.insert(any()) } returns 42L
        coEvery { dao.insertWithCurrentPrice(any(), any()) } coAnswers { callOriginal() }
        val saved = dao.insertWithCurrentPrice(record, "key")
        assertEquals(42L, saved.id)
        assertEquals(3.7, saved.estimatedCost, 0.000001)
        assertEquals("CNY", saved.currency)
        assertTrue(saved.costKnown)
        val old = record.copy(costKnown = true, estimatedCost = 9.0, currency = "USD")
        assertEquals(9.0, dao.insertWithCurrentPrice(old, "key").estimatedCost, 0.0)
    }
}
