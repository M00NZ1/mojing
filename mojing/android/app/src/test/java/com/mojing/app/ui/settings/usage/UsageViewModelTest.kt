package com.mojing.app.ui.settings.usage

import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.CostRecordEntity
import com.mojing.app.domain.billing.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = mockk<CostRecordDao>()
    private val currency = mockk<BillingCurrencyRepository>()
    private fun platform(id: String) = UsagePlatformUi(id, id, 10, 2, 0, 0)
    private fun model(id: String = "p") = UsageModelUi(id, "m", 10, 2, 0, 0)
    private fun summary() = UsageCurrencySummary("CNY", 2, 0, 0, 4, 6, 10, 0.01, 0)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { currency.state } returns MutableStateFlow(CurrencyDisplayState(usdToCny = 7.2, rateDate = "手动"))
        coEvery { dao.usageSummary(any(), any()) } returns listOf(summary())
        coEvery { dao.platformUsage() } returns listOf(PlatformUsageSummary("p", "渠道", 2, 0, 10))
        coEvery { dao.modelUsage(any()) } returns listOf(ModelChannelUsageSummary("m", 2, 0, 10))
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun failedRefreshKeepsOriginalCurrencyTotals() = runTest(dispatcher) {
        val vm = UsageViewModel(dao, currency)
        advanceUntilIdle()
        assertEquals("CNY", vm.state.value.currencies.single().currency)
        assertEquals(0.01, vm.state.value.currencies.single().cost, 0.0)
        coEvery { dao.usageSummary(null, null) } throws IllegalStateException()
        vm.refresh(); advanceUntilIdle()
        assertEquals(0.01, vm.state.value.currencies.single().cost, 0.0)
        assertNotNull(vm.state.value.error)
    }

    @Test fun slowPlatformSummaryCannotOverwriteNewPlatform() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { dao.usageSummary("a", "m") } coAnswers {
            withContext(NonCancellable) { gate.await() }
            listOf(summary().copy(totalTokens = 999))
        }
        val vm = UsageViewModel(dao, currency)
        advanceUntilIdle()
        vm.openPlatform(platform("a")); runCurrent()
        vm.openPlatform(platform("b")); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("b", vm.state.value.selectedPlatform?.id)
        assertEquals("b", vm.state.value.models.single().platformId)
    }

    @Test fun leavingRequestsRejectsLateResults() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { dao.requestPage(any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
            listOf(CostRecordEntity(id = 9))
        }
        val vm = UsageViewModel(dao, currency)
        advanceUntilIdle()
        vm.openPlatform(platform("p")); advanceUntilIdle()
        vm.openModel(model()); runCurrent()
        vm.back(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, vm.state.value.level)
        assertTrue(vm.state.value.requests.isEmpty())
        assertFalse(vm.state.value.loading)
    }

    @Test fun pagingFailureRetainsRecordsAndRetryUsesSameCursor() = runTest(dispatcher) {
        coEvery { dao.requestPage("p", "m", Long.MAX_VALUE, 40) } returns (80L downTo 41L).map { CostRecordEntity(id = it) }
        coEvery { dao.requestPage("p", "m", 41, 40) } throws IllegalStateException()
        val vm = UsageViewModel(dao, currency)
        advanceUntilIdle()
        vm.openModel(model()); advanceUntilIdle()
        vm.loadMoreRequests(); vm.loadMoreRequests(); advanceUntilIdle()
        assertEquals(40, vm.state.value.requests.size)
        assertNotNull(vm.state.value.error)
        coEvery { dao.requestPage("p", "m", 41, 40) } returns listOf(CostRecordEntity(id = 40), CostRecordEntity(id = 40))
        vm.loadMoreRequests(); advanceUntilIdle()
        assertEquals(41, vm.state.value.requests.size)
        assertEquals(40L, vm.state.value.requestCursor)
        assertFalse(vm.state.value.canLoadMore)
        coVerify(exactly = 2) { dao.requestPage("p", "m", 41, 40) }
    }
}
