package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.SaveWorldTemplatePackageUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveWorldTemplatePackageUseCaseInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var savePackage: SaveWorldTemplatePackageUseCase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        savePackage = SaveWorldTemplatePackageUseCase(database, com.mojing.app.domain.usecase.PromoteWorldTemplateUseCase(database))
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun packageFailureRollsBackTemplateAndOriginalLore() = runBlocking {
        val templateDao = database.worldTemplateDao()
        val loreDao = database.worldLoreEntryDao()
        val originalId = templateDao.upsert(
            WorldTemplateEntity(
                templateId = "atomic-world",
                label = "原世界",
                summary = "原摘要",
                pinnedAt = 123L,
            ),
        )
        loreDao.upsert(
            WorldLoreEntryEntity(
                worldTemplateId = originalId,
                title = "原设定",
                content = "必须保留",
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_failed_lore
            BEFORE INSERT ON world_lore_entries
            WHEN NEW.title = '触发失败'
            BEGIN
                SELECT RAISE(ABORT, 'injected lore failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching {
            savePackage(
                WorldTemplateEntity(
                    templateId = "atomic-world",
                    label = "错误的新世界",
                    summary = "不应留下",
                    worldPrompt = "错误但完整的世界规则",
                ),
                listOf(
                    WorldLoreEntryEntity(worldTemplateId = 0L, title = "新设定一", content = "不应留下"),
                    WorldLoreEntryEntity(worldTemplateId = 0L, title = "新设定二", content = "不应留下"),
                    WorldLoreEntryEntity(worldTemplateId = 0L, title = "触发失败", content = "不应留下"),
                ),
            )
        }.exceptionOrNull()
        assertNotNull(failure)

        val restoredTemplate = requireNotNull(templateDao.getByTemplateId("atomic-world"))
        assertEquals("原世界", restoredTemplate.label)
        assertEquals("原摘要", restoredTemplate.summary)
        assertEquals(123L, restoredTemplate.pinnedAt)
        assertEquals(
            listOf("原设定"),
            loreDao.getByTemplate(restoredTemplate.id).map { it.title },
        )
    }

    @Test
    fun successfulReplacementKeepsLocalIdentityAndReplacesWholeLoreSet() = runBlocking {
        val templateDao = database.worldTemplateDao()
        val loreDao = database.worldLoreEntryDao()
        val originalId = templateDao.upsert(
            WorldTemplateEntity(
                templateId = "replace-world",
                label = "旧世界",
                isBuiltin = true,
                createdAt = 10L,
                pinnedAt = 20L,
            ),
        )
        loreDao.upsert(WorldLoreEntryEntity(worldTemplateId = originalId, title = "旧设定"))

        val result = savePackage(
            WorldTemplateEntity(
                templateId = "replace-world",
                label = "新世界",
                worldPrompt = "新的完整世界规则",
                createdAt = 999L,
            ),
            listOf(
                WorldLoreEntryEntity(worldTemplateId = 0L, title = "新设定一", content = "正文一"),
                WorldLoreEntryEntity(worldTemplateId = 0L, title = "新设定二", content = "正文二"),
                WorldLoreEntryEntity(worldTemplateId = 0L, title = "新设定三", content = "正文三"),
            ),
        )

        assertEquals(originalId, result.template.id)
        assertEquals("新世界", result.template.label)
        assertEquals(true, result.template.isBuiltin)
        assertEquals(10L, result.template.createdAt)
        assertEquals(20L, result.template.pinnedAt)
        assertEquals(3, result.loreCount)
        assertEquals(
            listOf("新设定一", "新设定二", "新设定三"),
            loreDao.getByTemplate(originalId).map { it.title },
        )
    }

    @Test
    fun incompleteGeneratedPackageIsRejectedWithoutTouchingExistingWorld() = runBlocking {
        val templateDao = database.worldTemplateDao()
        val loreDao = database.worldLoreEntryDao()
        val originalId = templateDao.upsert(
            WorldTemplateEntity(
                templateId = "complete-world",
                label = "可用世界",
                worldPrompt = "原世界规则",
            ),
        )
        loreDao.upsert(
            WorldLoreEntryEntity(
                worldTemplateId = originalId,
                title = "原设定",
                content = "原正文",
            ),
        )
        val threeLore = (1..3).map { index ->
            WorldLoreEntryEntity(worldTemplateId = 0L, title = "设定$index", content = "正文$index")
        }

        val missingPrompt = runCatching {
            savePackage(
                WorldTemplateEntity(templateId = "complete-world", label = "空主提示"),
                threeLore,
            )
        }.exceptionOrNull()
        val tooFewLore = runCatching {
            savePackage(
                WorldTemplateEntity(
                    templateId = "complete-world",
                    label = "条目不足",
                    worldPrompt = "新世界规则",
                ),
                threeLore.take(2),
            )
        }.exceptionOrNull()

        assertNotNull(missingPrompt)
        assertNotNull(tooFewLore)
        val untouched = requireNotNull(templateDao.getByTemplateId("complete-world"))
        assertEquals("可用世界", untouched.label)
        assertEquals("原世界规则", untouched.worldPrompt)
        assertEquals(listOf("原设定"), loreDao.getByTemplate(originalId).map { it.title })
    }
}
