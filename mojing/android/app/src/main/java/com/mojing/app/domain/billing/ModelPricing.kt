package com.mojing.app.domain.billing

/**
 * A user-maintained price for one model. Rates are expressed per one million
 * tokens, so the values can be copied from a provider's pricing page without
 * converting units first.
 */
data class ModelPricing(
    val currency: String = "CNY",
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cachedInputPerMillion: Double? = null,
    val source: String = "manual",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(currency == "USD" || currency == "CNY") { "价格币种必须是 USD 或 CNY" }
        require(inputPerMillion.isFinite() && inputPerMillion >= 0.0) { "输入价格必须是非负数字" }
        require(outputPerMillion.isFinite() && outputPerMillion >= 0.0) { "输出价格必须是非负数字" }
        require(cachedInputPerMillion == null || (cachedInputPerMillion.isFinite() && cachedInputPerMillion >= 0.0)) {
            "缓存输入价格必须是非负数字"
        }
    }
}
