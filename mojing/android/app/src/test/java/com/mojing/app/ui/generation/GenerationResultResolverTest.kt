package com.mojing.app.ui.generation

import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.*
import com.mojing.app.domain.generation.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GenerationResultResolverTest {
    @Test fun resolvesExistingResultsByTaskKindAndRejectsDeletedOrWrongTargets() = runTest {
        val characters = mockk<CharacterDao>()
        val worlds = mockk<WorldTemplateDao>()
        val encyclopedias = mockk<EncyclopediaDao>()
        coEvery { characters.getById(11L) } returns CharacterEntity(id = 11L)
        coEvery { worlds.getById(12L) } returns WorldTemplateEntity(id = 12L, templateId = "test", label = "测试")
        coEvery { encyclopedias.getById(13L) } returns EncyclopediaEntity(id = 13L)
        val resolver = GenerationResultResolver(characters, worlds, encyclopedias)
        val task = GenerationTaskEntity(id = 1L, taskKind = GenerationTaskKinds.CHARACTER_PERSONA_AI,
            title = "部分完成", status = GenerationTaskStatus.FAILED, payloadJson = "{}", progressDone = 1,
            targetCharacterId = 11L, targetWorldTemplateId = 12L, targetEncyclopediaId = 13L)
        assertEquals(GenerationResultTarget.Character(11L), resolver.resolve(task))
        assertEquals(GenerationResultTarget.Character(11L), resolver.resolve(task.copy(resultJson = "")))
        assertEquals(GenerationResultTarget.World(12L), resolver.resolve(task.copy(taskKind = GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI)))
        assertEquals(GenerationResultTarget.Encyclopedia(13L), resolver.resolve(task.copy(taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES)))
        assertEquals(GenerationResultTarget.Encyclopedia(13L), resolver.resolve(task.copy(taskKind = GenerationTaskKinds.ENCYCLOPEDIA_META_FILL)))
        assertNull(resolver.resolve(task.copy(targetCharacterId = null)))
        assertNull(resolver.resolve(task.copy(targetCharacterId = -1L)))
        assertNull(resolver.resolve(task.copy(taskKind = "unsupported")))
        coEvery { characters.getById(11L) } returns null
        assertNull(resolver.resolve(task))
    }
}
