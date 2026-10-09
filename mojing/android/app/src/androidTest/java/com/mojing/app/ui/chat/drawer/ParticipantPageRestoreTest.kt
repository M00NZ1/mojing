package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.ui.chat.AddParticipantPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ParticipantPageRestoreTest {
    @get:Rule val rule=createComposeRule()
    private fun row(id:Long)=NewSessionCharacterOption(id,"候选$id",5,true,100)
    private fun page(cursor:NewSessionCharacterOption?)=if(cursor==null) AddParticipantPage((42L downTo 3L).map(::row),true) else AddParticipantPage(listOf(row(2),row(1)),false)

    @Test fun secondPageRestoresSortAnchorAndBothDirections() {
        val tester=StateRestorationTester(rule)
        val reads=mutableListOf<NewSessionCharacterOption?>()
        tester.setContent { MaterialTheme { AddParticipantDialog(loadPage={_,c->reads.add(c);page(c)},isSubmitting=false,submitError=null,onDismiss={},onSelect={}) } }
        rule.onNodeWithText("下一页").performClick()
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        rule.onNodeWithText("候选1").assertIsDisplayed()
        rule.runOnIdle { val c=reads.last()!!;assertEquals(3L,c.id);assertEquals(5L,c.pinnedAt);assertEquals(true,c.favorite);assertEquals(100L,c.createdAt);assertEquals("",c.name) }
        rule.onNodeWithText("上一页").performClick()
        rule.onNodeWithText("第 1 页").assertIsDisplayed()
        rule.onNodeWithText("下一页").performClick()
        rule.onNodeWithText("候选1").assertIsDisplayed()
    }

    @Test fun changedQueryRestartsAtFirstPageAcrossRecreation() {
        val tester=StateRestorationTester(rule)
        val reads=mutableListOf<Pair<String,Long?>>()
        tester.setContent { MaterialTheme { AddParticipantDialog(loadPage={q,c->reads.add(q to c?.id);page(c)},isSubmitting=false,submitError=null,onDismiss={},onSelect={}) } }
        rule.onNodeWithText("下一页").performClick()
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("雨港")
        rule.waitUntil(5000) { reads.lastOrNull()==("雨港" to null) }
        tester.emulateSavedInstanceStateRestore()
        rule.onNode(hasSetTextAction()).assertTextContains("雨港")
        rule.waitUntil(5000) { reads.count { it == ("雨港" to null) } >= 2 }
        rule.onNodeWithText("第 1 页").assertIsDisplayed()
        rule.runOnIdle { assertEquals("雨港" to null,reads.last()) }
    }

    @Test fun secondPageReadFailureRetryUsesRestoredAnchor() {
        val tester=StateRestorationTester(rule)
        var fail=false
        var last:Long?=null
        tester.setContent { MaterialTheme { AddParticipantDialog(loadPage={_,c->last=c?.id;if(fail && c!=null) error("synthetic");page(c)},isSubmitting=false,submitError=null,onDismiss={},onSelect={}) } }
        rule.onNodeWithText("下一页").performClick()
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        rule.runOnIdle { fail=true }
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("角色读取失败").assertIsDisplayed()
        rule.runOnIdle { assertEquals(3L,last);fail=false }
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        rule.onNodeWithText("候选1").assertIsDisplayed()
        rule.runOnIdle { assertEquals(3L,last) }
    }
}
