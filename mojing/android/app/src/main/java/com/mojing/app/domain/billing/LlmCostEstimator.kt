package com.mojing.app.domain.billing

/**
 * 按模型名估算 USD（与后端 [cost_service.MODEL_PRICES] 对齐思路；未知模型用保守默认）。
 * 定价为「每千 token」美元；仅作本地参考，实际以各平台账单为准。
 */
object LlmCostEstimator {

    private data class Rates(val promptPer1k: Double, val completionPer1k: Double)

    private val table: Map<String, Rates> = mapOf(
        "deepseek-chat" to Rates(0.00014, 0.00028),
        "deepseek-reasoner" to Rates(0.00055, 0.00110),
        "gpt-4o" to Rates(0.00250, 0.01000),
        "gpt-4o-mini" to Rates(0.00015, 0.00060),
        "claude-3-opus" to Rates(0.01500, 0.07500),
        "claude-3-sonnet" to Rates(0.00300, 0.01500),
        "claude-3-haiku" to Rates(0.00025, 0.00125),
        "gemini-pro" to Rates(0.000125, 0.000375),
    )

    private val defaultRates = Rates(0.00015, 0.00060)

    private fun ratesFor(modelName: String): Rates {
        val key = modelName.trim().lowercase()
        val sorted = table.entries.sortedByDescending { it.key.length }
        return sorted.find { key.contains(it.key.lowercase()) }?.value ?: defaultRates
    }

    fun estimateUsd(modelName: String, promptTokens: Int, completionTokens: Int): Double {
        val rates = ratesFor(modelName)
        val p = promptTokens.coerceAtLeast(0) / 1000.0 * rates.promptPer1k
        val c = completionTokens.coerceAtLeast(0) / 1000.0 * rates.completionPer1k
        return kotlin.math.round((p + c) * 1e8) / 1e8
    }
}
