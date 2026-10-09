package com.mojing.app.dao

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.Migrations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Publication check uses its own fresh database; the installed app database is untouched. */
@RunWith(AndroidJUnit4::class)
class PublicationMigrationNativeAppTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    @Test fun existing27To28MigrationPreservesRowsAndReopensWithoutReapplying() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val run=InstrumentationRegistry.getArguments().getString("publicationMigrationRun").orEmpty()
        UUID.fromString(run)
        val name="publication-migration-$run"
        check(!inst.targetContext.getDatabasePath(name).exists())
        helper.createDatabase(name,27).apply {
            execSQL("""INSERT INTO generation_tasks
                (id,taskKind,title,status,progressDone,progressTotal,payloadJson,errorMessage,
                targetEncyclopediaId,targetCharacterId,targetWorldTemplateId,createdAt,updatedAt)
                VALUES (9,'character_persona_ai','旧任务','COMPLETED',1,1,'{}','',NULL,4,NULL,1,2)""")
            close()
        }
        helper.runMigrationsAndValidate(name,28,true,Migrations.MIGRATION_27_28).use { db ->
            db.query("SELECT title,resultJson,resultAppliedAt FROM generation_tasks WHERE id=9").use {
                assertTrue(it.moveToFirst());assertEquals("旧任务",it.getString(0));assertEquals("",it.getString(1));assertTrue(it.isNull(2))
            }
        }
        repeat(2) {
            val db=Room.databaseBuilder(inst.targetContext,AppDatabase::class.java,name)
                .addMigrations(Migrations.MIGRATION_27_28).allowMainThreadQueries().build()
            try {
                db.openHelper.readableDatabase.query("SELECT title,resultJson,resultAppliedAt FROM generation_tasks WHERE id=9").use {
                    assertTrue(it.moveToFirst());assertEquals("旧任务",it.getString(0));assertEquals("",it.getString(1));assertTrue(it.isNull(2));assertFalse(it.moveToNext())
                }
            } finally { db.close() }
        }
    }
}
