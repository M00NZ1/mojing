package com.mojing.app.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.domain.engine.PromptBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class NarratorPromptGuidanceTest {
    @Test
    fun buildNarratorPromptIncludesGuidanceAndMemory() {
        val builder = PromptBuilder()
        val prompt = builder.buildNarratorPrompt(
            context = PromptBuilder.PromptContext(
                character = CharacterEntity(name = "旁白"),
                world = SessionWorldEntity(sessionId = 1L, worldPrompt = "世界设定", narratorName = "旁白"),
                universalContextMemoryText = "【通用高密度剧情记忆】\n- 关系不能漂移",
                encyclopediaHits = listOf("[旧城] 城门在午夜关闭"),
                loreHits = listOf("[禁令] 雨夜不得点燃蓝灯"),
            ),
            guidance = "让剧情推进到夜晚发现异响",
        )

        assertTrue(prompt.contains("【用户给旁白的大概剧情方向】"))
        assertTrue(prompt.contains("让剧情推进到夜晚发现异响"))
        assertTrue(prompt.contains("【通用高密度剧情记忆】"))
        assertTrue(prompt.contains("世界观背景：世界设定"))
        assertTrue(prompt.contains("相关百科信息：\n[旧城] 城门在午夜关闭"))
        assertTrue(prompt.contains("相关设定信息：\n[禁令] 雨夜不得点燃蓝灯"))
    }
}
