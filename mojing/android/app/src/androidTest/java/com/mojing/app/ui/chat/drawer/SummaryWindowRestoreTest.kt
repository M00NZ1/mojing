package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SummaryWindowRestoreTest {
    @get:Rule val rule=createComposeRule()
    private fun rows(branch:String="child")=(84L downTo 5L).map { SessionMemorySegmentEntity(id=it,sessionId=9,branchId=branch,summary="摘要$it") }
    @Test fun deepAnchorWaitsForReadyAndDelayedRows() {
        val ready=mutableStateOf(true);val loaded=mutableStateOf(true);var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { MemoryTab(segments=if(!restoring || loaded.value) rows() else emptyList(),
            corrections=emptyList(),promptTrace=null,currentBranchId=if(restoring && !ready.value) "main" else "child",
            isGenerating=false,onRebuildContextMemory={},onClearContextMemory={},onJumpToSource={},onAddCorrection={_,_->},onEditCorrection={},onDeleteCorrection={},onContinueStorySummary={},onStopStorySummary={},
            sessionReady=!restoring || ready.value,summariesLoaded=!restoring || loaded.value) } }
        rule.onNodeWithText("自动摘要").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("摘要10"))
        val before=rule.onNodeWithText("摘要10").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { ready.value=false;loaded.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("摘要10").assertDoesNotExist()
        rule.runOnIdle { ready.value=true };rule.onNodeWithText("摘要10").assertDoesNotExist()
        rule.runOnIdle { loaded.value=true };rule.onNodeWithText("摘要10").assertIsDisplayed()
        assertEquals(before,rule.onNodeWithText("摘要10").fetchSemanticsNode().boundsInRoot.top,2f)
    }
    @Test fun branchChangeStartsAtFirstRow() {
        val branch=mutableStateOf("child")
        rule.setContent { MaterialTheme { MemoryTab(segments=rows(branch.value),corrections=emptyList(),promptTrace=null,currentBranchId=branch.value,
            isGenerating=false,onRebuildContextMemory={},onClearContextMemory={},onJumpToSource={},onAddCorrection={_,_->},onEditCorrection={},onDeleteCorrection={},onContinueStorySummary={},onStopStorySummary={}) } }
        rule.onNodeWithText("自动摘要").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("摘要10"))
        rule.runOnIdle { branch.value="main" }
        rule.onNodeWithText("摘要10").assertDoesNotExist()
    }
}
