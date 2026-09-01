package com.mojing.app.dao

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.Migrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryCorrectionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migration16To17KeepsMessagesAndCreatesEmptyCorrectionTable() {
        val databaseName = "memory-correction-migration"
        helper.createDatabase(databaseName, 16).apply {
            execSQL("INSERT INTO sessions (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit) VALUES (1, '旧会话', '', 1, 1, 0, 0, 0)")
            execSQL("INSERT INTO messages (id, sessionId, speakerType, characterId, branchId, parentMessageId, regeneratedFromMessageId, swipeGroupId, content, structuredContentJson, includeInContext, createdAt, searchNormalized, searchTerms) VALUES (10, 1, 'user', NULL, 'main', NULL, NULL, NULL, '旧消息', '{}', 1, 2, '', '')")
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 17, true, Migrations.MIGRATION_16_17).use { db ->
            db.query("SELECT COUNT(*) FROM messages").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM session_memory_corrections").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
