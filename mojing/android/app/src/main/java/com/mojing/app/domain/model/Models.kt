package com.mojing.app.domain.model

data class Session(
    val id: Long = 0,
    val title: String = "新对话",
    val summary: String = "",
    val messageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class Character(
    val id: Long = 0,
    val name: String = "",
    val personaPrompt: String = "",
    val apiKey: String = "",
    val apiBaseUrl: String = "",
    val modelName: String = "",
    val temperature: Float = 0.8f,
    val maxTokens: Int = 2048
)

data class Message(
    val id: Long = 0,
    val sessionId: Long,
    val speakerType: String = "user",
    val characterId: Long? = null,
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
