package com.mojing.app.data

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import org.junit.Assert.*
import org.junit.Test

class BuiltinCatalogUpgradeTest {
    @Test fun onlyExactUncustomizedCharactersQualifyForRetirement() {
        val seed = SeedDataManager.SeedCharacter("Sample", "Original", "#123456", 0.8f, 1200)
        val c = CharacterEntity(id = 3, name = seed.name, personaPrompt = seed.personaPrompt,
            avatarColor = seed.avatarColor, temperature = seed.temperature, maxTokens = seed.maxTokens)
        assertTrue(BuiltinCatalogUpgrade.characterUntouched(c, seed))
        listOf(c.copy(personaPrompt = "Edited"), c.copy(apiKey = "test-personal"), c.copy(favorite = true),
            c.copy(pinnedAt = 1), c.copy(cardImagePath = "my-cover.png"), c.copy(modelName = "own-model"))
            .forEach { assertFalse(BuiltinCatalogUpgrade.characterUntouched(it, seed)) }
    }

    @Test fun sameNameUserWorldAndEditedBuiltinsArePreserved() {
        val seed = SeedDataManager.SeedTemplate(templateId = "old", label = "World", worldPrompt = "Original")
        val t = WorldTemplateEntity(templateId = "old", label = "World", worldPrompt = "Original", isBuiltin = true)
        assertTrue(BuiltinCatalogUpgrade.templateUntouched(t, seed))
        listOf(t.copy(isBuiltin = false), t.copy(worldPrompt = "Edited"), t.copy(coverImagePath = "cover.png"),
            t.copy(suggestedChoicesJson = "[\"choice\"]"), t.copy(pinnedAt = 2)).forEach {
            assertFalse(BuiltinCatalogUpgrade.templateUntouched(it, seed))
        }
    }
}
