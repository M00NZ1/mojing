package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.ui.generation.GenerationTaskDetailSheet
import com.mojing.app.ui.generation.GenerationTaskCancelConfirmation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GenerationTaskDetailTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cancelConfirmationFollowsProgressAndClosesWhenTaskCompletes() {
        val task = mutableStateOf(GenerationTaskEntity(id = 7, taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
            title = "雾港百科", status = GenerationTaskStatus.RUNNING, payloadJson = "{}", progressDone = 1))
        var dismissed = false
        rule.setContent { MaterialTheme {
            GenerationTaskCancelConfirmation(listOf(task.value), 7L, false,
                onDismiss = { dismissed = true }, onCancel = { error("Completed task must not be cancelled") })
        } }
        rule.onNodeWithText("已保存 1 项", substring = true).assertExists()
        rule.runOnIdle { task.value = task.value.copy(progressDone = 2) }
        rule.onNodeWithText("已保存 2 项", substring = true).assertExists()
        rule.runOnIdle { task.value = task.value.copy(status = GenerationTaskStatus.COMPLETED) }
        rule.onNodeWithText("取消生成").assertDoesNotExist()
        rule.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test fun longFeedbackKeepsResultAndCloseReachable() {
        var opened = 0
        var closed = 0
        rule.setContent {
            MaterialTheme {
                GenerationTaskDetailSheet(
                    GenerationTaskEntity(taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                        title = "雾港百科", status = GenerationTaskStatus.FAILED,
                        progressDone = 2, progressTotal = 5, payloadJson = "{}",
                        errorMessage = "请求中断，请稍后继续。\n".repeat(100)),
                    onDismiss = { closed++ }, canOpen = true, onOpen = { opened++ })
            }
        }
        rule.onNodeWithText("查看已生成内容").assertIsDisplayed().performClick()
        rule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, closed)
        }
    }

    @Test fun taskWithoutSavedContentHasNoResultAction() {
        rule.setContent {
            MaterialTheme {
                GenerationTaskDetailSheet(
                    GenerationTaskEntity(taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                        title = "雾港百科", status = GenerationTaskStatus.QUEUED, payloadJson = "{}"),
                    onDismiss = {}, canOpen = true, onOpen = {})
            }
        }
        rule.onNodeWithText("查看已生成内容").assertDoesNotExist()
        rule.onNodeWithText("已完成 0 / 0").assertDoesNotExist()
        rule.onNodeWithText("关闭").assertIsDisplayed()
    }
}
