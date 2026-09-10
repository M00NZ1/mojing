package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ImagePromptDialogTest {
    @get:Rule val rule = createComposeRule()
    @Test fun longPromptKeepsActionsVisibleAndBusyPreventsDuplicateGeneration() {
        var prompt by mutableStateOf("  ")
        var busy by mutableStateOf(false)
        var generates = 0
        var dismisses = 0
        rule.setContent { MaterialTheme {
            ImagePromptDialog(prompt, { prompt = it }, busy, { dismisses++ }, { generates++ })
        } }
        rule.onNodeWithText("生成并加入对话").assertIsNotEnabled()
        rule.runOnIdle { prompt = "细雨落在城市街头。".repeat(400) }
        rule.onNodeWithText("生成并加入对话").assertIsDisplayed().performClick()
        rule.runOnIdle { busy = true }
        rule.onNodeWithText("正在生成，请稍候").assertIsNotEnabled()
        rule.onNodeWithText("返回对话").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, generates); assertEquals(1, dismisses) }
    }
}
