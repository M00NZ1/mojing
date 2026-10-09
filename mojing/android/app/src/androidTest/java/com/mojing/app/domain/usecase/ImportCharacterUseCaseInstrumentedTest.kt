package com.mojing.app.domain.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.domain.util.CharacterPortableCodec
import com.mojing.app.ui.character.CharacterExportCodec
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportCharacterUseCaseInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var importer: ImportCharacterUseCase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        importer = ImportCharacterUseCase(database, SaveCharacterBindingUseCase(database))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun standardImportUsesUniqueNamesAndDoesNotBindAmbiguousNameAcrossPages() = runBlocking {
        database.encyclopediaDao().upsert(EncyclopediaEntity(name = "同名世界"))
        repeat(399) { index -> database.encyclopediaDao().upsert(EncyclopediaEntity(name = "填充$index")) }
        database.encyclopediaDao().upsert(EncyclopediaEntity(name = "同名世界"))

        database.encyclopediaDao().upsert(EncyclopediaEntity(name = "同名世界"))

        val first = importer.importStandard(listOf(exported("角色", "同名世界")))
        val second = importer.importStandard(listOf(exported("角色", "同名世界")))

        assertEquals(1, first.importedIds.size)
        assertEquals(1, second.importedIds.size)
        assertEquals(listOf("角色", "角色_2"), database.characterDao().getAll().sortedBy { it.id }.map(CharacterEntity::name))
        assertEquals(0L, database.characterDao().getById(first.importedIds.single())?.boundEncyclopediaId)
        assertEquals(1, first.unboundCount)
    }

    @Test
    fun namesInOneFileUseTrimmedKotlinIgnoreCaseMatching() = runBlocking {
        val harborId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "Harbor"))
        val unicodeId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "Ångström"))

        val result = importer.importStandard(
            listOf(exported("港口一", " Harbor "), exported("港口二", "harbor"), exported("单位", "ångström")),
        )

        assertEquals(3, result.importedIds.size)
        assertEquals(harborId, database.characterDao().getById(result.importedIds[0])?.boundEncyclopediaId)
        assertEquals(harborId, database.characterDao().getById(result.importedIds[1])?.boundEncyclopediaId)
        assertEquals(unicodeId, database.characterDao().getById(result.importedIds[2])?.boundEncyclopediaId)
    }

    @Test
    fun secondCharacterFailureRollsBackEarlierCharacterAndReturnsNoIds() = runBlocking {
        val worldId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "失败世界"))
        val existingId = SaveCharacterBindingUseCase(database)(
            CharacterEntity(name = "原有角色", personaPrompt = "原有", boundEncyclopediaId = worldId),
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_import_character BEFORE INSERT ON characters " +
                "WHEN NEW.name = '触发失败' BEGIN SELECT RAISE(ABORT, 'forced'); END",
        )

        var failed = false
        try {
            importer.importStandard(listOf(exported("首个", "失败世界"), exported("触发失败", "失败世界")))
        } catch (_: Exception) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(listOf(existingId), database.characterDao().getAll().map { it.id })
        assertEquals(1, characterMirrors(worldId, existingId).size)
        assertEquals(1, database.encyclopediaEntryDao().getByType(worldId, "character").size)
    }

    @Test
    fun cancellationBetweenCharactersRollsBackEarlierCharacter() = runBlocking {
        val worldId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "取消世界"))
        val existingId = SaveCharacterBindingUseCase(database)(
            CharacterEntity(name = "原有角色", personaPrompt = "原有", boundEncyclopediaId = worldId),
        )
        val job = launch {
            importer.importStandardForTest(
                listOf(exported("首个", "取消世界"), exported("取消", "取消世界")),
            ) { index ->
                if (index == 1) currentCoroutineContext().cancel()
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(existingId), database.characterDao().getAll().map { it.id })
        assertEquals(1, characterMirrors(worldId, existingId).size)
        assertEquals(1, database.encyclopediaEntryDao().getByType(worldId, "character").size)
    }

    @Test
    fun portableProfileSuccessPersistsCharacterAndProfileTogether() = runBlocking {
        val characterId = importer.importPortable(portableWithProfile())

        assertEquals(1, database.characterDao().getAll().size)
        assertEquals(characterId, database.characterProfileDao().getByCharacter(characterId)?.characterId)
    }

    @Test
    fun portableSamplingParametersPreserveZeroAndNegativeValues() = runBlocking {
        val characterId = importer.importPortable(
            portableWithProfile().copy(
                topP = 0f,
                frequencyPenalty = -1.25f,
                presencePenalty = 0f,
            ),
        )

        val character = database.characterDao().getById(characterId)
        assertEquals(0f, character?.topP)
        assertEquals(-1.25f, character?.frequencyPenalty)
        assertEquals(0f, character?.presencePenalty)
    }

    @Test
    fun nullablePortableSamplingParametersUseLegacyDefaults() = runBlocking {
        val characterId = importer.importPortable(portableWithProfile())

        val character = database.characterDao().getById(characterId)
        assertEquals(1f, character?.topP)
        assertEquals(0f, character?.frequencyPenalty)
        assertEquals(0f, character?.presencePenalty)
    }

    @Test
    fun nonFinitePortableSamplingParametersRejectBeforeWriteAndValidRetryCreatesOneCompleteRole() = runBlocking {
        listOf(
            portableWithProfile().copy(topP = Float.NaN),
            portableWithProfile().copy(frequencyPenalty = Float.POSITIVE_INFINITY),
            portableWithProfile().copy(presencePenalty = Float.NEGATIVE_INFINITY),
        ).forEach { invalid ->
            var rejected = false
            try { importer.importPortable(invalid) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(0, database.characterDao().getAll().size)
            assertEquals(0, profileCount())
        }

        val validId = importer.importPortable(
            portableWithProfile().copy(
                topP = 0.37f,
                frequencyPenalty = -0.5f,
                presencePenalty = 1.5f,
            ),
        )
        assertEquals(1, database.characterDao().getAll().size)
        assertEquals(1, profileCount())
        assertEquals(validId, database.characterProfileDao().getByCharacter(validId)?.characterId)
    }

    @Test
    fun portableProfileFailureRollsBackCharacterAndProfile() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_import_profile BEFORE INSERT ON character_profiles " +
                "BEGIN SELECT RAISE(ABORT, 'forced'); END",
        )
        var failed = false
        try {
            importer.importPortable(portableWithProfile())
        } catch (_: Exception) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(0, database.characterDao().getAll().size)
        assertEquals(null, database.characterProfileDao().getByCharacter(1L))
    }

    @Test
    fun invalidPortableOutputLimitCannotPersistCharacterOrProfile() = runBlocking {
        for (limit in listOf(0, -1)) {
            var rejected = false
            try { importer.importPortable(portableWithProfile().copy(maxTokens = limit)) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(0, database.characterDao().getAll().size)
            assertEquals(null, database.characterProfileDao().getByCharacter(1L))
        }
        val valid = importer.importPortable(portableWithProfile().copy(maxTokens = 32768))
        assertEquals(32768, database.characterDao().getById(valid)?.maxTokens)
    }

    @Test
    fun invalidSecondStandardOutputLimitRollsBackFirstCharacterAndMirror() = runBlocking {
        val world = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "合成世界"))
        var rejected = false
        try { importer.importStandard(listOf(exported("有效角色", "合成世界"), exported("无效角色", "合成世界").copy(maxTokens = 0))) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, database.characterDao().getAll().size)
        assertEquals(0, database.encyclopediaEntryDao().countEntries(world))
        val valid = importer.importStandard(listOf(exported("有效角色", "合成世界").copy(maxTokens = 32768)))
        assertEquals(32768, database.characterDao().getById(valid.importedIds.single())?.maxTokens)
    }

    private fun portableWithProfile() = CharacterPortableCodec.ParsedPortable(
        name = "带档案角色",
        personaPrompt = "人设",
        modelName = null,
        apiBaseUrl = null,
        temperature = null,
        maxTokens = null,
        topP = null,
        frequencyPenalty = null,
        presencePenalty = null,
        avatarColor = null,
        profile = CharacterPortableCodec.ParsedPortable.ProfileSlice(
            sourceFilename = "card.png",
            rawPersonaText = "原始人设",
            characterCardMarkdown = "卡片",
            characterCardJson = "{}",
        ),
    )

    private suspend fun characterMirrors(encyclopediaId: Long, characterId: Long) =
        database.encyclopediaEntryDao().getByType(encyclopediaId, "character")
            .filter { CharacterEncyclopediaSync.readLinkedCharacterId(it.metaJson) == characterId }

    private fun profileCount(): Int = database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM character_profiles",
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun exported(name: String, encyclopediaName: String = "") =
        CharacterExportCodec.ExportedCharacter(
            name = name,
            personaPrompt = "人设",
            apiBaseUrl = "",
            modelName = "deepseek-chat",
            temperature = 0.9f,
            maxTokens = 1200,
            topP = 1f,
            topK = 0,
            frequencyPenalty = 0f,
            presencePenalty = 0f,
            repetitionPenalty = 1f,
            avatarColor = "#F97316",
            avatarImagePath = "",
            cardImagePath = "",
            voiceProvider = "",
            voiceApiBaseUrl = "",
            voiceModel = "",
            imageGenEnabled = false,
            imageGenBaseUrl = "",
            imageGenModel = "dall-e-3",
            thinkMaxEnabled = false,
            thinkMaxModelName = "",
            boundEncyclopediaName = encyclopediaName,
        )
}
