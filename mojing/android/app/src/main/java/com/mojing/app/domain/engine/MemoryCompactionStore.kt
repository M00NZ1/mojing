package com.mojing.app.domain.engine

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class MemoryCompactionSnapshot(
    val sessionId: Long,
    val branchId: String,
    val previous: List<SessionMemorySegmentEntity>,
    val revision: Long,
    val sources: List<MessageEntity>,
    val limit: Int,
) {
    val afterMessageId: Long get() = previous.maxOfOrNull { it.endMessageId } ?: 0L
}

/** 模型等待在事务之外；读取快照和校验提交分别使用同一本机数据库的短事务。 */
class MemoryCompactionStore @Inject constructor(private val database: AppDatabase) {
    suspend fun read(sessionId: Long, branchId: String, limit: Int): MemoryCompactionSnapshot = database.withTransaction {
        require(limit in 1..2000)
        val previous = database.sessionMemorySegmentDao().getRecentForBranch(sessionId, branchId, 3)
        MemoryCompactionSnapshot(
            sessionId, branchId, previous,
            database.messageDao().getContextMemoryForInvalidation(sessionId, branchId)?.revision ?: 0L,
            database.messageDao().getNextStoryContextBatch(sessionId, branchId, previous.maxOfOrNull { it.endMessageId } ?: 0L, limit),
            limit,
        )
    }

    suspend fun commit(snapshot: MemoryCompactionSnapshot, segment: SessionMemorySegmentEntity): Boolean = database.withTransaction {
        commitValidated(snapshot, segment, database.messageDao(), database.sessionMemorySegmentDao())
    }

    internal companion object {
        // 仅供上面的事务入口调用；分离以对真实查询边界和拒绝条件做 JVM 回归。
        suspend fun commitValidated(snapshot: MemoryCompactionSnapshot, segment: SessionMemorySegmentEntity, messages: MessageDao, segments: SessionMemorySegmentDao): Boolean {
            val (sessionId, branchId, previous, revision, sources, limit) = snapshot
            require(limit in 1..2000 && sources.size == limit && sources.all { it.sessionId == sessionId && it.id > snapshot.afterMessageId })
            require(segment.id == 0L && segment.sessionId == sessionId && segment.branchId == branchId && segment.summary.isNotBlank())
            require(segment.startMessageId == sources.first().id && segment.endMessageId == sources.last().id)
            if ((messages.getContextMemoryForInvalidation(sessionId, branchId)?.revision ?: 0L) != revision) return false
            if (segments.getRecentForBranch(sessionId, branchId, 3) != previous) return false
            val current = messages.getNextStoryContextBatch(sessionId, branchId, snapshot.afterMessageId, limit)
            fun versions(rows: List<MessageEntity>) = rows.map { listOf(it.id, it.content, it.structuredContentJson, it.speakerType, it.characterId, it.branchId) }
            if (versions(current) != versions(sources)) return false
            currentCoroutineContext().ensureActive()
            segments.insertCompacted(segment.copy(segmentIndex = segments.nextSegmentIndex(sessionId, branchId)))
            return true
        }
    }
}
