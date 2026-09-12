package com.mojing.app.ui.chat

import com.mojing.app.domain.engine.UniversalContextMemoryUpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ContextMemoryStatusTest {
    @Test
    fun lateBackgroundResultCannotReplaceAnotherBranch() {
        val state = ChatContract.State(currentBranchId = "branch-b", contextMemoryText = "分支 B 的约定")
        assertSame(state, state.withContextMemoryResult("main", UniversalContextMemoryUpdateResult.FAILED, ""))
    }

    @Test
    fun supersededUpdateCannotReplaceManualRebuild() {
        val state = ChatContract.State(contextMemoryText = "新整理的约定", contextMemoryStatus = ContextMemoryStatus.UPDATED)
        assertSame(state, state.withContextMemoryResult("main", UniversalContextMemoryUpdateResult.SUPERSEDED, "旧约定"))
    }

    @Test
    fun failureAndCooldownRemainVisibleWithoutReplacingChatError() {
        val state = ChatContract.State(contextMemoryText = "已有约定", error = "消息发送失败")
        val failed = state.withContextMemoryResult("main", UniversalContextMemoryUpdateResult.FAILED, "已有约定")
        assertEquals(ContextMemoryStatus.FAILED, failed.contextMemoryStatus)
        assertEquals("消息发送失败", failed.error)
        assertEquals("已有约定", failed.contextMemoryText)
        val deferred = failed.withContextMemoryResult("main", UniversalContextMemoryUpdateResult.DEFERRED, "已有约定")
        assertEquals(ContextMemoryStatus.DEFERRED, deferred.contextMemoryStatus)
        assertEquals(ContextMemoryStatus.UPDATED, deferred.withContextMemoryResult(
            "main", UniversalContextMemoryUpdateResult.UPDATED, "新的约定",
        ).contextMemoryStatus)
    }
}
