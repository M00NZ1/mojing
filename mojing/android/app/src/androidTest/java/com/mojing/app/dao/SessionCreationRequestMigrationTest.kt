package com.mojing.app.dao

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.Migrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionCreationRequestMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration24To25KeepsOldSessionsAndAddsUniqueRequestIndex() {
        val name = "session-creation-request-migration"
        helper.createDatabase(name, 24).apply {
            execSQL(
                """INSERT INTO sessions
                    (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit)
                    VALUES (10, '旧故事', '原摘要', 1, 2, 0, 0, 1000000)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 25, true, Migrations.MIGRATION_24_25).use { db ->
            db.query("SELECT title, summary, creationRequestId FROM sessions WHERE id = 10").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("旧故事", row.getString(0))
                assertEquals("原摘要", row.getString(1))
                assertEquals(true, row.isNull(2))
            }
            db.execSQL("""INSERT INTO sessions
                (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit, creationRequestId)
                VALUES (11, '新故事', '', 3, 3, 0, 0, 1000000, 'one-request')""".trimIndent())
            assertEquals(true, runCatching {
                db.execSQL("""INSERT INTO sessions
                    (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit, creationRequestId)
                    VALUES (12, '重复故事', '', 4, 4, 0, 0, 1000000, 'one-request')""".trimIndent())
            }.isFailure)
        }
    }
}
