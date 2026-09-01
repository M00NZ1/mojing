package com.mojing.app.domain.billing

/** 生图等「按次」粗略美元估算（无官方用量 JSON 时的占位）。 */
object MediaCostEstimator {

    fun imageUsdPerCall(model: String): Double {
        val m = model.trim().lowercase()
        return when {
            m.contains("dall-e-3") || m == "dall-e-3" -> 0.04
            m.contains("dall-e-2") -> 0.02
            m.contains("flux") -> 0.03
            else -> 0.02
        }
    }

    /** HTTP TTS：无 token 账单时用字符量 × 极低单价作「量级」参考（非厂商真实价）。 */
    fun voiceHeuristicUsd(charCount: Int): Double =
        kotlin.math.round((charCount.coerceAtLeast(0) / 1000.0) * 0.002 * 1e6) / 1e6
}
