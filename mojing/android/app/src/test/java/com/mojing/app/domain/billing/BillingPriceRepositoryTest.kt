package com.mojing.app.domain.billing

import com.google.gson.Gson
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.repository.BillingPreferences
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BillingPriceRepositoryTest {
    @Test fun storedPriceOverridesLegacyAndClearDoesNotReviveIt() = runTest {
        val dao = mockk<CostRecordDao>()
        val legacy = mockk<BillingPreferences>()
        val repository = BillingPriceRepository(dao, legacy)
        val price = ModelPricing("USD", 2.0, 3.0)
        coEvery { dao.storedPrice(any()) } returns null
        every { legacy.price("p", "m") } returns price
        assertEquals(price, repository.price("p", "m"))
        coEvery { dao.storedPrice(any()) } returns Gson().toJson(price.copy(currency = "CNY"))
        assertEquals("CNY", repository.price("p", "m")?.currency)
        coEvery { dao.storedPrice(any()) } returns "null"
        assertNull(repository.price("p", "m"))
        verify(exactly = 1) { legacy.price(any(), any()) }
    }

    @Test fun platformAndModelKeysCannotCollide() {
        val repository = BillingPriceRepository(mockk(), mockk())
        assertNotEquals(repository.key("a:b", "c"), repository.key("a", "b:c"))
        assertNotEquals(repository.key("a", "m"), repository.key("b", "m"))
        assertNotEquals(repository.key("a", "m"), repository.key("a", "M"))
    }
}
