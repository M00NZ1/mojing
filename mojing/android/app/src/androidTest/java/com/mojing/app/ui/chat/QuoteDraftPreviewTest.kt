package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class QuoteDraftPreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun longQuoteKeepsCancelReachableOnNarrowScreen() {
        val shown = mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    if (shown.value) QuoteDraftPreview("雾港的长篇回复。".repeat(100)) { shown.value = false }
                }
            }
        }
        rule.onNodeWithText("引用回复").assertIsDisplayed()
        rule.onNodeWithContentDescription("取消引用").assertIsDisplayed().performClick()
        rule.onNodeWithText("引用回复").assertDoesNotExist()
    }
}
