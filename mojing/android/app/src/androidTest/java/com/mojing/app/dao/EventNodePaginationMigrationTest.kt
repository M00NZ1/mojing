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
class EventNodePaginationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration23To24PreservesEventsAndAddsPagingIndex() {
        val name = "event-node-pagination-migration"
        helper.createDatabase(name, 23).apply {
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
                    VALUES (7, 10, NULL, 'main', NULL, 'action', '保留事件', '原描述', 3, NULL, 0, 123)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 24, true, Migrations.MIGRATION_23_24).use { db ->
            db.query("SELECT title, description, createdAt FROM session_event_nodes WHERE id = 7").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("保留事件", row.getString(0))
                assertEquals("原描述", row.getString(1))
                assertEquals(123L, row.getLong(2))
            }
            db.query("PRAGMA index_list('session_event_nodes')").use { indexes ->
                var found = false
                val nameIndex = indexes.getColumnIndexOrThrow("name")
                while (indexes.moveToNext()) {
                    if (indexes.getString(nameIndex) == "index_session_event_nodes_sessionId_branchId_createdAt_id") {
                        found = true
                    }
                }
                assertEquals(true, found)
            }
        }
    }
}
