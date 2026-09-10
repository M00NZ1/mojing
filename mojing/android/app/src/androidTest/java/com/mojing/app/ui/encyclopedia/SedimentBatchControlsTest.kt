package com.mojing.app.ui.encyclopedia

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SedimentBatchControlsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun selectionCountAndBusyStateControlConfirmation() {
        var selecting by mutableStateOf(false)
        var count by mutableStateOf(0)
        var busy by mutableStateOf(false)
        var confirmed = 0
        rule.setContent { MaterialTheme {
            SedimentBatchControls(selecting, count, busy, { selecting = !selecting }, { count = 100 }, { count = 0 }, { confirmed++; busy = true })
        } }
        rule.onNodeWithText("批量核对").performClick()
        rule.onNodeWithText("确认所选").assertIsNotEnabled()
        rule.onNodeWithText("选择前100条").performClick()
        rule.onNodeWithText("已选 100 条").assertIsDisplayed()
        rule.onNodeWithText("确认所选").assertIsDisplayed().performClick()
        rule.onNodeWithText("确认中…").assertIsNotEnabled()
        rule.onNodeWithText("结束选择").assertIsNotEnabled()
        rule.onNodeWithText("清空").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, confirmed); busy = false }
        rule.onNodeWithText("清空").performClick()
        rule.onNodeWithText("确认所选").assertIsNotEnabled()
    }
}
