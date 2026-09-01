package com.mojing.app.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.domain.engine.PromptBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderUniversalMemoryTest {
    @Test
    fun buildForCharacterIncludesUniversalContextMemoryText() {
        val builder = PromptBuilder()
        val prompt = builder.buildForCharacter(
            PromptBuilder.PromptContext(
                character = CharacterEntity(name = "角色A", personaPrompt = "你是角色A"),
                personaName = "玩家",
                universalContextMemoryText = "【通用高密度剧情记忆】\n- 不得误判关系",
            ),
        )

        assertTrue(prompt.contains("【通用高密度剧情记忆】"))
        assertTrue(prompt.contains("不得误判关系"))
    }
}
