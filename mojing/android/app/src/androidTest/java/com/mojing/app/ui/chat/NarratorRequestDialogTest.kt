package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NarratorRequestDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun blankDirectionHasOneGenerationActionAndLongDirectionKeepsActionsReachable() {
        var guidance by mutableStateOf("   ")
        val requests = mutableListOf<String>()
        var dismissed = 0
        rule.setContent { MaterialTheme {
            NarratorRequestDialog(guidance, { guidance = it }, { dismissed++ }, { requests += it })
        } }
        rule.onNodeWithText("自动生成旁白").assertIsDisplayed().performClick()
        rule.onNodeWithText("不指定方向，自动生成").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(""), requests); guidance = "  " + "夜色中出现脚步声。".repeat(200) + "  " }
        rule.onNodeWithText("按此方向生成").assertIsDisplayed().performClick()
        rule.onNodeWithText("不指定方向，自动生成").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("关闭生成旁白").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(listOf("", guidance.trim(), ""), requests)
            assertEquals(1, dismissed)
        }
    }
}
