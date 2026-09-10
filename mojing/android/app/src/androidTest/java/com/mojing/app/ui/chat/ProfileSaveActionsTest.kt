package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.settings.ProfileSaveActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileSaveActionsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun savingFailureRetryAndSuccessHaveDistinctActions() {
        var saving by mutableStateOf(true)
        var dirty by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var saves = 0
        rule.setContent { MaterialTheme { ProfileSaveActions(saving, dirty, true, error) { saves++ } } }
        rule.onNodeWithText("正在保存…").assertIsNotEnabled()
        rule.runOnIdle { saving = false; error = "资料未保存，请重试" }
        rule.onNodeWithText("资料未保存，请重试").assertIsDisplayed()
        rule.onNodeWithText("保存资料").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, saves); dirty = false }
        rule.onNodeWithText("已保存").assertIsNotEnabled()
        rule.onNodeWithText("资料未保存，请重试").assertDoesNotExist()
        rule.runOnIdle { dirty = true; error = null }
        rule.onNodeWithText("保存资料").assertIsEnabled()
    }
}
