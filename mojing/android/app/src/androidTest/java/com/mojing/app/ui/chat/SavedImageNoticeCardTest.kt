package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SavedImageNoticeCardTest {
    @get:Rule val rule = createComposeRule()
    @Test fun savedResultHasVisibleLocalActionAndBlocksDuplicateReads() {
        var busy by mutableStateOf(false)
        var opens = 0
        rule.setContent { MaterialTheme { SavedImageNoticeCard(busy) { opens++ } } }
        rule.onNodeWithText("配图已保存，列表暂未刷新").assertIsDisplayed()
        rule.onNodeWithText("查看配图").assertIsDisplayed().performClick()
        rule.runOnIdle { busy = true }
        rule.onNodeWithText("请稍候…").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, opens) }
    }
}
