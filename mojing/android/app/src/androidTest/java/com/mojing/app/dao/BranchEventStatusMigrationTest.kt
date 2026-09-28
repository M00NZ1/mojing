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
class BranchEventStatusMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
    )

    @Test
    fun migration26To27KeepsOldEventsAndAddsEmptyBranchStatusTable() {
        val name = "branch-event-status-migration"
        helper.createDatabase(name, 26).apply {
            execSQL(
                """INSERT INTO sessions
                    (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit)
                    VALUES (10, '旧会话', '', 1, 1, 0, 0, 1000000)
                """.trimIndent(),
            )
            execSQL(
                """INSERT INTO session_event_nodes
                    (id, sessionId, characterId, branchId, parentEventId, eventType, title, description,
                     importance, messageId, resolved, createdAt)
                    VALUES (7, 10, NULL, 'main', NULL, 'action', '旧事件', '仍需跟进', 3, NULL, 0, 123)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 27, true, Migrations.MIGRATION_26_27).use { db ->
            db.query("SELECT title, resolved FROM session_event_nodes WHERE id = 7").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("旧事件", row.getString(0))
                assertEquals(0, row.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM branch_event_status").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
    }
}
