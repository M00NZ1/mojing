package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.SwipeRevealListRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SwipeRevealListRowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun revealKeepsContentAndReverseSwipeClosesWithoutDeleting() {
        var deletes = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    SwipeRevealListRow(true, isPinned = false, onPinToggle = {},
                        onDelete = { deletes++ }, onClick = {}, modifier = Modifier.testTag("row")) {
                        Box(Modifier.width(320.dp).height(72.dp)) { Text("当前会话") }
                    }
                }
            }
        }
        rule.onNodeWithTag("row").performTouchInput { swipeLeft() }
        rule.onNodeWithText("当前会话").assertIsDisplayed()
        rule.onNodeWithText("删除").assertIsDisplayed()
        rule.onNodeWithTag("row").performTouchInput {
            swipe(start = centerLeft + androidx.compose.ui.geometry.Offset(12f, 0f), end = centerRight)
        }
        rule.onNodeWithText("当前会话").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, deletes) }
    }
}
