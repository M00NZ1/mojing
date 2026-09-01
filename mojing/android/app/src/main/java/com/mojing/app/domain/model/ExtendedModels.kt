package com.mojing.app.domain.model

data class MemorySegment(val id: Long = 0, val summary: String = "", val emotionalTone: String = "中性")
data class EventNode(val id: Long = 0, val title: String = "", val importance: Int = 1)
data class Branch(val id: Long = 0, val branchId: String = "", val label: String = "")
data class Participant(val id: Long = 0, val characterId: Long = 0)
data class SessionWorld(val id: Long = 0, val gameplayMode: String = "")
data class CharacterState(
    val id: Long = 0,
    val sessionId: Long = 0,
    val characterId: Long = 0,
    val dynamicStateJson: String = "{}",
    val relationsJson: String = "{}",
    val emotionalState: String = ""
)
data class CostRecord(val id: Long = 0, val totalTokens: Int = 0, val estimatedCost: Double = 0.0)
data class ApiConfig(val apiKey: String = "", val baseUrl: String = "", val model: String = "")
data class EncyclopediaEntry(val id: Long = 0, val title: String = "", val entryType: String = "")
