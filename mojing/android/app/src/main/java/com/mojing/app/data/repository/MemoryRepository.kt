package com.mojing.app.data.repository

import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.dao.SessionEventNodeDao
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val memorySegmentDao: SessionMemorySegmentDao,
    private val eventNodeDao: SessionEventNodeDao
) {
    suspend fun getRecentSegments(sessionId: Long, branchId: String = "main"): List<SessionMemorySegmentEntity> =
        memorySegmentDao.getRecentForBranch(sessionId, branchId)
    suspend fun getSegments(sessionId: Long, branchId: String): List<SessionMemorySegmentEntity> =
        memorySegmentDao.getBySessionAndBranch(sessionId, branchId)
    suspend fun getEventNodes(sessionId: Long, branchId: String = "main"): List<SessionEventNodeEntity> =
        eventNodeDao.getForBranch(sessionId, branchId)
    suspend fun toggleEventResolved(id: Long) { eventNodeDao.toggleResolved(id) }
}
