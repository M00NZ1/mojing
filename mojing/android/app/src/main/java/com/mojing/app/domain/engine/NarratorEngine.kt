package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.SessionWorldEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NarratorEngine @Inject constructor() {

    fun buildNarratorPrompt(world: SessionWorldEntity): String {
        val parts = mutableListOf<String>()
        parts.add("你是旁白「${world.narratorName}」，负责场景描述和剧情推进。")
        parts.add("不要扮演具体角色，不要进行角色对话。")
        parts.add("根据对话最新进展，适时描述环境变化、气氛转变、事件发生。")
        parts.add("输出格式：<NARRATION>旁白内容</NARRATION>")
        if (world.worldPrompt.isNotBlank()) {
            parts.add("世界观背景：${world.worldPrompt}")
        }
        return parts.joinToString("\n\n")
    }

    fun shouldNarrate(
        world: SessionWorldEntity?,
        messagesSinceLastNarration: Int
    ): Boolean {
        if (world == null) return false
        if (!world.narratorEnabled) return false
        return messagesSinceLastNarration >= 4
    }
}
