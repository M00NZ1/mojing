package com.mojing.app.data.remote

/** Provider-reported token usage. A null usage callback means the API omitted usage. */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedPromptTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
