package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import org.junit.Rule
import org.junit.Test

class SummaryReaderRestoreTest {
    @get:Rule val rule=createComposeRule()
    private val text=(1..8).joinToString("\n") { "第${it}段：来信仍遵守约定。" }
    private val summaryRow=SessionMemorySegmentEntity(id=71,sessionId=9,branchId="child",summary=text)
    @Composable private fun memory(rows:List<SessionMemorySegmentEntity>,branch:String="child",ready:Boolean=true,loaded:Boolean=true) {
        MaterialTheme { MemoryTab(segments=rows,corrections=emptyList(),promptTrace=null,currentBranchId=branch,
            isGenerating=false,onRebuildContextMemory={},onClearContextMemory={},onJumpToSource={},
            onAddCorrection={_,_->},onEditCorrection={},onDeleteCorrection={},onContinueStorySummary={},
            onStopStorySummary={},sessionReady=ready,summariesLoaded=loaded) }
    }
    private fun open() { rule.onNodeWithText("自动摘要").performClick() }

    @Test fun expandedAndCollapsedSummaryRestore() {
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(summaryRow)) };open()
        rule.onNodeWithText("展开全文").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertIsDisplayed().performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun temporaryMainAndDelayedSummaryDoNotConsumeIntent() {
        val ready=mutableStateOf(true);val loaded=mutableStateOf(true);var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(if(!restoring || loaded.value) listOf(summaryRow) else emptyList(),
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
        tester.setContent { memory(listOf(summaryRow),branch=if(restoring && !ready.value) "main" else "child",ready=!restoring || ready.value) }
        open();rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { ready.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("收起").assertIsDisplayed()
    }

    @Test fun changedSummaryTextDuringRecreationCollapses() {
        var row=summaryRow
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(row)) };open()
        rule.onNodeWithText("展开全文").performClick()
        row=summaryRow.copy(summary=text.replace("来信","新信"))
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun changedStableIdOrSessionCannotReuseExpansion() {
        var row=summaryRow
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(row)) };open()
        listOf(summaryRow.copy(id=72),summaryRow.copy(id=72,sessionId=10)).forEach { next ->
            rule.onNodeWithText("展开全文").performClick();row=next
            tester.emulateSavedInstanceStateRestore()
            rule.onNodeWithText("展开全文").assertIsDisplayed()
            rule.onNodeWithText("收起").assertDoesNotExist()
        }
    }
    @Test fun changedCurrentBranchCannotReuseInheritedSummaryExpansion() {
        var branch="child"
        val tester=StateRestorationTester(rule)
        tester.setContent { memory(listOf(summaryRow.copy(branchId="main")),branch=branch) };open()
        rule.onNodeWithText("展开全文").performClick()
        branch="another-child"
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertDoesNotExist()
        open()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
    }

}
