package com.mojing.app.data.mapper

import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.domain.model.Session

object SessionMapper {
    fun toDomain(entity: SessionEntity): Session = Session(
        id = entity.id, title = entity.title, summary = entity.summary,
        createdAt = entity.createdAt, updatedAt = entity.updatedAt
    )
}
