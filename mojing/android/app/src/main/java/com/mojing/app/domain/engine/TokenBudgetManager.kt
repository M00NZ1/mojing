package com.mojing.app.domain.engine

import javax.inject.Inject

data class TokenBudget(
    val systemPrompt: Int,
    val longTermMemory: Int,
    val midTermMemory: Int,
    val shortTermWindow: Int,
    val reservedForOutput: Int
)

class TokenBudgetManager @Inject constructor() {
    fun calculateBudget(
        maxContextTokens: Int,
        maxOutputTokens: Int
    ): TokenBudget {
        val available = maxContextTokens - maxOutputTokens
        return TokenBudget(
            systemPrompt = (available * 0.10).toInt(),
            longTermMemory = (available * 0.15).toInt(),
            midTermMemory = (available * 0.20).toInt(),
            shortTermWindow = (available * 0.55).toInt(),
            reservedForOutput = maxOutputTokens
        )
    }
}
