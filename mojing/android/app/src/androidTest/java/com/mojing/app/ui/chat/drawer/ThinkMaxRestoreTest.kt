package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionWorldEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThinkMaxRestoreTest {
    @get:Rule val rule = createComposeRule()
    private fun restore(completes: Boolean) {
        val choice = mutableStateOf(false)
        val saving = mutableStateOf(false)
        val failure = mutableStateOf<String?>(null)
        var calls = 0
        val tester = StateRestorationTester(rule)
        tester.setContent { MaterialTheme { WorldConfigTab(
            world = SessionWorldEntity(id = 9, sessionId = 42),
            onWorldSettingChanged = { _, _ -> }, onSaveSessionWorldCredentials = {}, onCredentialFieldsDirty = {},
            allowSessionThinkMax = true, sessionThinkMaxEnabled = choice.value,
            sessionThinkMaxSaving = saving.value, sessionThinkMaxSaveError = failure.value,
            onSessionThinkMax = { calls++; saving.value = true },
        ) } }
        rule.onNodeWithContentDescription("本会话思考/Max开关").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithContentDescription("本会话思考/Max开关").assertIsNotEnabled().assertIsOff()
        rule.onNodeWithText("正在保存…").assertIsDisplayed()
        rule.runOnIdle { saving.value = false; if(completes) choice.value = true else failure.value = "思考/Max 设置未保存，请重试" }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("本会话思考/Max开关").assertIsEnabled()
        if(completes) rule.onNodeWithContentDescription("本会话思考/Max开关").assertIsOn()
        else {
            rule.onNodeWithContentDescription("本会话思考/Max开关").assertIsOff()
            rule.onNodeWithText("重试保存").assertIsEnabled()
        }
        rule.onNodeWithText("正在保存…").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun pendingRestoresAndCommitShowsEnabledChoice() = restore(true)
    @Test fun pendingRestoresAndFailureKeepsPreviousChoice() = restore(false)
}
