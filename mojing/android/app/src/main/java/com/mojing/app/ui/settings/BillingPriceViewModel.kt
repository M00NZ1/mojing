package com.mojing.app.ui.settings

import androidx.lifecycle.ViewModel
import com.mojing.app.domain.billing.BillingPriceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BillingPriceViewModel @Inject constructor(val prices: BillingPriceRepository) : ViewModel()
