package com.mojing.app.domain.engine

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import javax.inject.Inject

sealed interface SummaryMaintenanceResult {
    data object Updated : SummaryMaintenanceResult
    data object Deleted : SummaryMaintenanceResult
    data object Conflict : SummaryMaintenanceResult
    data object NotOwned : SummaryMaintenanceResult
}

/** Short Room transaction for user-owned summary maintenance and derived-state invalidation. */
class SummaryMaintenanceUseCase @Inject constructor(private val database: AppDatabase) {
    suspend fun edit(
        sessionId: Long,
        branchId: String,
        segmentId: Long,
        expectedSummary: String,
        replacement: String,
    ): SummaryMaintenanceResult = database.withTransaction {
        if (replacement.isBlank()) return@withTransaction SummaryMaintenanceResult.Conflict
        val segment = database.sessionMemorySegmentDao().getById(segmentId)
            ?: return@withTransaction SummaryMaintenanceResult.Conflict
        if (segment.sessionId != sessionId || segment.branchId != branchId) {
            return@withTransaction SummaryMaintenanceResult.NotOwned
        }
        if (segment.summary != expectedSummary) return@withTransaction SummaryMaintenanceResult.Conflict
        val affected = affectedBranches(sessionId, segment)
        if (database.sessionMemorySegmentDao().updateSummaryIfUnchanged(
                segmentId, sessionId, branchId, expectedSummary, replacement.trim(),
            ) != 1
        ) return@withTransaction SummaryMaintenanceResult.Conflict
        database.configDao().delete(SummaryProvenance.key(segment))
        // The edited summary is the last trustworthy boundary. Any later derived
        // summaries must be rebuilt from the original messages.
        database.sessionMemorySegmentDao().deleteFollowing(sessionId, branchId, segment.endMessageId)
        invalidate(sessionId, segment, affected)
        SummaryMaintenanceResult.Updated
    }

    suspend fun delete(
        sessionId: Long,
        branchId: String,
        segmentId: Long,
        expectedSummary: String,
    ): SummaryMaintenanceResult = database.withTransaction {
        val segment = database.sessionMemorySegmentDao().getById(segmentId)
            ?: return@withTransaction SummaryMaintenanceResult.Conflict
        if (segment.sessionId != sessionId || segment.branchId != branchId) {
            return@withTransaction SummaryMaintenanceResult.NotOwned
        }
        if (segment.summary != expectedSummary) return@withTransaction SummaryMaintenanceResult.Conflict
        val affected = affectedBranches(sessionId, segment)
        val dao = database.sessionMemorySegmentDao()
        if (dao.deleteOwnedIfUnchanged(segmentId, sessionId, branchId, expectedSummary) != 1) {
            return@withTransaction SummaryMaintenanceResult.Conflict
        }
        database.configDao().delete(SummaryProvenance.key(segment))
        dao.deleteFollowing(sessionId, branchId, segment.endMessageId)
        invalidate(sessionId, segment, affected)
        SummaryMaintenanceResult.Deleted
    }

    private suspend fun affectedBranches(sessionId: Long, segment: SessionMemorySegmentEntity): List<String> =
        (listOf(segment.branchId) + database.messageDao().getStorylinesSeeingSourceMessage(
            sessionId, segment.branchId, segment.endMessageId,
        )).filter(String::isNotBlank).distinct().filter { branch ->
            branch == segment.branchId || database.sessionMemorySegmentDao().getVisibleById(sessionId, branch, segment.id) != null
        }

    private suspend fun invalidate(sessionId: Long, segment: SessionMemorySegmentEntity, affected: List<String>) {
        val messageDao = database.messageDao()
        val now = System.currentTimeMillis()
        affected.forEach { branchId ->
            database.sessionMemorySegmentDao().deleteFollowing(
                sessionId, branchId, if (branchId == segment.branchId) segment.endMessageId else segment.endMessageId - 1L,
            )
            messageDao.invalidateContextMemoryForBranch(sessionId, branchId, now)
            val config = database.configDao()
            config.delete("memory_compaction_checkpoint_v1:$sessionId:$branchId")
            config.delete("memory_compaction_gap_checkpoint_v1:$sessionId:$branchId")
            config.delete("memory_gap_scan_v1:$sessionId:$branchId")
        }
    }
}
