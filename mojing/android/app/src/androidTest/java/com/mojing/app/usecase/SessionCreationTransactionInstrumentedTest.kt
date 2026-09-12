package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.usecase.SessionCreationTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionCreationTransactionInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var createPackage: SessionCreationTransaction

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        createPackage = SessionCreationTransaction(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun participantFailureRollsBackSessionWorldAndEarlierParticipants() = runBlocking {
        database.characterDao().upsert(CharacterEntity(id = 1L, name = "甲"))
        database.characterDao().upsert(CharacterEntity(id = 2L, name = "乙"))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_second_participant
            BEFORE INSERT ON session_participants
            WHEN NEW.characterId = 2
            BEGIN
                SELECT RAISE(ABORT, 'injected participant failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching {
            createPackage(
                SessionEntity(title = "不应留下"),
                SessionWorldEntity(sessionId = 0L, worldPrompt = "不应留下"),
                listOf(
                    SessionParticipantEntity(sessionId = 0L, characterId = 1L, sortOrder = 0),
                    SessionParticipantEntity(sessionId = 0L, characterId = 2L, sortOrder = 1),
                ),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(emptyList<SessionEntity>(), database.sessionDao().observeAll().first())
        assertEquals(0, countRows("session_worlds"))
        assertEquals(0, countRows("session_participants"))
    }

    @Test
    fun successfulPackageCreatesOneCompleteSession() = runBlocking {
        database.characterDao().upsert(CharacterEntity(id = 1L, name = "甲"))
        database.characterDao().upsert(CharacterEntity(id = 2L, name = "乙"))

        val sessionId = createPackage(
            SessionEntity(title = "完整故事"),
            SessionWorldEntity(sessionId = 0L, templateId = "wuxia", worldPrompt = "门派林立"),
            listOf(
                SessionParticipantEntity(sessionId = 0L, characterId = 2L, sortOrder = 0),
                SessionParticipantEntity(sessionId = 0L, characterId = 1L, sortOrder = 1),
            ),
        )

        assertEquals("完整故事", database.sessionDao().getById(sessionId)?.title)
        assertEquals("门派林立", database.sessionWorldDao().getBySession(sessionId)?.worldPrompt)
        assertEquals(
            listOf(2L, 1L),
            database.participantDao().getBySession(sessionId).map { it.characterId },
        )
    }

    private fun countRows(table: String): Int {
        return database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
    }

    @Test
    fun failedSecondChapterRollsBackEntireStoryAndRetryWritesOnePackage() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_second_chapter BEFORE INSERT ON messages
            WHEN NEW.content = '第二章正文' BEGIN SELECT RAISE(ABORT, 'injected write failure'); END
        """.trimIndent())
        val messages = listOf(
            MessageEntity(sessionId = 999, speakerType = "user", content = "故事背景"),
            MessageEntity(sessionId = 999, speakerType = "narrator", content = "第一章正文"),
            MessageEntity(sessionId = 999, speakerType = "narrator", content = "第二章正文"),
        )
        suspend fun write() = createPackage(SessionEntity(title = "完整小说"),
            SessionWorldEntity(sessionId = 0L), emptyList(), messages)
        assertNotNull(runCatching { write() }.exceptionOrNull())
        assertEquals(0, countRows("sessions"))
        assertEquals(0, countRows("session_worlds"))
        assertEquals(0, countRows("messages"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_chapter")
        val id = write()
        assertEquals(1, countRows("sessions"))
        assertEquals(1, countRows("session_worlds"))
        val saved = database.messageDao().getNextStoryContextBatch(id, "main", 0L, 10)
        assertEquals(messages.map { it.content }, saved.map { it.content })
    }
}
