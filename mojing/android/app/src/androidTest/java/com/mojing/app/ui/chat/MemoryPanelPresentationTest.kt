package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.ui.chat.drawer.ExpandableMemoryText
import com.mojing.app.ui.chat.drawer.MemoryTab
import org.junit.Rule
import org.junit.Test

class MemoryPanelPresentationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun correctionAndSummaryWithSameIdRemainBrowsable() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).height(600.dp)) {
                    MemoryTab(
                        segments = listOf(SessionMemorySegmentEntity(id = 1L, sessionId = 1L, summary = "码头相遇")),
                        corrections = listOf(SessionMemoryCorrectionEntity(id = 1L, sessionId = 1L, content = "角色不知道秘密")),
                        promptTrace = null, currentBranchId = "main", isGenerating = false,
                        onRebuildContextMemory = {}, onClearContextMemory = { _ -> }, onJumpToSource = {},
                        onAddCorrection = { _, _ -> }, onEditCorrection = {}, onDeleteCorrection = {},
                        onContinueStorySummary = {}, onStopStorySummary = {},
                    )
                }
            }
        }
        rule.onNodeWithText("用户纠正 1").performScrollTo().performClick()
        rule.onNodeWithText("角色不知道秘密").assertIsDisplayed()
        rule.onNodeWithText("本故事线尚未构造可追踪的角色或旁白请求").assertDoesNotExist()
        rule.onNodeWithText("自动摘要").performScrollTo().performClick()
        rule.onNodeWithText("码头相遇").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("纠正这段记忆").assertExists()
        rule.onNodeWithText("角色不知道秘密").assertDoesNotExist()
        rule.onNodeWithText("长期记忆", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("重建记忆").assertIsDisplayed()
    }

    @Test fun longTextExpandsAndShortTextNeedsNoExtraButton() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(260.dp)) {
                    ExpandableMemoryText("短句")
                    ExpandableMemoryText("长篇记忆内容\n".repeat(10))
                }
            }
        }
        rule.onAllNodesWithText("展开全文").assertCountEquals(1)
        rule.onNodeWithText("展开全文").performClick()
        rule.onNodeWithText("收起").assertExists().performClick()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
    }
}
