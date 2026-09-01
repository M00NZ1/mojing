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
class ContextMemoryValidityMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration18To19KeepsExistingMemoryUsableWithoutRewritingIt() {
        val databaseName = "context-memory-validity-migration"
        helper.createDatabase(databaseName, 18).apply {
            execSQL(
                "INSERT INTO sessions " +
                    "(id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit) " +
                    "VALUES (1, '旧会话', '', 1, 1, 0, 0, 1000000)",
            )
            execSQL(
                """
                INSERT INTO session_context_memories(
                    id, sessionId, branchId, globalSummary, userStateJson,
                    characterStatesJson, relationshipStatesJson, worldStateJson,
                    recentTimelineJson, openThreadsJson, continuityRulesJson,
                    sourceStartMessageId, sourceEndMessageId, memoryVersion, createdAt, updatedAt
                ) VALUES (
                    5, 1, 'main', '旧剧情仍可用', '{}', '[]', '[]', '{}',
                    '[]', '[]', '[]', 10, 20, 3, 30, 40
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            19,
            true,
            Migrations.MIGRATION_18_19,
        ).use { db ->
            db.query(
                "SELECT globalSummary, sourceStartMessageId, sourceEndMessageId, " +
                    "memoryVersion, isValid, revision, createdAt, updatedAt " +
                    "FROM session_context_memories WHERE id = 5",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("旧剧情仍可用", cursor.getString(0))
                assertEquals(10L, cursor.getLong(1))
                assertEquals(20L, cursor.getLong(2))
                assertEquals(3, cursor.getInt(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals(0L, cursor.getLong(5))
                assertEquals(30L, cursor.getLong(6))
                assertEquals(40L, cursor.getLong(7))
            }
        }
    }
}
