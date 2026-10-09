package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.ui.chat.drawer.MemoryTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SummaryEditorRestoreTest {
    @get:Rule val rule = createComposeRule()
    @Test fun childDraftWaitsForReadyAndLoadedDataAndKeepsConflictBaseline() {
        val tester = StateRestorationTester(rule)
        val branch = mutableStateOf("child")
        val ready = mutableStateOf(true)
        val loaded = mutableStateOf(true)
        val original = SessionMemorySegmentEntity(id=42,sessionId=1,branchId="child",summary="原摘要")
        val rows = mutableStateOf(listOf(original))
        var saved: Pair<SessionMemorySegmentEntity,String>? = null
        tester.setContent {
            MaterialTheme { Box(Modifier.fillMaxSize()) {
                MemoryTab(segments=rows.value,corrections=emptyList(),promptTrace=null,
                    currentBranchId=branch.value,isGenerating=false,sessionReady=ready.value,
                    summariesLoaded=loaded.value,onRebuildContextMemory={},onClearContextMemory={},
                    onJumpToSource={},onAddCorrection={_,_->},onEditCorrection={},onDeleteCorrection={},
                    onContinueStorySummary={},onStopStorySummary={},
                    onEditMemorySummary={s,t,done->saved=s to t;done(true)})
            } }
        }
        rule.onNodeWithText("自动摘要").performScrollTo().performClick()
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("未保存输入")
        rule.runOnIdle { ready.value=false;loaded.value=false;branch.value="main";rows.value=emptyList() }
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("编辑自动摘要").assertDoesNotExist()
        rule.runOnIdle { branch.value="child";ready.value=true }
        rule.onNodeWithText("编辑自动摘要").assertDoesNotExist()
        rule.runOnIdle { rows.value=listOf(original.copy(summary="外部更新"));loaded.value=true }
        rule.onNodeWithText("编辑自动摘要").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertTextEquals("未保存输入")
        rule.onNodeWithText("保存",substring=false).performClick()
        rule.runOnIdle { assertEquals(original to "未保存输入",saved) }
        rule.onNodeWithText("编辑自动摘要").assertDoesNotExist()
        rule.runOnIdle { branch.value="other" }
        rule.onNodeWithText("重建记忆").assertIsDisplayed()
    }

    @Test fun olderTargetReadFailurePreservesInputAndRetriesOnlyOnRequest() {
        val tester=StateRestorationTester(rule)
        val original=SessionMemorySegmentEntity(id=42,sessionId=1,summary="较早摘要")
        val rows=mutableStateOf(listOf(original))
        var fail=true
        var reads=0
        var saved:Pair<SessionMemorySegmentEntity,String>?=null
        tester.setContent {
            MaterialTheme { Box(Modifier.fillMaxSize()) {
                MemoryTab(segments=rows.value,corrections=emptyList(),promptTrace=null,currentBranchId="main",
                    isGenerating=false,onRebuildContextMemory={},onClearContextMemory={},onJumpToSource={},
                    onAddCorrection={_,_->},onEditCorrection={},onDeleteCorrection={},onContinueStorySummary={},onStopStorySummary={},
                    onResolveMemorySummaryEditor={id,branch->
                        assertEquals(42L,id);assertEquals("main",branch);reads++
                        if(fail) error("synthetic read failure")
                        original.copy(summary="外部更新")
                    },onEditMemorySummary={s,t,done->saved=s to t;done(true)})
            } }
        }
        rule.onNodeWithText("自动摘要").performScrollTo().performClick()
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("保留输入")
        rule.runOnIdle { rows.value=emptyList() }
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("摘要读取失败，输入仍保留，请重试").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertTextEquals("保留输入")
        rule.onNodeWithText("保存").assertIsNotEnabled()
        val before=reads
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(before,reads);fail=false }
        rule.onNodeWithText("重试读取").performClick()
        rule.onNodeWithText("保存").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(before+1,reads);assertEquals(original to "保留输入",saved) }
    }

    @Test fun inheritedSummaryRemainsReadOnly() {
        rule.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            MemoryTab(segments=listOf(SessionMemorySegmentEntity(id=1,sessionId=1,branchId="main",summary="父线摘要")),
                corrections=emptyList(),promptTrace=null,currentBranchId="child",isGenerating=false,
                onRebuildContextMemory={},onClearContextMemory={},onJumpToSource={},onAddCorrection={_,_->},
                onEditCorrection={},onDeleteCorrection={},onContinueStorySummary={},onStopStorySummary={})
        } } }
        rule.onNodeWithText("自动摘要").performScrollTo().performClick()
        rule.onNodeWithText("编辑").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("删除").assertIsNotEnabled()
        rule.onNodeWithText("纠正这段记忆").assertIsEnabled()
    }
}
