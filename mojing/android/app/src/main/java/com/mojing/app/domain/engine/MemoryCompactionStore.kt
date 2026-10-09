package com.mojing.app.domain.engine

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.local.entity.ConfigEntity
import com.google.gson.Gson
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
    val checkpoint: MemoryCompactionCheckpoint?,
    val cursorAfterMessageId: Long? = null,
    val nextCoveredSegment: SessionMemorySegmentEntity? = null,
    val contextSegments: List<SessionMemorySegmentEntity>? = null,
) {
    constructor(sessionId: Long, branchId: String, previous: List<SessionMemorySegmentEntity>,
        revision: Long, sources: List<MessageEntity>, limit: Int) :
        this(sessionId, branchId, previous, revision, sources, limit, null)

    val afterMessageId: Long get() = cursorAfterMessageId ?: previous.maxOfOrNull { it.endMessageId } ?: 0L
}

internal data class MemoryCoveragePage(val throughMessageId: Long, val gapBefore: SessionMemorySegmentEntity? = null)

internal suspend fun scanMemoryCoveragePage(
    afterMessageId: Long,
    segments: List<SessionMemorySegmentEntity>,
    firstStoryAfter: suspend (Long) -> Long?,
): MemoryCoveragePage {
    var through = afterMessageId
    for (segment in segments) {
        currentCoroutineContext().ensureActive()
        if (segment.endMessageId <= through) continue
        if (segment.startMessageId > through) {
            val first = firstStoryAfter(through)
            if (first != null && first < segment.startMessageId) return MemoryCoveragePage(through, segment)
        }
        through = maxOf(through, segment.endMessageId)
    }
    return MemoryCoveragePage(through)
}

/** 模型等待在事务之外；读取快照和校验提交分别使用同一本机数据库的短事务。 */
class MemoryCompactionStore @Inject constructor(private val database: AppDatabase) {
    private val gson = Gson()
    private data class GapBoundary(
        val revision: Long,
        val afterMessageId: Long,
        val nextSegment: SessionMemorySegmentEntity,
    )

    private data class GapScanCursor(
        val version: Int = 1,
        val revision: Long,
        val coveredThroughMessageId: Long,
    )

    private sealed interface GapScanPage {
        data class Found(val boundary: GapBoundary) : GapScanPage
        data object Continue : GapScanPage
        data object Done : GapScanPage
    }

