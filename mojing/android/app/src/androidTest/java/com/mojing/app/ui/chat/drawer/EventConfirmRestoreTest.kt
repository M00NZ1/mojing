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

class EventConfirmRestoreTest {
    @get:Rule val rule = createComposeRule()
    private val event = SessionEventNodeEntity(id=71,sessionId=9,title="港口约定",description="等待来信")

    @Test fun sameTargetRestoresAndCancelDoesNotDelete() {
        var deleted:Long?=null
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { TimelineTab(events=listOf(event),
            onToggleResolved={},onDelete={ deleted=it },onJumpToSource={}) } }
        rule.onNodeWithContentDescription("删除事件").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("删除这条事件？").assertIsDisplayed()
        rule.onNodeWithText("保留事件").performClick()
        rule.runOnIdle { assertEquals(null,deleted) }
        rule.onNodeWithText("删除这条事件？").assertDoesNotExist()
        rule.onNodeWithContentDescription("删除事件").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("删除事件").performClick()
        rule.runOnIdle { assertEquals(event.id,deleted) }
    }

    @Test fun delayedRowsRestoreConfirmationOnlyAfterReady() {
        val ready=mutableStateOf(true)
        var restoring=false
        var deleted:Long?=null
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { TimelineTab(
            events=if(!restoring || ready.value) listOf(event) else emptyList(),
            loaded=!restoring || ready.value,
            onToggleResolved={},onDelete={ deleted=it },onJumpToSource={}) } }
        rule.onNodeWithContentDescription("删除事件").performClick()
        rule.runOnIdle { ready.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("删除这条事件？").assertDoesNotExist()
        rule.onNodeWithText("正在加载当前故事线事件…").assertIsDisplayed()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("删除这条事件？").assertIsDisplayed()
        rule.onNodeWithText("删除事件").performClick()
        rule.runOnIdle { assertEquals(event.id,deleted) }
    }

    @Test fun missingOrOtherBranchTargetDoesNotRestore() {
        val events=mutableStateOf(listOf(event))
        val branch=mutableStateOf("main")
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { TimelineTab(events=events.value,currentBranchId=branch.value,
            onToggleResolved={},onDelete={ error("Invalid target deleted") },onJumpToSource={}) } }
        rule.onNodeWithContentDescription("删除事件").performClick()
        rule.runOnIdle { events.value=emptyList() }
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("删除这条事件？").assertDoesNotExist()
        rule.runOnIdle { events.value=listOf(event) }
        rule.onNodeWithContentDescription("删除事件").performClick()
        rule.runOnIdle { branch.value="child" }
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("删除这条事件？").assertDoesNotExist()
        rule.onNodeWithContentDescription("删除事件").assertIsNotEnabled()
    }

    @Test fun initialTemporaryMainBranchDoesNotConsumeChildConfirmation() {
        val ready=mutableStateOf(true)
        val loaded=mutableStateOf(true)
        var restoring=false
        val childEvent=event.copy(branchId="child")
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { TimelineTab(
            events=if(!restoring || loaded.value) listOf(childEvent) else emptyList(),
            currentBranchId=if(restoring && !ready.value) "main" else "child",
            sessionReady=!restoring || ready.value, loaded=!restoring || loaded.value,
            onToggleResolved={},onDelete={},onJumpToSource={}) } }
        rule.onNodeWithContentDescription("删除事件").performClick()
        rule.runOnIdle { ready.value=false;loaded.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("删除这条事件？").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("删除这条事件？").assertDoesNotExist()
        rule.runOnIdle { loaded.value=true }
        rule.onNodeWithText("删除这条事件？").assertIsDisplayed()
        rule.onNodeWithText("保留事件").performClick()
    }
}
