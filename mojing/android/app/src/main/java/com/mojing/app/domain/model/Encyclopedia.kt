package com.mojing.app.domain.model

data class Encyclopedia(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val coverImagePath: String = "",
    val isOfficial: Boolean = false,
    val genreTags: String = "",
    val worldPrompt: String = "",
    val gameplayMode: String = "自由剧情",
    val antiCheatPrompt: String = "",
    val entryCount: Int = 0
)
