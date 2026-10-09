package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import org.junit.Rule
import org.junit.Test

class CorrectionReaderRestoreTest {
    @get:Rule val rule=createComposeRule()
    private val text=(1..8).joinToString("\n") { "第${it}段：来信仍遵守约定。" }
    private val correction=SessionMemoryCorrectionEntity(id=71,sessionId=9,branchId="child",content=text)
    @Composable private fun memory(rows:List<SessionMemoryCorrectionEntity>,branch:String="child",ready:Boolean=true,loaded:Boolean=true) {
        MaterialTheme { MemoryTab(segments=emptyList(),corrections=rows,promptTrace=null,currentBranchId=branch,
            isGenerating=false,onRebuildContextMemory={},onClearContextMemory={},onJumpToSource={},
            onAddCorrection={_,_->},onEditCorrection={},onDeleteCorrection={},onContinueStorySummary={},
            onStopStorySummary={},sessionReady=ready,correctionsLoaded=loaded) }
    }
    private fun open() { rule.onNodeWithText("用户纠正 1").performClick() }

    @Test fun expandedAndCollapsedCorrectionRestore() {
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(correction)) };open()
        rule.onNodeWithText("展开全文").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertIsDisplayed().performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun temporaryMainAndDelayedCorrectionDoNotConsumeIntent() {
        val ready=mutableStateOf(true);val loaded=mutableStateOf(true);var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(if(!restoring || loaded.value) listOf(correction) else emptyList(),
            branch=if(restoring && !ready.value) "main" else "child",
            ready=!restoring || ready.value,loaded=!restoring || loaded.value) }
        open();rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { ready.value=false;loaded.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { loaded.value=true }
        rule.onNodeWithText("收起").assertIsDisplayed()
    }

    @Test fun staleLoadedRowsDuringTemporaryMainDoNotConsumeIntent() {
        val ready=mutableStateOf(true);var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(correction),branch=if(restoring && !ready.value) "main" else "child",ready=!restoring || ready.value) }
        open();rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { ready.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("收起").assertIsDisplayed()
    }

    @Test fun changedCorrectionTextDuringRecreationCollapses() {
        var row=correction
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(row)) };open()
        rule.onNodeWithText("展开全文").performClick()
        row=correction.copy(content=text.replace("来信","新信"))
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun changedStableIdOrSessionCannotReuseExpansion() {
        var row=correction
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(row)) };open()
        listOf(correction.copy(id=72),correction.copy(id=72,sessionId=10)).forEach { next ->
            rule.onNodeWithText("展开全文").performClick();row=next
            tester.emulateSavedInstanceStateRestore()
            rule.onNodeWithText("展开全文").assertIsDisplayed()
            rule.onNodeWithText("收起").assertDoesNotExist()
        }
    }
}
