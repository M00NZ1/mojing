package com.mojing.app.data.repository

import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao
) {
    fun observeAll(): Flow<List<SessionEntity>> = sessionDao.observeAll()
    suspend fun getById(id: Long): SessionEntity? = sessionDao.getById(id)
    suspend fun insert(entity: SessionEntity): Long = sessionDao.insert(entity)
    suspend fun delete(id: Long) { sessionDao.delete(id) }
    suspend fun search(query: String): List<SessionEntity> = sessionDao.search(query)
}
