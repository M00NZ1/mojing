package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.ConfigEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.domain.engine.SummaryMaintenanceResult
import com.mojing.app.domain.engine.SummaryMaintenanceUseCase
import com.mojing.app.domain.engine.MemoryCompactionSnapshot
import com.mojing.app.domain.engine.MemoryCompactionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryMaintenanceInstrumentedTest {
    @Test fun oldCompactionSnapshotCannotCommitAfterOriginalMessageChanges() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val sessionId = db.sessionDao().insert(SessionEntity(title = "旧快照"))
            val messageId = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "旧正文"))
            val source = db.messageDao().getById(messageId)!!
            val snapshot = MemoryCompactionSnapshot(sessionId, "main", emptyList(), 0L, listOf(source), 1)
            db.messageDao().updateContent(messageId, "已改正文")
            val candidate = SessionMemorySegmentEntity(sessionId = sessionId, startMessageId = messageId, endMessageId = messageId, summary = "不应提交")
            assertFalse(MemoryCompactionStore(db).commit(snapshot, candidate))
            assertTrue(db.sessionMemorySegmentDao().getBySessionAndBranch(sessionId, "main").isEmpty())
        } finally { db.close() }
    }

    @Test fun triggerFailureRollsBackSummaryAndTail() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val sessionId = db.sessionDao().insert(SessionEntity(title = "回滚"))
            val end = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "原文"))
            val id = db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(
                sessionId = sessionId, startMessageId = end, endMessageId = end, summary = "原摘要",
            ))
            db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(
                sessionId = sessionId, startMessageId = end + 1, endMessageId = end + 1, summary = "后续",
            ))
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_summary AFTER UPDATE OF summary ON session_memory_segments " +
                    "WHEN NEW.summary = '触发失败' BEGIN SELECT RAISE(ABORT, 'forced'); END",
            )
            val useCase = SummaryMaintenanceUseCase(db)
            assertTrue(runCatching { useCase.edit(sessionId, "main", id, "原摘要", "触发失败") }.isFailure)
            assertEquals("原摘要", db.sessionMemorySegmentDao().getById(id)!!.summary)
            assertEquals(2, db.sessionMemorySegmentDao().getBySessionAndBranch(sessionId, "main").size)
        } finally { db.close() }
    }

    @Test fun editIsCasAndDeletesFollowingTailAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val sessionId = db.sessionDao().insert(SessionEntity(title = "摘要事务"))
            val end = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "原文"))
            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = sessionId, branchId = "child", sourceMessageId = end))
            val first = db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(
                sessionId = sessionId, startMessageId = end, endMessageId = end, summary = "旧摘要",
            ))
            db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(
                sessionId = sessionId, startMessageId = end + 1, endMessageId = end + 1, summary = "后续摘要",
            ))
            db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(
                sessionId = sessionId, branchId = "child", startMessageId = end, endMessageId = end, summary = "子线摘要",
            ))
            db.sessionContextMemoryDao().upsert(SessionContextMemoryEntity(sessionId = sessionId, branchId = "main", revision = 2))
            db.sessionContextMemoryDao().upsert(SessionContextMemoryEntity(sessionId = sessionId, branchId = "child", revision = 2))
            db.configDao().set(ConfigEntity("memory_compaction_checkpoint_v1:$sessionId:main", "stale"))
            val useCase = SummaryMaintenanceUseCase(db)

            assertEquals(SummaryMaintenanceResult.Conflict, useCase.edit(sessionId, "main", first, "旧摘要", "   "))
            assertEquals(SummaryMaintenanceResult.Updated, useCase.edit(sessionId, "main", first, "旧摘要", "新摘要"))
            assertEquals("新摘要", db.sessionMemorySegmentDao().getById(first)!!.summary)
            assertEquals(1, db.sessionMemorySegmentDao().getBySessionAndBranch(sessionId, "main").size)
            assertEquals(0, db.sessionMemorySegmentDao().getBySessionAndBranch(sessionId, "child").size)
            assertEquals(3, db.sessionContextMemoryDao().getBySessionAndBranch(sessionId, "main")!!.revision)
            assertEquals(3, db.sessionContextMemoryDao().getBySessionAndBranch(sessionId, "child")!!.revision)
            assertEquals(null, db.configDao().get("memory_compaction_checkpoint_v1:$sessionId:main"))
            assertEquals(SummaryMaintenanceResult.Conflict, useCase.delete(sessionId, "main", first, "旧摘要"))
            assertTrue(db.sessionMemorySegmentDao().getById(first) != null)
        } finally { db.close() }
    }

    @Test fun invisibleEarlyBranchAndChangedSwipeDoNotLoseTheirOwnSummaries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val sessionId = db.sessionDao().insert(SessionEntity(title = "分叉隔离"))
            val first = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "早期"))
            val end = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "采用结尾", swipeGroupId = "g"))
            val alternate = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "另一版本", swipeGroupId = "g"))
            val earlyBranch = "early"
            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = sessionId, branchId = earlyBranch, sourceMessageId = first))
            db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId = sessionId, endMessageId = end, summary = "主线摘要"))
            db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId = sessionId, branchId = earlyBranch, endMessageId = first, summary = "早分叉摘要"))
            val changedBranch = "changed"
            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = sessionId, branchId = changedBranch, sourceMessageId = end))
            val changedVariant = db.messageDao().insert(MessageEntity(
                sessionId = sessionId, branchId = changedBranch, regeneratedFromMessageId = end,
                swipeGroupId = "g", content = "替代版本正文",
            ))
            val changedSummary = db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(
                sessionId = sessionId, branchId = changedBranch, endMessageId = end, summary = "替代版本摘要",
            ))
            db.messageDao().selectSwipeVariantForBranch(sessionId, changedBranch, "g", changedVariant)
            val mainSummary = db.sessionMemorySegmentDao().getBySessionAndBranch(sessionId, "main").single()
            SummaryMaintenanceUseCase(db).edit(sessionId, "main", mainSummary.id, "主线摘要", "主线修正")
            assertEquals("早分叉摘要", db.sessionMemorySegmentDao().getById(
                db.sessionMemorySegmentDao().getBySessionAndBranch(sessionId, earlyBranch).single().id,
            )!!.summary)
            assertEquals("替代版本摘要", db.sessionMemorySegmentDao().getById(changedSummary)!!.summary)
        } finally { db.close() }
    }
}
