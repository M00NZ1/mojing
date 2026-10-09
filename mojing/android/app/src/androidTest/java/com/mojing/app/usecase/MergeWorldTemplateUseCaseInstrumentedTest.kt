package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.MergeWorldTemplateUseCase
import com.mojing.app.domain.usecase.PromoteWorldTemplateUseCase
import com.mojing.app.domain.usecase.WorldMergeField
import com.mojing.app.domain.usecase.WorldTemplateMergePreviewStaleException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MergeWorldTemplateUseCaseInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var merge: MergeWorldTemplateUseCase
    private val pauseInserts = AtomicBoolean(false)
    private val insertQueries = AtomicInteger(0)
    private val reachedSecondInsert = CountDownLatch(1)
    private val releaseInsert = CountDownLatch(1)
    private val queries = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).setQueryCallback({ sql, _ ->
            queries += sql
            if (pauseInserts.get() && sql.contains("INSERT", ignoreCase = true) && sql.contains("encyclopedia_entries") && insertQueries.incrementAndGet() == 2) {
                reachedSecondInsert.countDown()
                check(releaseInsert.await(10, TimeUnit.SECONDS)) { "Cancellation test did not release the controlled insertion" }
            }
        }, java.util.concurrent.Executor { it.run() }).allowMainThreadQueries().build()
        merge = MergeWorldTemplateUseCase(database, PromoteWorldTemplateUseCase(database))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun conflictQueryCountGrowsByPageInsteadOfLoreRow() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "查询预算"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "130条资料"))
        repeat(130) { index -> database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "资料 $index", content = "内容 $index")) }
        queries.clear()
        val preview = merge.preview(templateId, targetId)
        val previewQueries = businessReadQueries().size
        org.junit.Assert.assertTrue("Preview should use a fixed aggregate and bounded token pages: $previewQueries", previewQueries <= 16)
        queries.clear()
        assertEquals(24, merge.lorePage(preview).size)
        val pageQueries = businessReadQueries().size
        org.junit.Assert.assertTrue("One preview page should batch conflict queries: $pageQueries", pageQueries <= 4)
        queries.clear()
        assertEquals(130, merge.apply(preview, emptySet()).added)
        val applyQueries = businessReadQueries().size
        org.junit.Assert.assertTrue("Apply should batch three conflict lookups per page: $applyQueries", applyQueries <= 36)
    }

    // Exclude Room invalidation and INSERT bookkeeping (changes/last_insert_rowid).
    private fun businessReadQueries(): List<String> = synchronized(queries) {
        queries.filter { sql ->
            val query = sql.trimStart()
            (query.startsWith("SELECT", true) || query.startsWith("WITH", true)) &&
                Regex("\\bFROM\\s+(world_templates|world_lore_entries|world_encyclopedias|encyclopedia_entries|legacy_world_mappings|legacy_lore_mappings)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
        }
    }

    @Test
    fun previewOnlyDoesNotWriteMappingsOrCanonicalFields() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "原世界", entryCount = 77))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "待预览", summary = "不应保存"))
        val loreId = database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "新资料"))
        val before = database.encyclopediaDao().getById(targetId)
        val preview = merge.preview(templateId, targetId)
        assertEquals(1, preview.loreToAdd)
        assertEquals(1, merge.lorePage(preview).size)
        assertEquals(before, database.encyclopediaDao().getById(targetId))
        assertEquals(null, database.legacyWorldMappingDao().getByTemplateId(templateId))
        assertEquals(null, database.legacyWorldMappingDao().getLoreById(loreId))
        assertEquals(0, database.encyclopediaEntryDao().countEntries(targetId))
    }

    @Test
    fun insertionFailureRollsBackEarlierLoreAndWorldMapping() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "保持原世界", description = "保持简介"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "写入失败样本", summary = "新简介"))
        val firstId = database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "先写入条目"))
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "拒绝条目"))
        val preview = merge.preview(templateId, targetId)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_merge_insert BEFORE INSERT ON encyclopedia_entries WHEN NEW.title='拒绝条目' BEGIN SELECT RAISE(ABORT, 'controlled merge failure'); END")
        val failure = runCatching { merge.apply(preview, setOf(WorldMergeField.DESCRIPTION)) }.exceptionOrNull()
        org.junit.Assert.assertNotNull(failure)
        assertEquals(null, database.legacyWorldMappingDao().getByTemplateId(templateId))
        assertEquals(null, database.legacyWorldMappingDao().getLoreById(firstId))
        assertEquals(0, database.encyclopediaEntryDao().countEntries(targetId))
        assertEquals("保持简介", database.encyclopediaDao().getById(targetId)?.description)
        assertEquals(2, database.worldLoreEntryDao().getByTemplate(templateId).size)
    }

    @Test
    fun cancellationDuringSecondInsertRollsBackFirstInsertAndMapping() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "取消样本", description = "原简介"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "取消模板", summary = "不应保存"))
        val firstId = database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "第一条"))
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "第二条"))
        val preview = merge.preview(templateId, targetId)
        pauseInserts.set(true)
        val pending = launch(Dispatchers.IO) { merge.apply(preview, setOf(WorldMergeField.DESCRIPTION)) }
        try {
            org.junit.Assert.assertTrue(withContext(Dispatchers.IO) { reachedSecondInsert.await(10, TimeUnit.SECONDS) })
            pending.cancel()
        } finally {
            releaseInsert.countDown()
            pending.cancelAndJoin()
            pauseInserts.set(false)
        }
        assertEquals(null, database.legacyWorldMappingDao().getByTemplateId(templateId))
        assertEquals(null, database.legacyWorldMappingDao().getLoreById(firstId))
        assertEquals(0, database.encyclopediaEntryDao().countEntries(targetId))
        assertEquals("原简介", database.encyclopediaDao().getById(targetId)?.description)
        assertEquals(2, database.worldLoreEntryDao().getByTemplate(templateId).size)
    }

    @Test
    fun previewAndApplyKeepTargetFieldsUnlessExplicitlySelected() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(
            EncyclopediaEntity(
                name = "已有世界", description = "原简介", coverImagePath = "old.png",
                worldPrompt = "原规则", gameplayMode = "原玩法", antiCheatPrompt = "原约束",
            ),
        )
        val templateId = database.worldTemplateDao().upsert(
            WorldTemplateEntity(
                label = "旧模板", summary = "新简介", coverImagePath = "new.png",
                worldPrompt = "新规则", gameplayMode = "新玩法", antiCheatPrompt = "新约束",
            ),
        )
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "新条目", content = "正文"))

        val preview = merge.preview(templateId, targetId)
        val result = merge.apply(preview, emptySet())
        assertEquals("原简介", result.world.description)
        assertEquals("原规则", result.world.worldPrompt)
        assertEquals("old.png", result.world.coverImagePath)
        assertEquals(1, result.added)

        val explicitTargetId = database.encyclopediaDao().upsert(
            EncyclopediaEntity(
                name = "另一个世界", description = "原简介", coverImagePath = "old.png",
                worldPrompt = "原规则", gameplayMode = "原玩法", antiCheatPrompt = "原约束",
            ),
        )
        val explicitTemplateId = database.worldTemplateDao().upsert(
            WorldTemplateEntity(
                label = "另一个旧模板", summary = "新简介", coverImagePath = "new.png",
                worldPrompt = "新规则", gameplayMode = "新玩法", antiCheatPrompt = "新约束",
            ),
        )
        val explicit = merge.preview(explicitTemplateId, explicitTargetId)
        val selected = merge.apply(
            explicit,
            setOf(WorldMergeField.DESCRIPTION, WorldMergeField.WORLD_PROMPT, WorldMergeField.GAMEPLAY_MODE, WorldMergeField.ANTI_CHEAT_PROMPT, WorldMergeField.COVER),
        )
        assertEquals("新简介", selected.world.description)
        assertEquals("新规则", selected.world.worldPrompt)
        assertEquals("new.png", selected.world.coverImagePath)
    }

    @Test
    fun duplicateTitlesAndRepeatedApplyAreSkippedWithoutOverwritingEdits() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "已有世界"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "旧模板"))
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "  重复  ", content = "旧"))
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "重复", content = "源重复"))
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "新", content = "新正文"))
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "\t唯一\t", content = "保留"))
        val existingId = database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(encyclopediaId = targetId, title = "重复", content = "用户编辑"),
        )

        val first = merge.apply(merge.preview(templateId, targetId), emptySet())
        assertEquals(2, first.added)
        assertEquals("用户编辑", database.encyclopediaEntryDao().getById(existingId)?.content)
        val second = merge.apply(merge.preview(templateId, targetId), emptySet())
        assertEquals(0, second.added)
        assertEquals(4, second.skipped)
    }

    @Test
    fun staleTargetRejectsBeforeWrite() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "已有世界", description = "旧"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "旧模板"))
        val preview = merge.preview(templateId, targetId)
        database.encyclopediaDao().updateWorldSettings(targetId, preview.targetWorld.updatedAt, "已有世界", "外部修改", "", "自由剧情", "", 22L)
        assertThrows(WorldTemplateMergePreviewStaleException::class.java) { runBlocking { merge.apply(preview, emptySet()) } }
        assertEquals(null, database.legacyWorldMappingDao().getByTemplateId(templateId))
    }

    @Test
    fun previewCountsCrossPageConflicts() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "已有世界"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "旧模板"))
        repeat(130) { index ->
            database.worldLoreEntryDao().upsert(
                WorldLoreEntryEntity(
                    worldTemplateId = templateId,
                    title = when (index) {
                        0, 70 -> "跨页重复"
                        else -> "条目$index"
                    },
                    content = "正文$index",
                ),
            )
        }
        database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(encyclopediaId = targetId, title = "条目120", content = "用户正文"),
        )
        val preview = merge.preview(templateId, targetId)
        assertEquals(130, preview.loreTotal)
        assertEquals(128, preview.loreToAdd)
        assertEquals(2, preview.loreConflicts)
    }

    @Test
    fun mappedToAnotherWorldIsRejected() = runBlocking {
        database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第一世界"))
        val secondWorld = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第二世界"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "旧模板"))
        PromoteWorldTemplateUseCase(database).invoke(templateId)
        assertThrows(IllegalStateException::class.java) { runBlocking { merge.preview(templateId, secondWorld) } }
        assertNotEquals(secondWorld, database.legacyWorldMappingDao().getByTemplateId(templateId)?.encyclopediaId)
    }

    @Test
    fun staleSourceBodyOrTargetTitleRejectsWithoutMapping() = runBlocking {
        val targetId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "已有世界"))
        val templateId = database.worldTemplateDao().upsert(WorldTemplateEntity(label = "旧模板"))
        val loreId = database.worldLoreEntryDao().upsert(
            WorldLoreEntryEntity(worldTemplateId = templateId, title = "来源", content = "旧正文"),
        )
        val sourcePreview = merge.preview(templateId, targetId)
        database.worldLoreEntryDao().upsert(
            WorldLoreEntryEntity(id = loreId, worldTemplateId = templateId, title = "来源", content = "新正文"),
        )
        assertThrows(WorldTemplateMergePreviewStaleException::class.java) {
            runBlocking { merge.apply(sourcePreview, emptySet()) }
        }
        assertEquals(null, database.legacyWorldMappingDao().getByTemplateId(templateId))

        val targetEntryId = database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(encyclopediaId = targetId, title = "目标旧名", content = "用户正文"),
        )
        val targetPreview = merge.preview(templateId, targetId)
        database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(id = targetEntryId, encyclopediaId = targetId, title = "目标新名", content = "用户正文"),
        )
        assertThrows(WorldTemplateMergePreviewStaleException::class.java) {
            runBlocking { merge.apply(targetPreview, emptySet()) }
        }
        assertEquals(null, database.legacyWorldMappingDao().getByTemplateId(templateId))
    }
}
