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
    /** Requests OpenAI-compatible providers to append a final usage-only SSE event. */
    @Transient val includeUsage: Boolean = false,
    /** Role sampling is opt-in; auxiliary requests retain their existing provider defaults. */
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
)
