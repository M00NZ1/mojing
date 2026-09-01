package com.mojing.app.data.mapper

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.domain.model.Character

object CharacterMapper {
    fun toDomain(entity: CharacterEntity): Character = Character(
        id = entity.id, name = entity.name, personaPrompt = entity.personaPrompt,
        apiKey = entity.apiKey, apiBaseUrl = entity.apiBaseUrl,
        modelName = entity.modelName, temperature = entity.temperature, maxTokens = entity.maxTokens
    )
}
