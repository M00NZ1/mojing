package com.mojing.app.data.remote

data class ChatMessage(
    val role: String,
    val content: String,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float,
    val max_tokens: Int,
    val stream: Boolean = false,
    @Transient val jsonOutput: Boolean = false,
)
