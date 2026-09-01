package com.mojing.app.data.mapper

import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.model.WorldTemplate

object WorldTemplateMapper {
    fun toDomain(entity: WorldTemplateEntity): WorldTemplate = WorldTemplate(
        id = entity.id,
        templateId = entity.templateId,
        label = entity.label,
        category = entity.category,
        summary = entity.summary,
        gameplayMode = entity.gameplayMode,
        worldPrompt = entity.worldPrompt,
        coverImagePath = entity.coverImagePath,
        suggestedChoicesJson = entity.suggestedChoicesJson,
        antiCheatPrompt = entity.antiCheatPrompt,
        isBuiltin = entity.isBuiltin
    )
}
