package com.mojing.app.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class BillingMathTest {
    @Test
    fun cachedTokensUseCachedRateAndNeverExceedInput() {
        val price = ModelPricing("USD", 2.0, 4.0, 0.5)
        // 80 non-cached input + 20 cached input + 40 output, each per million.
        assertEquals(0.00033, BillingMath.estimate(price, 100, 40, 20), 1e-10)
        assertEquals(0.00021, BillingMath.estimate(price, 100, 40, 200), 1e-10)
    }

    @Test
    fun freeModelAndNegativeTokenCountsAreSafe() {
        val price = ModelPricing("CNY", 0.0, 0.0)
        assertEquals(0.0, BillingMath.estimate(price, 1000, 1000), 0.0)
        assertEquals(0.0, BillingMath.estimate(price, -1, -1, -1), 0.0)
    }

    @Test
    fun currenciesRemainPartOfPriceSnapshotAndAreNotCombined() {
        val usd = ModelPricing("USD", 1.0, 1.0)
        val cny = ModelPricing("CNY", 1.0, 1.0)
        assertEquals("USD", usd.currency)
        assertEquals("CNY", cny.currency)
        // BillingMath calculates one request in one currency; conversion/aggregation belongs to usage UI.
        assertEquals(0.000002, BillingMath.estimate(usd, 1, 1), 1e-12)
        assertEquals(0.000002, BillingMath.estimate(cny, 1, 1), 1e-12)
    }
}
