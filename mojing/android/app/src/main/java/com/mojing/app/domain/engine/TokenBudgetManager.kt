package com.mojing.app.domain.engine

import javax.inject.Inject

data class TokenBudget(
    val systemPrompt: Int,
    val longTermMemory: Int,
    val midTermMemory: Int,
    val shortTermWindow: Int,
    val reservedForOutput: Int,
    val contextWindow: Int? = null,
)

class TokenBudgetManager @Inject constructor() {
    fun calculateBudget(
        maxContextTokens: Int,
        maxOutputTokens: Int,
        contextWindow: Int? = null,
    ): TokenBudget {
        val available = (maxContextTokens.toLong() - maxOutputTokens).coerceAtLeast(0L)
        return TokenBudget(
            systemPrompt = (available * 0.10).toInt(),
            longTermMemory = (available * 0.15).toInt(),
            midTermMemory = (available * 0.20).toInt(),
            shortTermWindow = (available * 0.55).toInt(),
            reservedForOutput = maxOutputTokens,
            contextWindow = contextWindow,
        )
    }
}
