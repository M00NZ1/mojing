package com.mojing.app.domain.model

data class WorldTemplate(
    val id: Long = 0,
    val templateId: String = "",
    val label: String = "",
    val category: String = "",
    val summary: String = "",
    val gameplayMode: String = "自由剧情",
    val worldPrompt: String = "",
    val coverImagePath: String = "",
    val suggestedChoicesJson: String = "[]",
    val antiCheatPrompt: String = "",
    val isBuiltin: Boolean = false
)