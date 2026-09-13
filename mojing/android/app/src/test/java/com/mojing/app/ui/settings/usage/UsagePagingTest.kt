package com.mojing.app.ui.settings.usage

import com.mojing.app.data.local.entity.CostRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class UsagePagingTest {
    private fun row(id: Long) = UsageRequestUi(CostRecordEntity(id = id))

    @Test fun retryDoesNotDuplicateAlreadyLoadedRows() {
        val merged = mergeUsageRequests(listOf(row(5), row(4)), listOf(row(5), row(3)), false)
        assertEquals(listOf(5L, 4L, 3L), merged.map { it.record.id })
    }

    @Test fun replacementDropsStalePageRows() {
        val merged = mergeUsageRequests(listOf(row(9)), listOf(row(8), row(8)), true)
        assertEquals(listOf(8L), merged.map { it.record.id })
    }
}
