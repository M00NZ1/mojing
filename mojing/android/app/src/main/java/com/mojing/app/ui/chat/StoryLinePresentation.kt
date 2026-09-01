package com.mojing.app.ui.chat

internal fun storyLineDisplayLabel(branchId: String, label: String?): String {
    if (branchId == "main") return "主线剧情"
    return label?.trim().orEmpty().ifBlank { "未命名故事线" }
}

internal fun storyLineParentLabel(parentBranchId: String?, labelsById: Map<String, String>): String {
    val normalizedParentId = parentBranchId?.trim().orEmpty().ifBlank { "main" }
    return labelsById[normalizedParentId] ?: "上一条故事线"
}
