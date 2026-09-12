package com.mojing.app.data.remote

/** 非流式 chat/completions 一次调用的内容与用量（OpenAI 兼容 `usage` 字段）。 */
data class ChatCompletionResult(
    val content: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val finishReason: String? = null,
)
