package com.mojing.app.data.mapper

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.model.Message

object MessageMapper {
    fun toDomain(entity: MessageEntity): Message = Message(
        id = entity.id, sessionId = entity.sessionId, speakerType = entity.speakerType,
        characterId = entity.characterId, content = entity.content, createdAt = entity.createdAt
    )
}
