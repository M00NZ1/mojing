package com.mojing.app.ui.chat.drawer

internal fun participantDisplayName(
    characterId: Long,
    characterNames: Map<Long, String>,
): String = characterNames[characterId]?.trim().orEmpty().ifBlank { "角色资料不可用" }

internal fun participantSpeakerStrategyLabel(strategy: String): String = when (strategy.trim().lowercase()) {
    "", "natural" -> "自然发言"
    "list" -> "按顺序发言"
    "pooled" -> "均衡发言"
    else -> "自定义发言"
}
