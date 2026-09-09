package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.ui.chat.drawer.TimelineTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TimelineTabTest {
    @get:Rule val rule = createComposeRule()

    @Test fun eventTextCanExpandAndDeletionRequiresConfirmation() {
        val deleted = mutableListOf<Long>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    TimelineTab(
                        events = listOf(SessionEventNodeEntity(id = 7L, sessionId = 1L,
                            title = "灯塔来信", description = "需要跟进的剧情线索\n".repeat(6))),
                        onToggleResolved = {}, onDelete = { deleted.add(it) }, onJumpToSource = {})
                }
            }
        }
        rule.onNodeWithText("展开全文").assertIsDisplayed().performClick()
        rule.onNodeWithText("收起").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("删除事件").performScrollTo().performClick()
        rule.onNodeWithText("删除这条事件？").assertIsDisplayed()
        rule.runOnIdle { assertTrue(deleted.isEmpty()) }
        rule.onNodeWithText("保留事件").performClick()
        rule.runOnIdle { assertTrue(deleted.isEmpty()) }
        rule.onNodeWithContentDescription("删除事件").performClick()
        rule.onNodeWithText("删除事件").performClick()
        rule.runOnIdle { assertEquals(listOf(7L), deleted) }
    }
}
