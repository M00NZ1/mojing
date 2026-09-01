package com.mojing.app.domain.engine

import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor(
    private val memorySegmentDao: SessionMemorySegmentDao
) {
    suspend fun getContextMemory(sessionId: Long, branchId: String = "main"): String {
        val segments = memorySegmentDao.getRecentForBranch(sessionId, branchId)
        if (segments.isEmpty()) return ""
        return segments.joinToString("\n") { "- ${it.summary}" }
    }

    suspend fun shouldCompact(messages: List<MessageEntity>, threshold: Int = 20): Boolean {
        return messages.size >= threshold
    }
}
