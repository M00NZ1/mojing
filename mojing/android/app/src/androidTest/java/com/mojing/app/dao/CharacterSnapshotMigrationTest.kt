package com.mojing.app.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
class CharacterSnapshotMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migration22To23PreservesUnscopedSnapshotWithoutInjectingItIntoMain() {
        val name = "character-snapshot-migration"
        helper.createDatabase(name, 22).apply {
            execSQL("""INSERT INTO sessions (id, title, summary, createdAt, updatedAt, thinkMaxEnabled, pinnedAt, displayContextTokenLimit)
                VALUES (10, '旧会话', '', 1, 1, 0, 0, 1000000)""".trimIndent())
            val character = ContentValues().apply { put("id", 20L) }
            query("PRAGMA table_info(characters)").use { columns ->
                while (columns.moveToNext()) {
                    val column = columns.getString(1)
                    if (column == "id") continue
                    when (columns.getString(2)) {
                        "REAL" -> character.put(column, 0.0)
                        "INTEGER" -> character.put(column, 0L)
                        else -> character.put(column, "")
                    }
                }
            }
            insert("characters", SQLiteDatabase.CONFLICT_ABORT, character)
            execSQL("""INSERT INTO session_character_states
                (id, sessionId, characterId, dynamicStateJson, relationsJson, privateFactsJson,
                 eventLogJson, goalsJson, emotionalState, updatedAt)
                VALUES (7, 10, 20, '{"mood":"主线"}', '{}', '[]', '[]', '[]', '平静', 123)
            """.trimIndent())
            close()
        }

        helper.runMigrationsAndValidate(name, 23, true, Migrations.MIGRATION_22_23).apply {
            query("SELECT id, branchId, lastSnapshotAttemptUserMessageId, snapshotIsValid, dynamicStateJson, emotionalState, updatedAt FROM session_character_states").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals(7L, row.getLong(0))
                assertEquals("__legacy_unscoped__", row.getString(1))
                assertEquals(0L, row.getLong(2))
                assertEquals(0, row.getInt(3))
                assertEquals("{\"mood\":\"主线\"}", row.getString(4))
                assertEquals("平静", row.getString(5))
                assertEquals(123L, row.getLong(6))
                assertEquals(false, row.moveToNext())
            }
            query("SELECT COUNT(*) FROM session_character_states WHERE branchId IN ('main', 'other')").use { row ->
                row.moveToFirst()
                assertEquals(0, row.getInt(0))
            }
            close()
        }
    }
}
