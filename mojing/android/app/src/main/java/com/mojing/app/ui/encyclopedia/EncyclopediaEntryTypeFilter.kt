package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.local.entity.EncyclopediaEntity

/** 百科详情「条目」Tab 顶栏类型筛选（含「全部」） */
val ENTRY_TYPES = listOf(
    "全部" to "",
    "🌍 世界" to "world",
    "👤 角色" to "character",
    "🏛️ 阵营" to "faction",
    "📍 地点" to "location",
    "🗡️ 物品" to "item",
    "⚡ 事件" to "event",
    "🎯 技能" to "skill",
    "🐉 生物" to "creature",
    "💼 职业" to "profession",
    "💡 概念" to "concept",
    "⏱️ 时间线" to "timeline",
)

/** 条目编辑页「类型」下拉（无「全部」） */
val ENTRY_TYPE_OPTIONS = listOf(
    "🌍 世界" to "world", "👤 角色" to "character", "🏛️ 阵营" to "faction",
    "📍 地点" to "location", "🗡️ 物品" to "item", "⚡ 事件" to "event",
    "🎯 技能" to "skill", "🐉 生物" to "creature", "💼 职业" to "profession", "💡 概念" to "concept",
    "⏱️ 时间线" to "timeline",
)

/**
 * 按百科名称/简介/题材弱化易串玄幻的类目（官场、都市等不突出「技能 / 生物 / 法宝」类）。
 * 不删已有条目，只收紧筛选与新建时的选项。
 */
fun filteredEntryTypeTabs(encyclopedia: EncyclopediaEntity?): List<Pair<String, String>> {
    val hint = "${encyclopedia?.name.orEmpty()} ${encyclopedia?.description.orEmpty()} ${encyclopedia?.genreTags.orEmpty()}"
        .lowercase()
    val bureaucraticOrModern = listOf(
        "官场", "权谋", "吏治", "制度", "都市", "校园", "悬疑", "现实", "职场", "民国",
    ).any { hint.contains(it) }
    if (!bureaucraticOrModern) return ENTRY_TYPES
    val drop = setOf("skill", "creature", "item")
    return ENTRY_TYPES.filter { (_, type) -> type !in drop }
}

fun filteredEntryTypeOptions(encyclopediaHint: String): List<Pair<String, String>> {
    val hint = encyclopediaHint.lowercase()
    val bureaucraticOrModern = listOf(
        "官场", "权谋", "吏治", "制度", "都市", "校园", "悬疑", "现实", "职场", "民国",
    ).any { hint.contains(it) }
    if (!bureaucraticOrModern) return ENTRY_TYPE_OPTIONS
    val drop = setOf("skill", "creature", "item")
    return ENTRY_TYPE_OPTIONS.filter { (_, type) -> type !in drop }
}
