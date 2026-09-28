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
class SessionLibraryIndexMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration25To26KeepsMessagesAndIndexesLatestMainMessage() {
        val name = "session-library-index-migration"
        helper.createDatabase(name, 25).apply {
            execSQL(
                """INSERT INTO sessions
                    (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt,
                     displayContextTokenLimit, creationRequestId)
                    VALUES (10, '旧故事', '', 1, 2, 0, 0, 1000000, NULL)
                """.trimIndent(),
            )
            listOf(
                listOf(2, "main", 200, "第一条新消息"),
                listOf(3, "main", 100, "较早消息"),
                listOf(4, "main", 200, "同时间后写消息"),
                listOf(5, "branch-a", 300, "其他故事线"),
            ).forEach { (id, branch, time, content) ->
                execSQL(
                    """INSERT INTO messages
                        (id, sessionId, speakerType, branchId, content, structuredContentJson,
                         includeInContext, createdAt, searchNormalized, searchTerms)
                        VALUES (?, 10, 'user', ?, ?, '{}', 1, ?, '', '')
                    """.trimIndent(),
                    arrayOf<Any>(id, branch, content, time),
                )
            }
            close()
        }

        helper.runMigrationsAndValidate(name, 26, true, Migrations.MIGRATION_25_26).use { db ->
            db.query(
                "SELECT id, content FROM messages WHERE sessionId = 10 AND branchId = 'main' " +
                    "ORDER BY createdAt DESC, id DESC",
            ).use { rows ->
                val found = buildList {
                    while (rows.moveToNext()) add(rows.getLong(0) to rows.getString(1))
                }
                assertEquals(
                    listOf(4L to "同时间后写消息", 2L to "第一条新消息", 3L to "较早消息"),
                    found,
                )
            }
            db.query("SELECT content FROM messages WHERE id = 5").use { rows ->
                assertEquals(true, rows.moveToFirst())
                assertEquals("其他故事线", rows.getString(0))
            }
            db.query("PRAGMA index_info('index_messages_sessionId_branchId_createdAt_id')").use { columns ->
                val names = buildList {
                    while (columns.moveToNext()) add(columns.getString(columns.getColumnIndexOrThrow("name")))
                }
                assertEquals(listOf("sessionId", "branchId", "createdAt", "id"), names)
            }
        }
    }
}
