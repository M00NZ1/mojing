package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.StoryOpeningDraftStore
import com.mojing.app.data.UnreadableStoryDraft
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.ConfigEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.domain.story.StoryChapter
import com.mojing.app.domain.story.StoryOpeningDraft
import com.mojing.app.domain.story.StoryOpeningDraftCodec
import com.mojing.app.domain.story.StoryOpeningRecord
import com.mojing.app.domain.story.StoryWritingResult
import com.mojing.app.domain.usecase.SessionCreationTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StoryOpeningRecoveryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun pendingDraftSurvivesCloseAndReopenWithCompleteLongBody() = runBlocking {
        val name = "story_recovery_pending_${UUID.randomUUID()}.db"
        val draft = draft("10000000-0000-4000-8000-000000000001")
        var database: AppDatabase? = null
        try {
            val db = open(name)
            database = db
            val store = StoryOpeningDraftStore(db)
            store.persist(draft)
            db.close()
            val reopened = open(name)
            database = reopened

            val recovered = StoryOpeningDraftStore(reopened).load()
            assertEquals(StoryOpeningRecord.Pending(draft), recovered)
            assertTrue(draft.result.chapters.all { it.content.length > 4_000 })
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun secondChapterFailureRollsBackSessionKeepsPendingAndRetryIsIdempotent() = runBlocking {
        val name = "story_recovery_chapter_${UUID.randomUUID()}.db"
        val draft = draft("10000000-0000-4000-8000-000000000002")
        var database: AppDatabase? = null
        try {
            val db = open(name)
            database = db
            val store = StoryOpeningDraftStore(db)
            store.persist(draft)
            db.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_story_second_chapter
                BEFORE INSERT ON messages
                WHEN NEW.content = '${draft.result.chapters[1].content}'
                BEGIN SELECT RAISE(ABORT, 'injected second chapter failure'); END
                """.trimIndent(),
            )
            val transaction = SessionCreationTransaction(db)
            val messages = draft.result.chapters.map { chapter ->
                MessageEntity(sessionId = 999L, speakerType = "narrator", content = chapter.content)
            }
            val failed = runCatching {
                transaction(SessionEntity(title = draft.result.title), SessionWorldEntity(sessionId = 0L), emptyList(), messages, draft.id)
            }.exceptionOrNull()
            assertNotNull(failed)
            assertEquals(0, countRows(db, "sessions"))
            assertEquals(0, countRows(db, "session_worlds"))
            assertEquals(0, countRows(db, "messages"))
            assertEquals(StoryOpeningRecord.Pending(draft), store.load())

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_story_second_chapter")
            val sessionId = transaction(
                SessionEntity(title = draft.result.title),
                SessionWorldEntity(sessionId = 0L),
                emptyList(),
                messages,
                draft.id,
            )
            assertEquals(1, countRows(db, "sessions"))
            assertEquals(2, countRows(db, "messages"))
            assertEquals(StoryOpeningRecord.Saved(draft.id, sessionId, draft.result.title), store.load())

            val repeatedId = transaction(
                SessionEntity(title = "不应重复"),
                SessionWorldEntity(sessionId = 0L),
                emptyList(),
                messages,
                draft.id,
            )
            assertEquals(sessionId, repeatedId)
            assertEquals(1, countRows(db, "sessions"))
            assertEquals(2, countRows(db, "messages"))

            assertNotNull(runCatching { store.discard(draft.id) }.exceptionOrNull())
            store.clearSavedReceipt(draft.id)
            assertEquals(null, store.load())
            val failedAfterReceiptClear = runCatching {
                transaction(
                    SessionEntity(title = "回执已清除，不应创建"),
                    SessionWorldEntity(sessionId = 0L),
                    emptyList(), messages, draft.id,
                )
            }.exceptionOrNull()
            assertNotNull(failedAfterReceiptClear)
            assertEquals(1, countRows(db, "sessions"))
            assertEquals(2, countRows(db, "messages"))
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun receiptInsertOrUpdateFailureRollsBackWholeTransactionAndKeepsPending() = runBlocking {
        val name = "story_recovery_receipt_${UUID.randomUUID()}.db"
        val draft = draft("10000000-0000-4000-8000-000000000003")
        var database: AppDatabase? = null
        try {
            val db = open(name)
            database = db
            StoryOpeningDraftStore(db).persist(draft)
            db.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER reject_story_receipt_insert
                BEFORE INSERT ON app_config
                WHEN NEW.`key` = '${StoryOpeningDraftCodec.KEY}'
                BEGIN SELECT RAISE(ABORT, 'injected receipt insert failure'); END
                """.trimIndent(),
            )
            db.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER reject_story_receipt_update
                BEFORE UPDATE ON app_config
                WHEN NEW.`key` = '${StoryOpeningDraftCodec.KEY}'
                BEGIN SELECT RAISE(ABORT, 'injected receipt update failure'); END
                """.trimIndent(),
            )
            val failed = runCatching {
                SessionCreationTransaction(db)(
                    SessionEntity(title = draft.result.title),
                    SessionWorldEntity(sessionId = 0L),
                    emptyList(),
                    draft.result.chapters.map { MessageEntity(sessionId = 0L, speakerType = "narrator", content = it.content) },
                    draft.id,
                )
            }.exceptionOrNull()
            assertNotNull(failed)
            assertEquals(0, countRows(db, "sessions"))
            assertEquals(0, countRows(db, "session_worlds"))
            assertEquals(0, countRows(db, "messages"))
            assertEquals(StoryOpeningRecord.Pending(draft), StoryOpeningDraftStore(db).load())
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun anotherUuidCannotOverwriteOrDeletePendingDraft() = runBlocking {
        val name = "story_recovery_cas_${UUID.randomUUID()}.db"
        val draft = draft("10000000-0000-4000-8000-000000000004")
        val other = draft("10000000-0000-4000-8000-000000000005")
        var database: AppDatabase? = null
        try {
            val db = open(name)
            database = db
            val store = StoryOpeningDraftStore(db)
            store.persist(draft)
            assertNotNull(runCatching { store.persist(other) }.exceptionOrNull())
            assertNotNull(runCatching { store.discard(other.id) }.exceptionOrNull())
            assertEquals(StoryOpeningRecord.Pending(draft), store.load())
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun unreadableDraftIsPreservedAndCasDiscardCannotDeleteLaterData() = runBlocking {
        val name = "story_recovery_corrupt_${UUID.randomUUID()}.db"
        val corrupt = "{\"version\":999,\"id\":\"10000000-0000-4000-8000-000000000006\"}"
        val later = StoryOpeningDraftCodec.encode(StoryOpeningRecord.Saved("10000000-0000-4000-8000-000000000007", 7L, "后来保存"))
        var database: AppDatabase? = null
        try {
            val db = open(name)
            database = db
            db.configDao().set(ConfigEntity(StoryOpeningDraftCodec.KEY, corrupt))
            val failure = runCatching { StoryOpeningDraftStore(db).load() }.exceptionOrNull()
            assertTrue(failure is UnreadableStoryDraft)
            assertEquals(corrupt, (failure as UnreadableStoryDraft).raw)
            db.configDao().set(ConfigEntity(StoryOpeningDraftCodec.KEY, later))
            assertNotNull(runCatching { StoryOpeningDraftStore(db).discardUnreadable(corrupt) }.exceptionOrNull())
            assertEquals(later, db.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson)
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deletedSavedSessionCannotBeReportedAsSuccessfulRecovery() = runBlocking {
        val name = "story_recovery_deleted_${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            val draft = draft("10000000-0000-4000-8000-000000000009")
            val store = StoryOpeningDraftStore(db)
            store.persist(draft)
            val transaction = SessionCreationTransaction(db)
            val messages = listOf(MessageEntity(sessionId = 0L, speakerType = "narrator", content = "完整正文"))
            val id = transaction(SessionEntity(title = "已保存"), SessionWorldEntity(sessionId = 0L), emptyList(), messages, draft.id)
            db.openHelper.writableDatabase.execSQL("DELETE FROM sessions WHERE id = ?", arrayOf(id))
            val receipt = store.load() as StoryOpeningRecord.Saved
            assertEquals(false, receipt.sessionExists)
            val countBefore = countRows(db, "messages")
            assertNotNull(runCatching {
                transaction(SessionEntity(title = "不应重建"), SessionWorldEntity(sessionId = 0L), emptyList(), messages, draft.id)
            }.exceptionOrNull())
            assertEquals(0, countRows(db, "sessions"))
            assertEquals(countBefore, countRows(db, "messages"))
            assertEquals(receipt, store.load())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun open(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .allowMainThreadQueries()
        .build()

    private fun countRows(database: AppDatabase, table: String): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun draft(id: String): StoryOpeningDraft {
        val longBody = "长篇正文。".repeat(1_100)
        return StoryOpeningDraft(
            id = id,
            premise = "边城在暴雨夜迎来不速之客",
            direction = "调查旧塔",
            tone = "克制悬疑",
            template = null,
            encyclopediaId = null,
            characterIds = emptyList(),
            worldPrompt = "旧塔与潮汐",
            result = StoryWritingResult(
                title = "雨夜旧塔",
                chapters = listOf(
                    StoryChapter(1, "第一章", longBody + "第一章收束。"),
                    StoryChapter(2, "第二章", longBody + "第二章收束。"),
                ),
                nextChoices = listOf("进入旧塔", "追踪潮声"),
            ),
            model = "offline-test",
        )
    }
}
