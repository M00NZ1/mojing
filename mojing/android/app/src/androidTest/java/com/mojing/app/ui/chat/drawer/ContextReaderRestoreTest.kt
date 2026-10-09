package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class ContextReaderRestoreTest {
    @get:Rule val rule=createComposeRule()
    private val text=(1..10).joinToString("\n") { "第${it}段：来信仍遵守约定。" }
    @Composable private fun memory(body:String=text,branch:String="child",ready:Boolean=true,loaded:Boolean=true,sid:Long=9) {
        MaterialTheme { MemoryTab(segments=emptyList(),corrections=emptyList(),promptTrace=null,currentBranchId=branch,
            isGenerating=false,onRebuildContextMemory={},onClearContextMemory={},onJumpToSource={},
            onAddCorrection={_,_->},onEditCorrection={},onDeleteCorrection={},onContinueStorySummary={},
            onStopStorySummary={},sessionReady=ready,contextMemoryLoaded=loaded,contextMemoryText=body,sessionId=sid) }
    }

    @Test fun expandedAndCollapsedContextRestore() {
        val tester=StateRestorationTester(rule)
        tester.setContent { memory() }
        rule.onNodeWithText("展开全文").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertIsDisplayed().performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun temporaryMainAndDelayedBodyDoNotConsumeIntent() {
        val ready=mutableStateOf(true);val loaded=mutableStateOf(true);var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(body=if(!restoring || loaded.value) text else "",
            branch=if(restoring && !ready.value) "main" else "child",
            ready=!restoring || ready.value,loaded=!restoring || loaded.value) }
        rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { ready.value=false;loaded.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { loaded.value=true }
        rule.onNodeWithText("收起").assertIsDisplayed()
    }

    @Test fun staleLoadedBodyDuringTemporaryMainDoesNotConsumeIntent() {
        val ready=mutableStateOf(true);var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(branch=if(restoring && !ready.value) "main" else "child",ready=!restoring || ready.value) }
        rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { ready.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("收起").assertIsDisplayed()
    }

    @Test fun changedContextBodyDuringRecreationCollapses() {
        var body=text
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(body=body) }
        rule.onNodeWithText("展开全文").performClick();body=text.replace("来信","新信")
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun differentSessionCannotReuseSameBodyExpansion() {
        var sid=9L
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(sid=sid) }
        rule.onNodeWithText("展开全文").performClick();sid=10L
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun differentCurrentBranchCannotReuseSameBodyExpansion() {
        var branch="child"
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(branch=branch) }
        rule.onNodeWithText("展开全文").performClick();branch="another-child"
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }
}
