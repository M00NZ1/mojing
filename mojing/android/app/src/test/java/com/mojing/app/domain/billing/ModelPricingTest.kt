package com.mojing.app.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelPricingTest {
    @Test
    fun acceptsFreeAndCachedRates() {
        val price = ModelPricing("USD", 0.0, 1.25, 0.15)
        assertEquals("USD", price.currency)
        assertEquals(1.25, price.outputPerMillion, 0.0)
    }

    @Test
    fun rejectsInvalidRatesAndCurrency() {
        assertThrows(IllegalArgumentException::class.java) { ModelPricing("EUR", 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { ModelPricing("CNY", -1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { ModelPricing("CNY", 1.0, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ModelPricing("CNY", 1.0, 1.0, Double.POSITIVE_INFINITY) }
    }
}
