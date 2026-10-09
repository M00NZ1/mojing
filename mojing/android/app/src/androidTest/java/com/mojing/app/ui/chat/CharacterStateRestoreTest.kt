package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CharacterStateRestoreTest {
    @get:Rule val rule = createComposeRule()
    private fun restore(completes: Boolean) {
        val original = CharacterStatePanel(42L, 7L, "main", "主线", loading = false,
            state = CharacterStateDisplay("平静", "", "", emptyList(), emptyList(), emptyList()))
        val panel = mutableStateOf(original)
        var calls = 0
        val tester = StateRestorationTester(rule)
        tester.setContent { MaterialTheme { CharacterStateSheet(panel.value, "来信者", {}, {}, {
            calls++; panel.value = panel.value.copy(clearing = true)
        }) } }
        rule.onNodeWithText("清除当前状态").performClick()
        rule.onNodeWithText("确认清除").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("正在清除…").assertIsDisplayed().assertIsNotEnabled()
        rule.runOnIdle { panel.value = if (completes) original.copy(state = null) else original.copy(error = "清除失败，内容已保留，请重试") }
        rule.waitForIdle()
        if (completes) {
            rule.onNodeWithText("当前还没有自动角色状态。对话达到现有自动更新节奏后会更新。").assertIsDisplayed()
            rule.onNodeWithText("清除当前状态").assertDoesNotExist()
        } else {
            rule.onNodeWithText("清除失败，内容已保留，请重试").assertIsDisplayed()
            rule.onNodeWithText("清除当前状态").assertIsEnabled()
        }
        rule.onNodeWithText("正在清除…").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun pendingRestoresAndReadbackShowsEmptyState() = restore(true)
    @Test fun pendingRestoresAndFailureKeepsClearAvailable() = restore(false)
}
