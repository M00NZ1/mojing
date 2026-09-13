package com.mojing.app.ui.settings.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.domain.billing.BillingCurrencyRepository
import com.mojing.app.domain.billing.CurrencyDisplayState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

data class UsageUiState(
    val level: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val currencies: List<UsageCurrencyUi> = emptyList(),
    val platforms: List<UsagePlatformUi> = emptyList(),
    val selectedPlatform: UsagePlatformUi? = null,
    val models: List<UsageModelUi> = emptyList(),
    val selectedModel: UsageModelUi? = null,
    val requests: List<UsageRequestUi> = emptyList(),
    val requestCursor: Long = Long.MAX_VALUE,
    val canLoadMore: Boolean = true,
)

internal fun mergeUsageRequests(existing: List<UsageRequestUi>, page: List<UsageRequestUi>, replace: Boolean): List<UsageRequestUi> {
    if (replace) return page.distinctBy { it.record.id }
    val ids = existing.asSequence().map { it.record.id }.toHashSet()
    return existing + page.filter { ids.add(it.record.id) }
}

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val dao: CostRecordDao,
    private val billingCurrency: BillingCurrencyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()
    val currencyState: StateFlow<CurrencyDisplayState> = billingCurrency.state
    private var loadJob: Job? = null
    private var generation = 0L

    init {
        refresh()
        val cached = billingCurrency.state.value
        if (cached.usdToCny == null || (cached.rateDate.isNotBlank() && cached.rateDate != "手动" && cached.rateDate != LocalDate.now().toString())) {
            viewModelScope.launch { billingCurrency.refreshRate() }
        }
    }

    fun setDisplayCurrency(currency: String) = billingCurrency.setDisplayCurrency(currency)
    fun refreshCurrency() { viewModelScope.launch { billingCurrency.refreshRate() } }
    fun setManualRate(rate: Double) = billingCurrency.setManualRate(rate)

    fun refresh() {
        loadJob?.cancel()
        val token = ++generation
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val currencies = dao.usageSummary().map { UsageCurrencyUi(it.currency, it.estimatedCost, it.totalTokens, it.totalCalls.toInt(), it.failedCalls.toInt(), it.unknownCostCalls.toInt()) }
                val platforms = dao.platformUsage().map { platform ->
                    val rows = dao.usageSummary(platform.platformId)
                    UsagePlatformUi(platform.platformId, platform.platformName, platform.totalTokens, platform.totalCalls.toInt(), platform.failedCalls.toInt(), rows.sumOf { it.unknownCostCalls }.toInt(),
                        rows.map { UsageCurrencyUi(it.currency, it.estimatedCost, it.totalTokens, it.totalCalls.toInt(), it.failedCalls.toInt(), it.unknownCostCalls.toInt()) })
                }
                currencies to platforms
            }.onSuccess { (currencies, platforms) -> if (token == generation) _state.value = UsageUiState(loading = false, currencies = currencies, platforms = platforms) }
                .onFailure { error -> if (error is CancellationException) throw error; if (token == generation) _state.value = _state.value.copy(loading = false, error = "用量记录读取失败，请重试") }
        }
    }

    fun openPlatform(platform: UsagePlatformUi) {
        loadJob?.cancel()
        val token = ++generation
        _state.value = _state.value.copy(level = 1, selectedPlatform = platform, selectedModel = null,
            models = emptyList(), requests = emptyList(), loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val models = dao.modelUsage(platform.id).map { row ->
                    val currencies = dao.usageSummary(platform.id, row.modelName)
                    UsageModelUi(platform.id, row.modelName, row.totalTokens, row.totalCalls.toInt(), row.failedCalls.toInt(),
                        currencies.sumOf { it.unknownCostCalls }.toInt(), currencies.map {
                            UsageCurrencyUi(it.currency, it.estimatedCost, it.totalTokens, it.totalCalls.toInt(), it.failedCalls.toInt(), it.unknownCostCalls.toInt())
                        })
                }
                if (token == generation) _state.value = _state.value.copy(loading = false, models = models)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) _state.value = _state.value.copy(loading = false, error = "平台用量读取失败，请重试")
            }
        }
    }

    fun openModel(model: UsageModelUi) {
        loadJob?.cancel(); ++generation
        _state.value = _state.value.copy(level = 2, selectedModel = model, requests = emptyList(), requestCursor = Long.MAX_VALUE, canLoadMore = true, loading = true, error = null)
        loadRequests(model, Long.MAX_VALUE, replace = true)
    }

    fun back(): Boolean {
        val current = _state.value
        if (current.level == 0) return false
        loadJob?.cancel(); ++generation
        _state.value = current.copy(level = current.level - 1, selectedModel = if (current.level == 2) null else current.selectedModel, requests = if (current.level == 2) emptyList() else current.requests, error = null, loading = false)
        return true
    }

    fun loadMoreRequests() {
        val current = _state.value
        val model = current.selectedModel ?: return
        if (!current.loading && current.canLoadMore) loadRequests(model, current.requestCursor, replace = false)
    }

    private fun loadRequests(model: UsageModelUi, beforeId: Long, replace: Boolean) {
        val token = generation
        _state.value = _state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            runCatching { dao.requestPage(model.platformId, model.name, beforeId, 40) }
                .onSuccess { rows ->
                    val records = rows.map(::UsageRequestUi)
                    val merged = mergeUsageRequests(_state.value.requests, records, replace)
                    if (token == generation) _state.value = _state.value.copy(loading = false, error = null, requests = merged, requestCursor = records.lastOrNull()?.record?.id ?: beforeId, canLoadMore = records.size == 40)
                }
                .onFailure { error -> if (error is CancellationException) throw error; if (token == generation) _state.value = _state.value.copy(loading = false, error = "请求记录读取失败，请重试") }
        }
    }
}
