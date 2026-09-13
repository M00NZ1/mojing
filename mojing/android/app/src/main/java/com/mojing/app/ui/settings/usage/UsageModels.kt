package com.mojing.app.ui.settings.usage

import com.mojing.app.data.local.entity.CostRecordEntity

/** 用量页只依赖这些展示模型，避免把数据库聚合字段散落到 Composable。 */
data class UsageCurrencyUi(
    val currency: String,
    val cost: Double,
    val tokens: Long,
    val calls: Int,
    val failed: Int,
    val unknownPrice: Int,
)

data class UsagePlatformUi(
    val id: String,
    val name: String,
    val tokens: Long,
    val calls: Int,
    val failed: Int,
    val unknownPrice: Int,
    val currencies: List<UsageCurrencyUi> = emptyList(),
)

data class UsageModelUi(
    val platformId: String,
    val name: String,
    val tokens: Long,
    val calls: Int,
    val failed: Int,
    val unknownPrice: Int,
    val currencies: List<UsageCurrencyUi> = emptyList(),
)

data class UsageRequestUi(val record: CostRecordEntity)
