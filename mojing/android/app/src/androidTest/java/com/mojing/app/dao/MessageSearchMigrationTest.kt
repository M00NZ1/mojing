package com.mojing.app.dao

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageSearchMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration14To15PreservesMessagesAndRebuildsDerivedIndex() = runBlocking {
        val databaseName = "message-search-migration"
        helper.createDatabase(databaseName, 14).apply {
            execSQL(
                """
                INSERT INTO sessions
                    (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit)
                VALUES (1, '旧会话', '', 1, 1, 0, 0, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages
                    (id, sessionId, speakerType, characterId, branchId, parentMessageId,
                     regeneratedFromMessageId, swipeGroupId, content, structuredContentJson,
                     includeInContext, createdAt)
                VALUES (7, 1, 'user', NULL, 'main', NULL, NULL, NULL,
                        '迁移前的星门记录', '{}', 1, 2)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            15,
            true,
            Migrations.MIGRATION_14_15,
        ).apply {
            query("SELECT content, searchNormalized, searchTerms FROM messages WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移前的星门记录", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals("", cursor.getString(2))
            }
            query("SELECT indexVersion, indexedThroughMessageId, isComplete FROM message_search_index_state").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals(0, cursor.getInt(2))
            }
            query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_message_search_fts_%'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
            close()
        }

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            databaseName,
        ).addMigrations(
            Migrations.MIGRATION_14_15,
            Migrations.MIGRATION_15_16,
            Migrations.MIGRATION_16_17,
            Migrations.MIGRATION_17_18,
        ).build()
        try {
            val dao = db.messageDao()
            assertEquals(1, dao.searchMainMessages(1L, "星门", 0, 10).size)

            val rebuilt = dao.rebuildSearchIndexBatch(batchSize = 10, now = 3L)
            assertTrue(rebuilt.isComplete)
            assertEquals(1, rebuilt.indexedCount)
            assertEquals(1, dao.searchMainMessages(1L, "星门", 0, 10).size)
            assertFalse(dao.getById(7L)?.searchTerms.isNullOrBlank())
            assertEquals("迁移前的星门记录", dao.getById(7L)?.content)
        } finally {
            db.close()
        }
    }
}
