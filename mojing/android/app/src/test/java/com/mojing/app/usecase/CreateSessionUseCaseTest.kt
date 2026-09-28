package com.mojing.app.usecase

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.domain.usecase.SessionCreationTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateSessionUseCaseTest {
    private val transaction = mockk<SessionCreationTransaction>(relaxed = true)
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val worldTemplateDao = mockk<WorldTemplateDao>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val mappingDao = mockk<com.mojing.app.data.local.dao.LegacyWorldMappingDao> { coEvery { getByTemplateId(any()) } returns null }
    private val encyclopediaDao = mockk<com.mojing.app.data.local.dao.EncyclopediaDao> { coEvery { getById(any()) } returns null }
    private val useCase = CreateSessionUseCase(
        worldMappingDao = mappingDao,
        encyclopediaDao = encyclopediaDao,
        transaction = transaction,
        characterDao = characterDao,
        worldTemplateDao = worldTemplateDao,
        secureStorage = secureStorage,
    )

    @Test
    fun blankSessionCreatesCompleteWorldUsingDefaultTemplate() = runTest {
        coEvery { transaction(any(), any(), any()) } returns 7L
        every { secureStorage.defaultWorldTemplateId } returns "default-world"
        every { secureStorage.defaultNarratorEnabled } returns true
        every { secureStorage.defaultChoiceGenerationEnabled } returns false
        every { secureStorage.defaultAntiCheatEnabled } returns true
        coEvery { worldTemplateDao.getByTemplateId("default-world") } returns WorldTemplateEntity(
            templateId = "default-world",
            gameplayMode = "江湖",
            worldPrompt = "门派争斗",
            suggestedChoicesJson = "[\"入城\"]",
            antiCheatPrompt = "不可改写门派规则",
        )

        val result = useCase.createBlank()

        assertEquals(CreateSessionUseCase.Result.Created(7L), result)
        coVerify {
            transaction(
                any(),
                match {
                    it.sessionId == 0L && it.templateId == "default-world" &&
                        it.gameplayMode == "江湖" && it.worldPrompt == "门派争斗" &&
                        it.suggestedChoicesJson == "[\"入城\"]" &&
                        it.antiCheatPrompt == "不可改写门派规则" &&
                        it.narratorEnabled && !it.choiceGenerationEnabled && it.antiCheatEnabled
                },
                match { it.isEmpty() },
            )
        }
    }

    @Test
    fun creationRequestIdsReachBothSessionTransactions() = runTest {
        coEvery { transaction(any(), any(), any()) } returns 7L
        coEvery { characterDao.getById(1L) } returns CharacterEntity(id = 1L)

        useCase.createBlank(creationRequestId = "blank-request")
        useCase.create(characterIds = listOf(1L), creationRequestId = "configured-request")

        coVerify(exactly = 1) {
            transaction(match { it.creationRequestId == "blank-request" }, any(), any())
        }
        coVerify(exactly = 1) {
            transaction(match { it.creationRequestId == "configured-request" }, any(), any())
        }
    }

    @Test
    fun optionsRejectMissingCharacterWithoutWritingSession() = runTest {
        coEvery { characterDao.getById(42L) } returns null

        val result = useCase.create(
            characterIds = listOf(42L),
            encyclopediaId = 3L,
        )

        assertEquals(CreateSessionUseCase.Result.CharacterNotFound, result)
        coVerify(exactly = 0) { transaction(any(), any(), any()) }
    }

    @Test
    fun characterShortcutCreatesBoundSessionUsingCurrentDefaults() = runTest {
        coEvery { transaction(any(), any(), any()) } returns 12L
        coEvery { characterDao.getById(5L) } returns CharacterEntity(
            id = 5L,
            name = " 林默 ",
            boundEncyclopediaId = 9L,
        )
        every { secureStorage.defaultNarratorEnabled } returns true
        every { secureStorage.defaultChoiceGenerationEnabled } returns false
        every { secureStorage.defaultAntiCheatEnabled } returns false

        val result = useCase.createForCharacter(5L)

        assertEquals(CreateSessionUseCase.Result.Created(12L), result)
        coVerify {
            transaction(
                match { it.title == "林默 · 新故事" },
                match {
                    it.sessionId == 0L && it.encyclopediaId == 9L &&
                        it.narratorEnabled && !it.choiceGenerationEnabled && !it.antiCheatEnabled
                },
                match { it.single().sessionId == 0L && it.single().characterId == 5L },
            )
        }
    }

    @Test
    fun characterShortcutCreatesUnboundCharacterSession() = runTest {
        coEvery { characterDao.getById(5L) } returns CharacterEntity(id = 5L, boundEncyclopediaId = 0L)

        val result = useCase.createForCharacter(5L)

        assertTrue(result is CreateSessionUseCase.Result.Created)
        coVerify { transaction(any(), match { it.encyclopediaId == null }, match { it.single().characterId == 5L }) }
    }

    @Test
    fun optionsCanCreateNarrativeOnlySessionWithoutParticipants() = runTest {
        coEvery { transaction(any(), any(), any()) } returns 8L

        val result = useCase.create(
            title = "剧情推演",
            gameplayMode = "剧情推演",
            narratorEnabled = true,
            characterIds = emptyList(),
            allowNoParticipants = true,
        )

        assertEquals(CreateSessionUseCase.Result.Created(8L), result)
        coVerify {
            transaction(
                any(),
                match { it.sessionId == 0L && it.gameplayMode == "剧情推演" && it.narratorEnabled },
                match { it.isEmpty() },
            )
        }
    }

    @Test
    fun optionsRejectCharactersFromDifferentEncyclopedias() = runTest {
        coEvery { characterDao.getById(1L) } returns CharacterEntity(id = 1L, boundEncyclopediaId = 3L)
        coEvery { characterDao.getById(2L) } returns CharacterEntity(id = 2L, boundEncyclopediaId = 4L)

        val result = useCase.create(
            characterIds = listOf(1L, 2L),
            encyclopediaId = 3L,
        )

        assertEquals(CreateSessionUseCase.Result.EncyclopediaMismatch, result)
        coVerify(exactly = 0) { transaction(any(), any(), any()) }
    }

    @Test
    fun optionsCreatesTemplateWorldAndOrderedParticipants() = runTest {
        coEvery { transaction(any(), any(), any()) } returns 9L
        coEvery { characterDao.getById(1L) } returns CharacterEntity(id = 1L, boundEncyclopediaId = 3L)
        coEvery { characterDao.getById(2L) } returns CharacterEntity(id = 2L, boundEncyclopediaId = 3L)
        val template = WorldTemplateEntity(
            templateId = " wuxia ",
            gameplayMode = "江湖",
            worldPrompt = "门派林立",
        )

        val result = useCase.create(
            title = "  新故事 ",
            template = template,
            encyclopediaId = 3L,
            characterIds = listOf(2L, 1L, 2L),
        )

        assertTrue(result is CreateSessionUseCase.Result.Created)
        coVerify {
            transaction(
                match { it.title == "新故事" },
                match {
                    it.sessionId == 0L && it.templateId == "wuxia" &&
                        it.encyclopediaId == 3L && it.worldPrompt == "门派林立"
                },
                match {
                    it.map { participant -> participant.characterId } == listOf(2L, 1L) &&
                        it.map { participant -> participant.sortOrder } == listOf(0, 1)
                },
            )
        }
    }

    @Test
    fun missingDefaultTemplateFallsBackToHonestCustomWorld() = runTest {
        coEvery { transaction(any(), any(), any()) } returns 10L
        every { secureStorage.defaultWorldTemplateId } returns "deleted-world"
        coEvery { worldTemplateDao.getByTemplateId("deleted-world") } returns null

        val result = useCase.createBlank()

        assertEquals(CreateSessionUseCase.Result.Created(10L), result)
        coVerify {
            transaction(
                any(),
                match { it.templateId == "custom" && it.worldPrompt.isEmpty() },
                match { it.isEmpty() },
            )
        }
    }
    @Test
    fun mappedWorldOpeningUsesCanonicalBackgroundWithoutLegacyLore() = runTest {
        val template = WorldTemplateEntity(id = 3, templateId = "old-world", worldPrompt = "旧背景")
        coEvery { mappingDao.getByTemplateId(3) } returns com.mojing.app.data.local.entity.LegacyWorldMappingEntity(3, 5, "source")
        coEvery { encyclopediaDao.getById(5) } returns com.mojing.app.data.local.entity.EncyclopediaEntity(id = 5, name = "统一世界", worldPrompt = "当前背景")
        useCase.createBlank(template = template)
        coVerify { transaction(any(), match { it.encyclopediaId == 5L && it.templateId == "custom" && it.worldPrompt == "当前背景" }, any()) }
    }

    @Test
    fun restoredStoryKeepsItsSubmittedBackground() = runTest {
        val template = WorldTemplateEntity(id = 3, templateId = "old-world", worldPrompt = "旧背景")
        coEvery { mappingDao.getByTemplateId(3) } returns com.mojing.app.data.local.entity.LegacyWorldMappingEntity(3, 5, "source")
        coEvery { encyclopediaDao.getById(5) } returns com.mojing.app.data.local.entity.EncyclopediaEntity(id = 5, worldPrompt = "后来修改")
        useCase.create(template = template, allowNoParticipants = true, worldPromptOverride = "提交时的背景")
        coVerify { transaction(any(), match { it.worldPrompt == "提交时的背景" }, any(), any(), any()) }
    }
}
