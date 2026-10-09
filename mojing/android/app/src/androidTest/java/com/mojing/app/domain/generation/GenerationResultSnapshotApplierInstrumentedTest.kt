package com.mojing.app.domain.generation

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationResultSnapshotApplierInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var applier: GenerationResultSnapshotApplier

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        applier = GenerationResultSnapshotApplier(
            database = db,
            taskDao = db.generationTaskDao(),
            characterDao = db.characterDao(),
            worldTemplateDao = db.worldTemplateDao(),
            saveCharacterBinding = SaveCharacterBindingUseCase(db),
        )
    }

    @After fun tearDown() = db.close()

    @Test fun characterApplyUpdatesCharacterAndBoundMirrorInOneRoomFlow() = runBlocking {
        db.encyclopediaDao().upsert(EncyclopediaEntity(id = 1, name = "百科"))
        db.characterDao().upsert(CharacterEntity(id = 2, name = "角色", personaPrompt = "旧", boundEncyclopediaId = 1))
        val taskId = insertTask(GenerationTaskKinds.CHARACTER_PERSONA_AI, characterId = 2,
            result = GenerationResultSnapshotCodec.encode(GenerationResultSnapshot.CharacterPersona("新")))

        assertEquals(GenerationQueueProcessor.ResultApplyOutcome.Applied, applier.apply(taskId, expectedPersonaPrompt = "旧"))
        assertEquals("新", db.characterDao().getById(2)?.personaPrompt)
        val mirror = db.encyclopediaEntryDao().getByType(1, "character")
            .first { JsonParser.parseString(it.metaJson).getAsJsonObject().get("linkedCharacterId").asLong == 2L }
        assertEquals("新", mirror.content)
        assertTrue(db.generationTaskDao().getById(taskId)?.resultAppliedAt != null)
    }

    @Test fun worldApplyUsesCasAndRejectsStalePreview() = runBlocking {
        db.worldTemplateDao().upsert(WorldTemplateEntity(id = 3, summary = "旧摘要", worldPrompt = "旧世界"))
        val taskId = insertTask(GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI, worldId = 3,
            result = GenerationResultSnapshotCodec.encode(GenerationResultSnapshot.WorldTemplate("新摘要", "新世界")))
        db.worldTemplateDao().updateGeneratedContentIfUnchanged(3, "旧摘要", "旧世界", "用户摘要", "旧世界", 2)
        assertEquals(GenerationQueueProcessor.ResultApplyOutcome.StalePreview, applier.apply(taskId, expectedSummary = "旧摘要", expectedWorldPrompt = "旧世界"))
        assertEquals("用户摘要", db.worldTemplateDao().getById(3)?.summary)
        assertEquals(null, db.generationTaskDao().getById(taskId)?.resultAppliedAt)
    }

    @Test fun deletedTargetKeepsReadableResultAndCannotApply() = runBlocking {
        db.characterDao().upsert(CharacterEntity(id = 44, personaPrompt = "旧"))
        val taskId = insertTask(GenerationTaskKinds.CHARACTER_PERSONA_AI, characterId = 44,
            result = GenerationResultSnapshotCodec.encode(GenerationResultSnapshot.CharacterPersona("结果")))
        db.characterDao().delete(44)
        assertEquals(GenerationQueueProcessor.ResultApplyOutcome.TargetMissing, applier.apply(taskId, expectedPersonaPrompt = "旧"))
        assertEquals("结果", (GenerationResultSnapshotCodec.decode(db.generationTaskDao().getById(taskId)!!.resultJson) as GenerationResultSnapshot.CharacterPersona).personaPrompt)
    }

    @Test fun concurrentApplyMarksExactlyOneApplication() = runBlocking {
        db.characterDao().upsert(CharacterEntity(id = 5, personaPrompt = "旧"))
        val taskId = insertTask(GenerationTaskKinds.CHARACTER_PERSONA_AI, characterId = 5,
            result = GenerationResultSnapshotCodec.encode(GenerationResultSnapshot.CharacterPersona("新")))
        val outcomes = listOf(async { applier.apply(taskId, expectedPersonaPrompt = "旧") }, async { applier.apply(taskId, expectedPersonaPrompt = "旧") }).awaitAll()
        assertEquals(1, outcomes.count { it == GenerationQueueProcessor.ResultApplyOutcome.Applied })
        assertEquals(1, outcomes.count { it == GenerationQueueProcessor.ResultApplyOutcome.AlreadyApplied })
    }

    @Test fun forcedRoomFailureRollsBackTargetAndAppliedMarker() = runBlocking {
        db.characterDao().upsert(CharacterEntity(id = 6, personaPrompt = "旧"))
        val taskId = insertTask(GenerationTaskKinds.CHARACTER_PERSONA_AI, characterId = 6,
            result = GenerationResultSnapshotCodec.encode(GenerationResultSnapshot.CharacterPersona("新")))
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_apply BEFORE UPDATE OF resultAppliedAt ON generation_tasks
            BEGIN SELECT RAISE(ABORT, 'test write failure'); END""")
        val failure = runCatching { applier.apply(taskId, expectedPersonaPrompt = "旧") }
        assertTrue(failure.isFailure)
        assertEquals("旧", db.characterDao().getById(6)?.personaPrompt)
        assertEquals(null, db.generationTaskDao().getById(taskId)?.resultAppliedAt)
    }

    @Test fun refreshedPreviewAppliesToCurrentUserContentExactlyOnce() = runBlocking {
        db.worldTemplateDao().upsert(WorldTemplateEntity(id = 7, summary = "用户改过的摘要", worldPrompt = "用户改过的世界"))
        val taskId = insertTask(GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI, worldId = 7,
            result = GenerationResultSnapshotCodec.encode(GenerationResultSnapshot.WorldTemplate("新摘要", "新世界")))
        val preview = applier.preview(taskId)!!
        assertEquals("用户改过的摘要", preview.currentSummary)
        assertEquals("用户改过的世界", preview.currentWorld)
        assertEquals(GenerationQueueProcessor.ResultApplyOutcome.Applied,
            applier.apply(taskId, expectedSummary = preview.currentSummary, expectedWorldPrompt = preview.currentWorld))
        assertEquals("新世界", db.worldTemplateDao().getById(7)?.worldPrompt)
        assertTrue(applier.preview(taskId)!!.alreadyApplied)
        assertEquals(GenerationQueueProcessor.ResultApplyOutcome.AlreadyApplied,
            applier.apply(taskId, expectedSummary = preview.currentSummary, expectedWorldPrompt = preview.currentWorld))
    }

    private suspend fun insertTask(kind: String, characterId: Long? = null, worldId: Long? = null, result: String): Long =
        db.generationTaskDao().insert(GenerationTaskEntity(
            taskKind = kind, title = "测试", status = GenerationTaskStatus.COMPLETED,
            payloadJson = "{}", targetCharacterId = characterId, targetWorldTemplateId = worldId,
            resultJson = result,
        ))
}
