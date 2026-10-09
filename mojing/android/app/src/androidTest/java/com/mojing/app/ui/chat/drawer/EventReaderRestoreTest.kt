package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import org.junit.Rule
import org.junit.Test

class EventReaderRestoreTest {
    @get:Rule val rule = createComposeRule()
    private val text = (1..8).joinToString("\n") { "第${it}段：等待来信。" }
    private val event = SessionEventNodeEntity(id=71,sessionId=9,title="港口约定",description=text)

    @Test fun expandedAndCollapsedIntentRestore() {
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { ExpandableMemoryText(text,3,"event:9:main:71") } }
        rule.onNodeWithText("展开全文").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertIsDisplayed().performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun changedTextDuringRecreationDoesNotExpand() {
        var content=text
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { ExpandableMemoryText(content,3,"event:9:main:71") } }
        rule.onNodeWithText("展开全文").performClick()
        content=text.replace("来信","新信")
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun differentStableIdSessionAndBranchDoNotConsumeIntent() {
        var scope="event:9:main:71"
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { ExpandableMemoryText(text,3,scope) } }
        listOf("event:9:main:72","event:10:main:72","event:10:child:72").forEach { next ->
            rule.onNodeWithText("展开全文").performClick()
            scope=next
            tester.emulateSavedInstanceStateRestore()
            rule.onNodeWithText("展开全文").assertIsDisplayed()
            rule.onNodeWithText("收起").assertDoesNotExist()
        }
    }

    @Test fun temporaryMainAndDelayedRowsDoNotConsumeChildIntent() {
        val ready=mutableStateOf(true)
        val loaded=mutableStateOf(true)
        var restoring=false
        val child=event.copy(branchId="child")
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { TimelineTab(
            events=if(!restoring || loaded.value) listOf(child) else emptyList(),
            currentBranchId=if(restoring && !ready.value) "main" else "child",
            sessionReady=!restoring || ready.value,loaded=!restoring || loaded.value,
            onToggleResolved={},onDelete={},onJumpToSource={}) } }
        rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { ready.value=false;loaded.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { ready.value=true }
        rule.onNodeWithText("收起").assertDoesNotExist()
        rule.runOnIdle { loaded.value=true }
        rule.onNodeWithText("收起").assertIsDisplayed()
    }

    @Test fun liveContentAndScopeChangesReset() {
        val content=mutableStateOf(text)
        val scope=mutableStateOf("event:9:main:71")
        rule.setContent { MaterialTheme { ExpandableMemoryText(content.value,3,scope.value) } }
        rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { content.value=text.replace("来信","新信") }
        rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { scope.value="event:9:child:71" }
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }

    @Test fun defaultMemoryCorrectionSummaryAndFactTextStillResetOnChange() {
        val content=mutableStateOf(text)
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { ExpandableMemoryText(content.value,2) } }
        rule.onNodeWithText("展开全文").performClick()
        rule.runOnIdle { content.value=text.replace("来信","纠正") }
        rule.onNodeWithText("展开全文").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        rule.onNodeWithText("收起").assertDoesNotExist()
    }
}
