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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BranchVisibilityMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration15To16BuildsNestedSegmentsWithoutChangingMessages() = runBlocking {
        val databaseName = "branch-visibility-migration"
        helper.createDatabase(databaseName, 15).apply {
            execSQL(
                """
                INSERT INTO sessions
                    (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit)
                VALUES (1, '旧分支会话', '', 1, 1, 0, 0, 0)
                """.trimIndent(),
            )
            insertMessage(1, "main", null, "主线一")
            insertMessage(2, "main", null, "主线二")
            insertMessage(3, "main", null, "主线三不可见")
            execSQL(
                """
                INSERT INTO session_branches
                    (id, sessionId, branchId, label, sourceMessageId, parentBranchId,
                     isCheckpoint, checkpointLabel, createdAt)
                VALUES (1, 1, 'a', '', 2, 'main', 0, '', 4),
                       (2, 1, 'b', '', 5, 'a', 0, '', 6)
                """.trimIndent(),
            )
            insertMessage(4, "a", 2, "主线二编辑版")
            insertMessage(5, "a", null, "A 后续")
            insertMessage(6, "b", null, "B 后续")
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            16,
            true,
            Migrations.MIGRATION_15_16,
        ).apply {
            query(
                """
                SELECT sourceBranchId, maxMessageId
                FROM branch_visibility_segments
                WHERE sessionId = 1 AND targetBranchId = 'b'
                ORDER BY CASE sourceBranchId WHEN 'b' THEN 1 WHEN 'a' THEN 2 ELSE 3 END
                """.trimIndent(),
            ).use { cursor ->
                val actual = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1))
                }
                assertEquals(listOf("b" to Long.MAX_VALUE, "a" to 5L, "main" to 2L), actual)
            }
            query("SELECT COUNT(*), SUM(length(content)) FROM messages").use { cursor ->
                cursor.moveToFirst()
                assertEquals(6, cursor.getInt(0))
                assertEquals(26, cursor.getInt(1))
            }
            close()
        }

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            databaseName,
        ).addMigrations(
            Migrations.MIGRATION_15_16,
            Migrations.MIGRATION_16_17,
            Migrations.MIGRATION_17_18,
        ).build()
        try {
            val dao = db.messageDao()
            assertEquals(
                listOf("B 后续", "A 后续", "主线二编辑版", "主线一"),
                dao.getVisibleMessagesTail(1L, "b", 10).map { it.content },
            )
            assertNull(dao.getVisibleMessageById(1L, "b", 2L))
        } finally {
            db.close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertMessage(
        id: Long,
        branchId: String,
        regeneratedFromMessageId: Long?,
        content: String,
    ) {
        execSQL(
            """
            INSERT INTO messages
                (id, sessionId, speakerType, characterId, branchId, parentMessageId,
                 regeneratedFromMessageId, swipeGroupId, content, structuredContentJson,
                 includeInContext, createdAt, searchNormalized, searchTerms)
            VALUES (?, 1, 'user', NULL, ?, NULL, ?, NULL, ?, '{}', 1, ?, '', '')
            """.trimIndent(),
            arrayOf<Any?>(id, branchId, regeneratedFromMessageId, content, id),
        )
    }
}
