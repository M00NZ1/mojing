package com.mojing.app.ui.settings.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.domain.billing.BillingCurrencyRepository
import com.mojing.app.domain.billing.CurrencyDisplayState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@HiltViewModel
class BillingDisplayViewModel @Inject constructor(
    private val repository: BillingCurrencyRepository,
    private val costs: com.mojing.app.data.local.dao.CostRecordDao,
) : ViewModel() {
    val state: StateFlow<CurrencyDisplayState> = repository.state
    init {
        val cached = state.value
        if (cached.usdToCny == null || (cached.rateDate != "手动" && cached.rateDate != java.time.LocalDate.now().toString())) {
            refreshRate()
        }
    }
    fun observeRecord(id: Long) = costs.observeRecord(id).catch { emit(null) }
    fun setDisplayCurrency(currency: String) = repository.setDisplayCurrency(currency)
    fun refreshRate() { viewModelScope.launch { repository.refreshRate() } }
    fun setManualRate(rate: Double) = repository.setManualRate(rate)
}
