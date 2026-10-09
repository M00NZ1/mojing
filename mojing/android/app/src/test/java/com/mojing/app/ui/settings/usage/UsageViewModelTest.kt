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
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = mockk<CostRecordDao>()
    private val currency = mockk<BillingCurrencyRepository>()
    private fun platform(id: String) = UsagePlatformUi(id, id, 10, 2, 0, 0)
    private fun model(id: String = "p") = UsageModelUi(id, "m", 10, 2, 0, 0)
    private fun summary() = UsageCurrencySummary("CNY", 2, 0, 0, 4, 6, 10, 0.01, 0)
    private fun day(month: YearMonth, label: String = month.toString()) = DailyUsageSummary(label, "CNY", 0.01, 0, 10, 2)
    private fun monthPlatform(id: String, tokens: Long = 10) = MonthlyPlatformUsage(id, id, "CNY", 0.01, tokens, 2, 0, 0)
    private fun bounds(month: YearMonth): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { currency.state } returns MutableStateFlow(CurrencyDisplayState(usdToCny = 7.2, rateDate = "手动"))
        coEvery { dao.usageSummary(any(), any()) } returns listOf(summary())
        coEvery { dao.dailyUsage(any(), any()) } returns listOf(day(YearMonth.now()))
        coEvery { dao.monthlyPlatformUsage(any(), any()) } returns listOf(monthPlatform("p"))
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

    @Test fun changingMonthQueriesExactlyTheSelectedMonthBounds() = runTest(dispatcher) {
        val capturedDaily = mutableListOf<Pair<Long, Long>>()
        val capturedMonthly = mutableListOf<Pair<Long, Long>>()
        coEvery { dao.dailyUsage(any(), any()) } coAnswers {
            capturedDaily += firstArg<Long>() to secondArg<Long>()
            listOf(day(YearMonth.now()))
        }
        coEvery { dao.monthlyPlatformUsage(any(), any()) } coAnswers {
            capturedMonthly += firstArg<Long>() to secondArg<Long>()
            listOf(monthPlatform("p"))
        }
        val vm = UsageViewModel(dao, currency)
        advanceUntilIdle()
        val selected = YearMonth.now().minusMonths(1)
        vm.setMonth(selected)
        advanceUntilIdle()
        assertEquals(selected, vm.state.value.selectedMonth)
        assertEquals(bounds(YearMonth.now()), capturedDaily.first())
        assertEquals(bounds(selected), capturedDaily.last())
        assertEquals(bounds(selected), capturedMonthly.last())
    }

    @Test fun newlyCreatedOwnerRestoresOnlyMonthIdentityAndReloadsItsBounds() = runTest(dispatcher) {
        val saved = androidx.lifecycle.SavedStateHandle()
        val first = UsageViewModel(dao, currency, saved)
        advanceUntilIdle()
        val month = YearMonth.now().minusMonths(2)
        first.setMonth(month)
        advanceUntilIdle()
        val restored = androidx.lifecycle.SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val second = UsageViewModel(dao, currency, restored)
        advanceUntilIdle()
        assertEquals(month, second.state.value.selectedMonth)
        assertEquals(month, second.state.value.loadedMonth)
        val range = bounds(month)
        coVerify(exactly = 2) { dao.dailyUsage(range.first, range.second) }
        assertTrue(saved.keys().size == 1)
    }

    @Test fun invalidOrFutureSavedMonthsFallBackWithoutChangingRouteIdentity() = runTest(dispatcher) {
        listOf<Any>("broken", "2026-99", "10000-01", "0000-01", 17, YearMonth.now().plusMonths(1).toString()).forEach { value ->
            val handle = androidx.lifecycle.SavedStateHandle(mapOf("usage_selected_month" to value))
            val vm = UsageViewModel(dao, currency, handle)
            advanceUntilIdle()
            assertEquals(YearMonth.now(), vm.state.value.selectedMonth)
            assertEquals(YearMonth.now(), vm.state.value.loadedMonth)
        }
        coEvery { dao.requestPage("p", "m", any(), 40) } returns emptyList()
        val month = YearMonth.now().minusMonths(3)
        val handle = androidx.lifecycle.SavedStateHandle(mapOf("usage_selected_month" to month.toString(), "platformId" to "p", "modelName" to "m"))
        val vm = UsageViewModel(dao, currency, handle)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.level)
        assertEquals("p", vm.state.value.selectedModel?.platformId)
        assertEquals(month, vm.state.value.selectedMonth)
        assertNull(vm.state.value.loadedMonth)
    }

    @Test fun failedMonthSelectionPersistsIntentButKeepsLoadedMonthUntilRetry() = runTest(dispatcher) {
        val handle = androidx.lifecycle.SavedStateHandle()
        val vm = UsageViewModel(dao, currency, handle)
        advanceUntilIdle()
        val previous = YearMonth.now().minusMonths(1)
        val range = bounds(previous)
        coEvery { dao.dailyUsage(range.first, range.second) } throws IllegalStateException()
        vm.setMonth(previous); advanceUntilIdle()
        assertEquals(YearMonth.now(), vm.state.value.loadedMonth)
        assertEquals(previous.toString(), handle.get<String>("usage_selected_month"))
        coEvery { dao.dailyUsage(range.first, range.second) } returns listOf(day(previous))
        vm.refresh(); advanceUntilIdle()
        assertEquals(previous, vm.state.value.loadedMonth)
        assertNull(vm.state.value.error)
        coVerify(exactly = 2) { dao.dailyUsage(range.first, range.second) }
    }

    @Test fun lateCompletionFromOlderMonthCannotOverwriteNewMonth() = runTest(dispatcher) {
        val oldMonth = YearMonth.now().minusMonths(1)
        val newMonth = oldMonth.minusMonths(1)
        val oldBounds = bounds(oldMonth)
        val gate = CompletableDeferred<Unit>()
        coEvery { dao.dailyUsage(any(), any()) } coAnswers {
            if (firstArg<Long>() == oldBounds.first) {
                withContext(NonCancellable) { gate.await() }
                listOf(day(oldMonth, "old"))
            } else listOf(day(newMonth, "new"))
        }
        coEvery { dao.monthlyPlatformUsage(any(), any()) } returns listOf(monthPlatform("new"))
        val vm = UsageViewModel(dao, currency)
        advanceUntilIdle()
        vm.setMonth(oldMonth)
        runCurrent()
        vm.setMonth(newMonth)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(newMonth, vm.state.value.selectedMonth)
        assertEquals("new", vm.state.value.daily.single().day)
    }

    @Test fun failedMonthRefreshKeepsDisplayedDailyAndPlatformData() = runTest(dispatcher) {
        val vm = UsageViewModel(dao, currency)
        advanceUntilIdle()
        val displayedDaily = vm.state.value.daily
        val displayedPlatforms = vm.state.value.platforms
        coEvery { dao.dailyUsage(any(), any()) } throws IllegalStateException("offline")
        vm.setMonth(YearMonth.now().minusMonths(1))
        advanceUntilIdle()
        assertEquals(displayedDaily, vm.state.value.daily)
        assertEquals(displayedPlatforms, vm.state.value.platforms)
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
    @Test fun filteredTokenPagingKeepsPairCursor() = runTest(dispatcher) {
        coEvery { dao.requestPage(any(), any(), any(), any()) } returns emptyList()
        val page = (80L downTo 41L).map { CostRecordEntity(id = it, platformId = "p", modelName = "m", totalTokens = 500) }
        coEvery { dao.filteredRequestPage("p", "m", Long.MAX_VALUE, Long.MAX_VALUE, 40, "success", "tokens") } returns page
        coEvery { dao.filteredRequestPage("p", "m", 41, 500, 40, "success", "tokens") } returns listOf(CostRecordEntity(id = 40, totalTokens = 500))
        val vm = UsageViewModel(dao, currency); advanceUntilIdle()
        vm.openDestination("p", "平台", "m"); advanceUntilIdle()
        vm.filterRequests("success", "tokens"); advanceUntilIdle()
        vm.loadMoreRequests(); advanceUntilIdle()
        assertEquals(41, vm.state.value.requests.size)
        assertEquals(40L, vm.state.value.requestCursor)
        assertFalse(vm.state.value.canLoadMore)
    }

    @Test fun deepModelDestinationSkipsOverviewAndKeepsFilteredPagesOnReentry() = runTest(dispatcher) {
        coEvery { dao.requestPage("p", "m", any(), 40) } returns emptyList()
        coEvery { dao.filteredRequestPage("p", "m", any(), any(), 40, "failed", "tokens") } returns listOf(CostRecordEntity(id = 7, platformId = "p", modelName = "m"))
        val vm = UsageViewModel(dao, currency, androidx.lifecycle.SavedStateHandle(mapOf("platformId" to "p", "platformName" to "渠道", "modelName" to "m")))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.selectedModel?.calls)
        assertEquals(10L, vm.state.value.selectedModel?.tokens)
        assertEquals("CNY", vm.state.value.selectedModel?.currencies?.single()?.currency)
        coVerify(exactly = 0) { dao.usageSummary(null, null) }
        coVerify(exactly = 0) { dao.modelUsage(any()) }
        vm.filterRequests("failed", "tokens"); advanceUntilIdle()
        val before = vm.state.value
        vm.openDestination("p", "渠道", "m"); advanceUntilIdle()
        assertEquals(before, vm.state.value)
        coVerify(exactly = 1) { dao.filteredRequestPage("p", "m", any(), any(), 40, "failed", "tokens") }
    }

    @Test fun deepPlatformDestinationLoadsItsOwnTotals() = runTest(dispatcher) {
        val vm = UsageViewModel(dao, currency, androidx.lifecycle.SavedStateHandle(mapOf("platformId" to "p", "platformName" to "渠道")))
        advanceUntilIdle()
        assertEquals("渠道", vm.state.value.selectedPlatform?.name)
        assertEquals(2, vm.state.value.selectedPlatform?.calls)
        assertEquals(10L, vm.state.value.selectedPlatform?.tokens)
        assertEquals("CNY", vm.state.value.selectedPlatform?.currencies?.single()?.currency)
        coVerify(exactly = 0) { dao.usageSummary(null, null) }
    }

    @Test fun reenteringLoadingPlatformDoesNotRestartItsQuery() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<ModelChannelUsageSummary>>()
        coEvery { dao.modelUsage("p") } coAnswers { gate.await() }
        val vm = UsageViewModel(dao, currency, androidx.lifecycle.SavedStateHandle(mapOf("platformId" to "p")))
        runCurrent()
        vm.openDestination("p", "渠道", null); runCurrent()
        assertTrue(vm.state.value.loading)
        coVerify(exactly = 1) { dao.modelUsage("p") }
        gate.complete(emptyList()); advanceUntilIdle()
        assertFalse(vm.state.value.loading)
    }

}
