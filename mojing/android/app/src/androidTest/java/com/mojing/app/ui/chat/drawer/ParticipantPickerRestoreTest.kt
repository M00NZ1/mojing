package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.ui.chat.AddParticipantPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ParticipantPickerRestoreTest {
    @get:Rule val rule = createComposeRule()
    private fun row(id: Long, name: String) = NewSessionCharacterOption(id,name,0,false,10)

    @Test fun searchRestoresAndReloadsStableIdsWithoutSelecting() {
        val tester=StateRestorationTester(rule)
        val queries=mutableListOf<String>()
        var selected=0L
        tester.setContent { MaterialTheme { AddParticipantDialog(
            loadPage={q,_->queries.add(q);AddParticipantPage(listOf(row(19,"候选$q")),false)},
            isSubmitting=false,submitError=null,onDismiss={},onSelect={selected=it}) } }
        rule.onNode(hasSetTextAction()).performTextInput("雨港")
        rule.waitUntil(5000) { queries.lastOrNull()=="雨港" }
        tester.emulateSavedInstanceStateRestore()
        rule.onNode(hasSetTextAction()).assertTextContains("雨港")
        rule.waitUntil(5000) { queries.count { it=="雨港" }>=2 }
        rule.onNodeWithText("候选雨港").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0L,selected) }
        rule.onNodeWithText("候选雨港").performClick()
        rule.runOnIdle { assertEquals(19L,selected) }
    }

    @Test fun failedReadRestoresQueryAndRetryReloadsIt() {
        val tester=StateRestorationTester(rule)
        var fail=false
        val queries=mutableListOf<String>()
        tester.setContent { MaterialTheme { AddParticipantDialog(
            loadPage={q,_->queries.add(q);if(fail) error("synthetic read");AddParticipantPage(listOf(row(20,"雨港候选")),false)},
            isSubmitting=false,submitError=null,onDismiss={},onSelect={}) } }
        rule.waitForIdle();rule.runOnIdle { fail=true }
        rule.onNode(hasSetTextAction()).performTextInput("雨港")
        rule.waitUntil(5000) { queries.lastOrNull()=="雨港" }
        rule.onNodeWithText("角色读取失败").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        rule.onNode(hasSetTextAction()).assertTextContains("雨港")
        rule.waitUntil(5000) { queries.count { it=="雨港" }>=2 }
        rule.onNodeWithText("角色读取失败").assertIsDisplayed()
        rule.runOnIdle { fail=false }
        rule.onNodeWithText("重试").performClick()
        rule.waitUntil(5000) { queries.count { it=="雨港" }>=3 }
        rule.onNodeWithText("雨港候选").assertIsDisplayed()
    }

    @Test fun submittingBlocksSelectionAndDismissal() {
        val busy=mutableStateOf(false)
        var selections=0;var dismissals=0
        rule.setContent { MaterialTheme { AddParticipantDialog(
            loadPage={_,_->AddParticipantPage(listOf(row(21,"候选")),false)},
            isSubmitting=busy.value,submitError=null,onDismiss={dismissals++},onSelect={selections++;busy.value=true}) } }
        rule.onNodeWithText("候选").performClick()
        rule.onNodeWithText("候选").assertIsNotEnabled()
        rule.onNodeWithText("取消").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.runOnIdle { assertEquals(1,selections);assertEquals(0,dismissals) }
    }
}
