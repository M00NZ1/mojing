package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EventWindowRestoreTest {
    @get:Rule val rule=createComposeRule()
    private fun rows(range:LongProgression,branch:String="main")=range.map {
        SessionEventNodeEntity(id=it,sessionId=9,branchId=branch,title="事件$it",description="等待来信",createdAt=it)
    }

    @Test fun deepChildAnchorWaitsForReadyAndRowsOnRestoration() {
        val ready=mutableStateOf(true);val loaded=mutableStateOf(true)
        var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { TimelineTab(
            events=if(!restoring || loaded.value) rows(76L downTo 5L,"child") else emptyList(),
            currentBranchId=if(restoring && !ready.value) "main" else "child",
            sessionReady=!restoring || ready.value,loaded=!restoring || loaded.value,
            hasNewerEvents=true,onToggleResolved={},onDelete={},onJumpToSource={}) } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("事件10"))
        rule.onNodeWithText("事件10").assertIsDisplayed()
        val before=rule.onNodeWithText("事件10").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { ready.value=false;loaded.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("事件10").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("事件10").assertDoesNotExist()
        rule.runOnIdle { loaded.value=true }
        rule.onNodeWithText("事件10").assertIsDisplayed()
        val after=rule.onNodeWithText("事件10").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before,after,2f)
    }

    @Test fun recentActionResetsExistingListAndRestoresFirstRow() {
        val events=mutableStateOf(rows(76L downTo 5L));val newer=mutableStateOf(true)
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { TimelineTab(events=events.value,hasNewerEvents=newer.value,
            onResetWindow={ events.value=rows(100L downTo 77L);newer.value=false },
            onToggleResolved={},onDelete={},onJumpToSource={}) } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("事件10"))
        rule.onNodeWithText("事件10").assertIsDisplayed()
        rule.onNodeWithText("回到最近事件").performClick()
        rule.onNodeWithText("事件100").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("事件100").assertIsDisplayed()
    }

    @Test fun newQueryUsesFreshListPosition() {
        val query=mutableStateOf("")
        rule.setContent { MaterialTheme { TimelineTab(events=rows(76L downTo 5L),query=query.value,
            onToggleResolved={},onDelete={},onJumpToSource={}) } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("事件10"))
        rule.onNodeWithText("事件10").assertIsDisplayed()
        rule.runOnIdle { query.value="事件" }
        rule.onNodeWithText("事件76").assertIsDisplayed()
    }
}
