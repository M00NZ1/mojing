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
class BranchSwipeSelectionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration17To18KeepsValidDefaultsAndRepairsOnlyInvalidGroups() {
        val databaseName = "branch-swipe-selection-migration"
        helper.createDatabase(databaseName, 17).apply {
            execSQL(
                "INSERT INTO sessions " +
                    "(id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit) " +
                    "VALUES (1, '旧会话', '', 1, 1, 0, 0, 1000000)",
            )
            insertMessage(1L, "stable", includeInContext = false, content = "稳定旧版")
            insertMessage(2L, "stable", includeInContext = true, content = "稳定采用版")
            insertMessage(3L, "zero", includeInContext = false, content = "零选旧版")
            insertMessage(4L, "zero", includeInContext = false, content = "零选新版")
            insertMessage(5L, "multiple", includeInContext = true, content = "多选旧版")
            insertMessage(6L, "multiple", includeInContext = true, content = "多选新版")
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            18,
            true,
            Migrations.MIGRATION_17_18,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM branch_swipe_selections").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            assertEquals(listOf(2L), db.activeMessageIds("stable"))
            assertEquals(listOf(4L), db.activeMessageIds("zero"))
            assertEquals(listOf(6L), db.activeMessageIds("multiple"))
            db.query("SELECT COUNT(*), SUM(length(content)) FROM messages").use { cursor ->
                cursor.moveToFirst()
                assertEquals(6, cursor.getInt(0))
                assertEquals(25, cursor.getInt(1))
            }
        }
    }

    private fun SupportSQLiteDatabase.insertMessage(
        id: Long,
        groupId: String,
        includeInContext: Boolean,
        content: String,
    ) {
        execSQL(
            """
            INSERT INTO messages
                (id, sessionId, speakerType, characterId, branchId, parentMessageId,
                 regeneratedFromMessageId, swipeGroupId, content, structuredContentJson,
                 includeInContext, createdAt, searchNormalized, searchTerms)
            VALUES (?, 1, 'character', NULL, 'main', NULL, NULL, ?, ?, '{}', ?, ?, '', '')
            """.trimIndent(),
            arrayOf<Any?>(id, groupId, content, if (includeInContext) 1 else 0, id),
        )
    }

    private fun SupportSQLiteDatabase.activeMessageIds(groupId: String): List<Long> =
        query(
            "SELECT id FROM messages WHERE sessionId = 1 AND swipeGroupId = ? " +
                "AND includeInContext = 1 ORDER BY id",
            arrayOf(groupId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
}
