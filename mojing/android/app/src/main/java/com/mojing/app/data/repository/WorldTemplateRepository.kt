package com.mojing.app.data.repository

import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.dao.WorldLoreEntryDao
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorldTemplateRepository @Inject constructor(
    private val templateDao: WorldTemplateDao,
    private val loreEntryDao: WorldLoreEntryDao
) {
    suspend fun getAll(): List<WorldTemplateEntity> = templateDao.getAll()
    suspend fun getById(id: Long): WorldTemplateEntity? = templateDao.getById(id)
    suspend fun upsert(entity: WorldTemplateEntity): Long = templateDao.upsert(entity)
    suspend fun delete(id: Long) { templateDao.delete(id) }
    suspend fun getLoreEntries(templateId: Long): List<WorldLoreEntryEntity> = loreEntryDao.getByTemplate(templateId)
    suspend fun upsertLore(entity: WorldLoreEntryEntity): Long = loreEntryDao.upsert(entity)
}