    /** The manual path scans only segment metadata and visible message IDs; the saved cursor avoids rereading old coverage. */
    private suspend fun findHistoricalGap(sessionId: Long, branchId: String): GapBoundary? {
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = database.withTransaction {
                checkNotNull(database.sessionDao().getById(sessionId)) { "会话已删除，停止记忆整理" }
                val revision = database.messageDao().getContextMemoryForInvalidation(sessionId, branchId)?.revision ?: 0L
                val key = gapScanKey(sessionId, branchId)
                val saved = database.configDao().get(key)?.valueJson?.let { raw ->
                    runCatching { gson.fromJson(raw, GapScanCursor::class.java) }.getOrNull()
                }
                val after = saved?.coveredThroughMessageId?.takeIf {
                    saved.version == 1 && saved.revision == revision && it >= 0L
                } ?: 0L
                val segments = database.sessionMemorySegmentDao().getCoverageAfter(sessionId, branchId, after, 64)
                val coverage = scanMemoryCoveragePage(after, segments) { cursor ->
                    database.messageDao().getFirstStoryContextIdAfter(sessionId, branchId, cursor)
                }
                coverage.gapBefore?.let { return@withTransaction GapScanPage.Found(GapBoundary(revision, coverage.throughMessageId, it)) }
                if (coverage.throughMessageId != after || saved?.revision != revision) {
                    database.configDao().set(ConfigEntity(key, gson.toJson(GapScanCursor(revision = revision, coveredThroughMessageId = coverage.throughMessageId))))
                }
                if (segments.size == 64) GapScanPage.Continue else GapScanPage.Done
            }
            when (page) {
                is GapScanPage.Found -> return page.boundary
                GapScanPage.Done -> return null
                GapScanPage.Continue -> Unit
            }
        }
    }

    suspend fun read(sessionId: Long, branchId: String, limit: Int, scanHistoricalGaps: Boolean = false): MemoryCompactionSnapshot {
        require(limit in 1..2000)
        val gap = if (scanHistoricalGaps) findHistoricalGap(sessionId, branchId) else null
        return database.withTransaction {
            val previous = database.sessionMemorySegmentDao().getRecentForBranch(sessionId, branchId, 3)
            val revision = database.messageDao().getContextMemoryForInvalidation(sessionId, branchId)?.revision ?: 0L
            val boundary = gap?.takeIf { it.revision == revision &&
                database.sessionMemorySegmentDao().getCoverageAfter(sessionId, branchId, it.afterMessageId, 1).firstOrNull() == it.nextSegment }
            val historicalSources = boundary?.let {
                database.messageDao().getStoryContextBetween(sessionId, branchId, it.afterMessageId, it.nextSegment.startMessageId, limit)
            }
            val isGap = !historicalSources.isNullOrEmpty()
            val checkpoint = database.configDao().get(checkpointKey(sessionId, branchId, isGap))?.valueJson?.let { raw ->
                runCatching {
                    gson.fromJson(raw, MemoryCompactionCheckpoint::class.java)?.takeIf {
                        it.version == 1 && it.nextChunkIndex > 0 && it.carriedSummary.isNotBlank() &&
                            it.sourceFingerprint.matches(Regex("[0-9a-f]{64}")) &&
                            MemoryCompactionInput.weight(it.carriedSummary) <= MemoryCompactionInput.MEMORY_BUDGET
                    }
                }.getOrNull()
            }
            MemoryCompactionSnapshot(
                sessionId, branchId, previous, revision,
                historicalSources?.takeIf { isGap } ?: database.messageDao().getNextStoryContextBatch(
                    sessionId, branchId, previous.maxOfOrNull { it.endMessageId } ?: 0L, limit),
                if (isGap) checkNotNull(historicalSources).size else limit,
                checkpoint,
                cursorAfterMessageId = boundary?.afterMessageId?.takeIf { isGap },
                nextCoveredSegment = boundary?.nextSegment?.takeIf { isGap },
                contextSegments = boundary?.let {
                    if (isGap) database.sessionMemorySegmentDao().getRecentBefore(sessionId, branchId, it.afterMessageId, 3) else null
                },
            )
        }
    }

    suspend fun saveCheckpoint(snapshot: MemoryCompactionSnapshot, checkpoint: MemoryCompactionCheckpoint) {
        require(checkpoint.version == 1 && checkpoint.nextChunkIndex > 0 && checkpoint.carriedSummary.isNotBlank() &&
            checkpoint.sourceFingerprint == snapshot.sourceFingerprint() &&
            MemoryCompactionInput.weight(checkpoint.carriedSummary) <= MemoryCompactionInput.MEMORY_BUDGET)
        database.withTransaction {
            checkNotNull(database.sessionDao().getById(snapshot.sessionId)) { "会话已删除，停止记忆整理" }
            database.configDao().set(ConfigEntity(checkpointKey(snapshot.sessionId, snapshot.branchId, snapshot.nextCoveredSegment != null), gson.toJson(checkpoint)))
        }
    }

    suspend fun commit(snapshot: MemoryCompactionSnapshot, segment: SessionMemorySegmentEntity): Boolean = database.withTransaction {
        val committed = commitValidated(snapshot, segment, database.messageDao(), database.sessionMemorySegmentDao(), database.configDao())
        if (committed) database.configDao().delete(checkpointKey(snapshot.sessionId, snapshot.branchId, snapshot.nextCoveredSegment != null))
        committed
    }

    private fun checkpointKey(sessionId: Long, branchId: String, historicalGap: Boolean) =
        "${if (historicalGap) "memory_compaction_gap_checkpoint_v1" else "memory_compaction_checkpoint_v1"}:$sessionId:$branchId"

    private fun gapScanKey(sessionId: Long, branchId: String) = "memory_gap_scan_v1:$sessionId:$branchId"

    internal companion object {
        // 仅供上面的事务入口调用；分离以对真实查询边界和拒绝条件做 JVM 回归。
        suspend fun commitValidated(snapshot: MemoryCompactionSnapshot, segment: SessionMemorySegmentEntity, messages: MessageDao, segments: SessionMemorySegmentDao,
            config: com.mojing.app.data.local.dao.ConfigDao? = null): Boolean {
            val (sessionId, branchId, previous, revision, sources, limit) = snapshot
            require(limit in 1..2000 && sources.size == limit && sources.all { it.sessionId == sessionId && it.id > snapshot.afterMessageId })
            require(segment.id == 0L && segment.sessionId == sessionId && segment.branchId == branchId && segment.summary.isNotBlank())
            require(segment.startMessageId == sources.first().id && segment.endMessageId == sources.last().id)
            if ((messages.getContextMemoryForInvalidation(sessionId, branchId)?.revision ?: 0L) != revision) return false
            if (segments.getRecentForBranch(sessionId, branchId, 3) != previous) return false
            val boundary = snapshot.nextCoveredSegment
            if (boundary != null) {
                if (sources.last().id >= boundary.startMessageId) return false
                if (segments.getCoverageAfter(sessionId, branchId, snapshot.afterMessageId, 1).firstOrNull() != boundary) return false
                if (segments.getRecentBefore(sessionId, branchId, snapshot.afterMessageId, 3) != snapshot.contextSegments) return false
            }
            val current = if (boundary == null) messages.getNextStoryContextBatch(sessionId, branchId, snapshot.afterMessageId, limit)
                else messages.getStoryContextBetween(sessionId, branchId, snapshot.afterMessageId, boundary.startMessageId, limit)
            fun versions(rows: List<MessageEntity>) = rows.map { listOf(it.id, it.content, it.structuredContentJson, it.speakerType, it.characterId, it.branchId) }
            if (versions(current) != versions(sources)) return false
            currentCoroutineContext().ensureActive()
            val inserted = segment.copy(segmentIndex = segments.nextSegmentIndex(sessionId, branchId))
            val id = segments.insertCompacted(inserted)
            if (config != null) {
                val persisted = inserted.copy(id = id)
                config.set(ConfigEntity(SummaryProvenance.key(persisted), SummaryProvenance.fingerprint(persisted)))
            }
            return true
        }
    }
}
