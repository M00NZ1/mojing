package com.mojing.app.ui.chat

import com.mojing.app.domain.engine.UniversalContextMemoryUpdateResult

enum class ContextMemoryStatus(val message: String) {
    IDLE(""),
    UPDATED("已更新当前故事线的记忆"),
    FAILED("本次记忆整理未完成，可以点击重建记忆重试。"),
    DEFERRED("自动整理暂缓，稍后继续对话时重试；也可以立即重建记忆。"),
    REBUILD_REQUIRED("待整理剧情较多，请点击重建记忆。"),
}

/** 后台整理结果只更新所属故事线，不覆盖聊天请求本身的错误。 */
internal fun ChatContract.State.withContextMemoryResult(
    branchId: String,
    result: UniversalContextMemoryUpdateResult,
    memoryText: String,
): ChatContract.State {
    if (currentBranchId != branchId || result == UniversalContextMemoryUpdateResult.SUPERSEDED) return this
    val status = when (result) {
        UniversalContextMemoryUpdateResult.UPDATED -> ContextMemoryStatus.UPDATED
        UniversalContextMemoryUpdateResult.FAILED -> ContextMemoryStatus.FAILED
        UniversalContextMemoryUpdateResult.DEFERRED -> ContextMemoryStatus.DEFERRED
        UniversalContextMemoryUpdateResult.REQUIRES_FULL_REBUILD -> ContextMemoryStatus.REBUILD_REQUIRED
        else -> contextMemoryStatus
    }
    return copy(contextMemoryText = memoryText, contextMemoryStatus = status)
}
