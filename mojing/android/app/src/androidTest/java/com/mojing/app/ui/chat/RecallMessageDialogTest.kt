package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.dao.MessageRecallImpact
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecallMessageDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recallExplainsSummaryRebuildWithoutChangingDataBeforeConfirmation() {
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                RecallMessageDialog("原文", { MessageRecallImpact(true, affectedSummaryCount = 3) }, { deletes++ }, {})
            }
        }
        compose.onNodeWithText("将重新整理 3 段自动摘要，后续对话会逐批补齐；手动纠正会保留。").assertExists()
        compose.runOnIdle { assertEquals(0, deletes) }
    }

    @Test fun protectedSourceOnlyAllowsReturningWithoutRecall() {
        var deletes = 0
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                RecallMessageDialog("原始故事", { MessageRecallImpact(false, "这是故事线来源") },
                    { deletes++ }, { dismissed++ })
            }
        }
        compose.onNodeWithText("确认撤回").assertIsNotEnabled()
        compose.onNodeWithText("保留并返回").performClick()
        compose.runOnIdle { assertEquals(0, deletes); assertEquals(1, dismissed) }
    }

    @Test fun failedReadCanRetryAndFailedRecallRefreshesProtection() {
        var reads = 0
        var deletes = 0
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                RecallMessageDialog("普通消息", {
                    reads++
                    if (reads == 1) error("read failure")
                    MessageRecallImpact(deletes == 0, if (deletes > 0) "已被新故事线引用" else "")
                }, { result -> deletes++; result(false) }, { dismissed++ })
            }
        }
        compose.onNodeWithText("确认撤回").assertIsNotEnabled()
        compose.onNodeWithText("重试检查").performClick()
        compose.onNodeWithText("确认撤回").assertIsEnabled().performClick()
        compose.onNodeWithText("确认撤回").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(0, dismissed) }
    }
}
