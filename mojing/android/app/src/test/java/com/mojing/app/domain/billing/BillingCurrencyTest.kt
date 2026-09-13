package com.mojing.app.domain.billing

import org.junit.Assert.*
import org.junit.Test

class BillingCurrencyTest {
    @Test fun convertsUsdToCnyWithoutMixingCurrencies() {
        val text = formatBillingAmount(2.0, "USD", CurrencyDisplayState(displayCurrency = "CNY", usdToCny = 7.2))
        assertTrue(text.startsWith("¥14.400000"))
    }

    @Test fun keepsOriginalCurrencyWhenRateMissing() {
        assertTrue(formatBillingAmount(0.0000002, "USD", CurrencyDisplayState(displayCurrency = "CNY" )).contains("US$<0.000001"))
    }
    @Test fun ecbRatesAreCrossConvertedRegardlessOfAttributeOrder() {
        val result = parseEcbRate("""<Cube time="2026-09-14"><Cube rate="1.25" currency="USD"/><Cube currency="CNY" rate="9.0"/></Cube>""")
        assertEquals(7.2, result.first, 0.00001)
        assertEquals("2026-09-14", result.second)
    }
    @Test fun malformedRatesAreRejectedAndInvalidDisplayRateKeepsOriginalCurrency() {
        assertThrows(java.time.format.DateTimeParseException::class.java) { parseEcbRate("<Cube time='wrong'/>") }
        assertTrue(formatBillingAmount(2.0, "USD", CurrencyDisplayState(usdToCny = 0.0)).startsWith("US$2."))
        assertTrue(formatBillingAmount(2.0, "CNY", CurrencyDisplayState("USD", 7.2)).startsWith("US$0.277778"))
    }
}
