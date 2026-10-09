package com.mojing.app.dao

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationTaskResultSnapshotMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migration27To28PreservesLegacyRowsAndDefaultsSnapshotColumns() {
        val name = "generation-task-result-snapshot-migration"
        helper.createDatabase(name, 27).apply {
            execSQL("""INSERT INTO generation_tasks
                (id, taskKind, title, status, progressDone, progressTotal, payloadJson, errorMessage,
                 targetEncyclopediaId, targetCharacterId, targetWorldTemplateId, createdAt, updatedAt)
                VALUES (9, 'character_persona_ai', '旧任务', 'COMPLETED', 1, 1, '{}', '', NULL, 4, NULL, 1, 2)
            """.trimIndent())
            close()
        }
        helper.runMigrationsAndValidate(name, 28, true, Migrations.MIGRATION_27_28).use { db ->
            db.query("SELECT title, resultJson, resultAppliedAt FROM generation_tasks WHERE id = 9").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("旧任务", row.getString(0))
                assertEquals("", row.getString(1))
                assertTrue(row.isNull(2))
            }
        }
    }
}
