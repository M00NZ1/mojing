package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.*
import com.mojing.app.domain.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated Room database; never accesses production user rows or config. */
class SummaryProvenanceInstrumentedTest {
    @Test fun productionCommitRecordsSourceAndUserEditRevokesIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "来源记录"))
            val message = db.messageDao().insert(MessageEntity(sessionId = session, content = "正式原文"))
            val snapshot = MemoryCompactionSnapshot(session, "main", emptyList(), 0, listOf(db.messageDao().getById(message)!!), 1)
            val segment = SessionMemorySegmentEntity(sessionId = session, startMessageId = message, endMessageId = message, summary = "自动摘要")
            assertTrue(MemoryCompactionStore(db).commit(snapshot, segment))
            val persisted = db.sessionMemorySegmentDao().getBySessionAndBranch(session, "main").single()
            assertEquals(SummaryProvenance.fingerprint(persisted), db.configDao().get(SummaryProvenance.key(persisted))!!.valueJson)
            assertEquals(SummaryMaintenanceResult.Updated,
                SummaryMaintenanceUseCase(db).edit(session, "main", persisted.id, persisted.summary, "人工校正"))
            assertNull(db.configDao().get(SummaryProvenance.key(persisted)))
            assertEquals("人工校正", db.sessionMemorySegmentDao().getById(persisted.id)!!.summary)
            assertEquals("正式原文", db.messageDao().getById(message)!!.content)
        } finally { db.close() }
    }

    @Test fun metadataWriteFailureRollsBackSegmentAndKeepsOriginalAndCheckpoint() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "来源失败"))
            val message = db.messageDao().insert(MessageEntity(sessionId = session, content = "原文保留"))
            val checkpoint = "memory_compaction_checkpoint_v1:$session:main"
            db.configDao().set(ConfigEntity(checkpoint, "恢复点"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_source BEFORE INSERT ON app_config " +
                "WHEN NEW.`key` LIKE 'memory_summary_source_v1:%' BEGIN SELECT RAISE(ABORT, 'forced'); END")
            val snapshot = MemoryCompactionSnapshot(session, "main", emptyList(), 0, listOf(db.messageDao().getById(message)!!), 1)
            val segment = SessionMemorySegmentEntity(sessionId = session, startMessageId = message, endMessageId = message, summary = "不应半提交")
            assertTrue(runCatching { MemoryCompactionStore(db).commit(snapshot, segment) }.isFailure)
            assertTrue(db.sessionMemorySegmentDao().getBySessionAndBranch(session, "main").isEmpty())
            assertEquals("恢复点", db.configDao().get(checkpoint)!!.valueJson)
            assertEquals("原文保留", db.messageDao().getById(message)!!.content)
        } finally { db.close() }
    }

    @Test fun failedManualEditKeepsSourceAndLegacyRowsAreNotBackfilled() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "旧摘要"))
            val message = db.messageDao().insert(MessageEntity(sessionId = session, content = "原文"))
            val id = db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId = session,
                startMessageId = message, endMessageId = message, summary = "未知来源"))
            val segment = db.sessionMemorySegmentDao().getById(id)!!
            assertNull(db.configDao().get(SummaryProvenance.key(segment)))
            db.configDao().set(ConfigEntity(SummaryProvenance.key(segment), SummaryProvenance.fingerprint(segment)))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_source_delete BEFORE DELETE ON app_config " +
                "WHEN OLD.`key` LIKE 'memory_summary_source_v1:%' BEGIN SELECT RAISE(ABORT, 'forced'); END")
            assertTrue(runCatching { SummaryMaintenanceUseCase(db).edit(session, "main", id, "未知来源", "用户修改") }.isFailure)
            assertEquals("未知来源", db.sessionMemorySegmentDao().getById(id)!!.summary)
            assertEquals(SummaryProvenance.fingerprint(segment), db.configDao().get(SummaryProvenance.key(segment))!!.valueJson)
        } finally { db.close() }
    }
}
