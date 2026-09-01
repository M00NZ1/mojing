package com.mojing.app.data.mapper

import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.domain.model.Encyclopedia

object EncyclopediaMapper {
    fun toDomain(entity: EncyclopediaEntity): Encyclopedia = Encyclopedia(
        id = entity.id,
        name = entity.name,
        description = entity.description,
        coverImagePath = entity.coverImagePath,
        isOfficial = entity.isOfficial,
        genreTags = entity.genreTags,
        worldPrompt = entity.worldPrompt,
        gameplayMode = entity.gameplayMode,
        antiCheatPrompt = entity.antiCheatPrompt,
        entryCount = entity.entryCount
    )
}
