package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookmarkReadOnlyRestoreTest {
    @get:Rule val rule=createComposeRule()
    private val text=(1..70).joinToString("\n\n") { "第${it}段：灯塔守望者留下旧版约定，故人沿着原来的故事前行。" }
    private fun scrollValue()=rule.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scrollDown() {
        rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,1400f) }
        rule.waitForIdle();assertTrue(scrollValue()>100f)
    }
    @Test fun sameTargetWaitsThroughDelayedBodyAndRetryWithoutClampingPosition() {
        val body=mutableStateOf<String?>(text);val error=mutableStateOf<String?>(null)
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { BookmarkReadOnlyDialog(9,"child",50,body.value,"主线",body.value==null && error.value==null,error.value,{},{},{}) } }
        scrollDown();val before=scrollValue()
        rule.runOnIdle { body.value=null };tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("正在读取收藏原文…").assertIsDisplayed()
        rule.runOnIdle { error.value="读取失败" };rule.onNodeWithText("重试读取").assertIsEnabled()
        rule.runOnIdle { body.value=text;error.value=null }
        assertEquals(before,scrollValue(),1f)
    }
    @Test fun newTargetStartsAtBeginning() {
        val id=mutableStateOf(50L)
        rule.setContent { MaterialTheme { BookmarkReadOnlyDialog(9,"main",id.value,text,"主线",false,null,{},{},{}) } }
        scrollDown();rule.runOnIdle { id.value=51L };assertEquals(0f,scrollValue(),1f)
    }
    @Test fun actualReadingLineAndSessionHaveIndependentPosition() {
        val line=mutableStateOf("main");val sid=mutableStateOf(9L)
        rule.setContent { MaterialTheme { BookmarkReadOnlyDialog(sid.value,line.value,50,text,"主线",false,null,{},{},{}) } }
        scrollDown();rule.runOnIdle { line.value="child" };assertEquals(0f,scrollValue(),1f)
        scrollDown();rule.runOnIdle { sid.value=10L };assertEquals(0f,scrollValue(),1f)
    }
    @Test fun closeAndReopenSameTargetStartsAtBeginning() {
        val open=mutableStateOf(true)
        rule.setContent { MaterialTheme { if(open.value) BookmarkReadOnlyDialog(9,"main",50,text,"主线",false,null,{open.value=false},{},{}) } }
        scrollDown();rule.onNodeWithText("关闭").performClick();rule.waitForIdle()
        rule.runOnIdle { open.value=true };assertEquals(0f,scrollValue(),1f)
    }
}
