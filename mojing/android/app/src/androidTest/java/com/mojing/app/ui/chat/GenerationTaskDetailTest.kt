package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.ui.generation.GenerationTaskDetailSheet
import com.mojing.app.ui.generation.GenerationFeedbackText
import com.mojing.app.ui.generation.GenerationTaskDetailHost
import com.mojing.app.ui.generation.rememberGenerationListState
import com.mojing.app.ui.generation.GenerationTaskFilterBar
import com.mojing.app.ui.generation.GenerationTaskCancelConfirmation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GenerationTaskDetailTest {
    @get:Rule val rule = createComposeRule()

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test fun listPositionRestoresButResetsWhenFilterChanges() {
        val filter = mutableStateOf(0)
        val page = mutableStateOf(0)
        val restoration = StateRestorationTester(rule)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        restoration.setContent {
            state = rememberGenerationListState(filter.value, page.value)
            androidx.compose.foundation.lazy.LazyColumn(state = state,
                modifier = androidx.compose.ui.Modifier.fillMaxSize().systemBarsPadding().testTag("generation-list")) {
                stickyHeader { GenerationTaskFilterBar(filter.value) { filter.value = it } }
                items(150) { index -> androidx.compose.material3.Text("记录 $index") }
            }
        }
        rule.onNodeWithTag("generation-list").performScrollToIndex(80)
        rule.runOnIdle { org.junit.Assert.assertTrue(state.firstVisibleItemIndex > 0) }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { org.junit.Assert.assertTrue(state.firstVisibleItemIndex > 0) }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "filter-scroll.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithText("需处理").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, state.firstVisibleItemIndex); assertEquals(0, state.firstVisibleItemScrollOffset) }
        rule.onNodeWithTag("generation-list").performScrollToIndex(80)
        rule.runOnIdle { page.value = 2 }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }

    @Test fun selectedTaskRestoresAndUsesFreshProgress() {
        val original = GenerationTaskEntity(id = 7, taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
            title = "雾港百科", status = GenerationTaskStatus.RUNNING, progressDone = 1, progressTotal = 5, payloadJson = "{}")
        val tasks = mutableStateOf(listOf(original))
        val restoration = StateRestorationTester(rule)
        var opened: GenerationTaskEntity? = null
        restoration.setContent {
            var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
            MaterialTheme {
                androidx.compose.material3.TextButton(onClick = { selectedId = 7 }) { androidx.compose.material3.Text("打开详情") }
                GenerationTaskDetailHost(tasks.value, selectedId, null, null,
                    onDismiss = { selectedId = null }, onOpen = { opened = it })
            }
        }
        rule.onNodeWithText("打开详情").performClick()
        rule.onNodeWithText("已完成 1 / 5").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("已完成 1 / 5").assertExists()
        rule.runOnIdle { tasks.value = emptyList() }
        rule.onNodeWithText("已完成 1 / 5").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("查看已生成内容").assertDoesNotExist()
        val latest = original.copy(progressDone = 5, status = GenerationTaskStatus.COMPLETED)
        rule.runOnIdle { tasks.value = listOf(latest) }
        rule.onNodeWithText("已完成 5 / 5").assertExists()
        rule.onNodeWithText("查看已生成内容").performClick()
        rule.runOnIdle { assertEquals(latest, opened) }
        rule.onNodeWithText("关闭").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("查看已生成内容").assertDoesNotExist()
    }

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

    @Test fun cancellationKeepsDialogThroughPendingFailureAndRetry() {
        val task = GenerationTaskEntity(id = 7, taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
            title = "雾港百科", status = GenerationTaskStatus.RUNNING, payloadJson = "{}", progressDone = 1)
        val busy = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var attempts = 0
        var dismissed = 0
        rule.setContent { MaterialTheme {
            GenerationTaskCancelConfirmation(listOf(task), 7L, busy.value,
                onDismiss = { dismissed++ }, error = error.value,
                onCancel = { attempts++; busy.value = true; error.value = null })
        } }
        rule.onNodeWithText("取消生成").performClick()
        rule.onNodeWithText("正在取消…").assertIsNotEnabled()
        rule.onNodeWithText("保留任务").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, dismissed); assertEquals(1, attempts)
            busy.value = false; error.value = "取消未完成，请重试。" }
        rule.onNodeWithText("取消未完成，请重试。").assertIsDisplayed()
        rule.onNodeWithText("取消生成").performClick()
        rule.runOnIdle { assertEquals(2, attempts); assertEquals(0, dismissed); busy.value = false }
        rule.onNodeWithText("保留任务").performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
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

    @Test fun resultLookupShowsPendingAndInlineRetryWithCloseAvailable() {
        val opening = mutableStateOf(true)
        val failure = mutableStateOf<String?>(null)
        var retries = 0
        var closes = 0
        rule.setContent { MaterialTheme {
            GenerationTaskDetailSheet(
                GenerationTaskEntity(taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                    title = "雾港百科", status = GenerationTaskStatus.COMPLETED, progressDone = 2, payloadJson = "{}",
                    errorMessage = "长反馈\n".repeat(100)),
                onDismiss = { closes++ }, canOpen = !opening.value, opening = opening.value,
                openError = failure.value, onOpen = { retries++ })
        } }
        rule.onNodeWithText("正在打开…").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("关闭").assertIsEnabled()
        rule.runOnIdle { opening.value = false; failure.value = "生成内容暂时无法打开，请重试" }
        rule.onNodeWithText("生成内容暂时无法打开，请重试").assertIsDisplayed()
        rule.onNodeWithText("重试打开").assertIsDisplayed().performClick()
        rule.onNodeWithText("关闭").performClick()
        rule.runOnIdle { assertEquals(1, retries); assertEquals(1, closes) }
    }

    @Test fun expandedOpenFailureKeepsActionsReachable() {
        var retries = 0
        var closed = 0
        rule.setContent { MaterialTheme {
            GenerationTaskDetailSheet(
                GenerationTaskEntity(taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                    title = "很长的世界设定任务名称".repeat(30), status = GenerationTaskStatus.COMPLETED,
                    progressDone = 2, payloadJson = "{}"),
                onDismiss = { closed++ }, canOpen = true,
                openError = "读取失败，请重试。\n".repeat(100), onOpen = { retries++ })
        } }
        rule.onNodeWithText("展开反馈").performScrollTo().performClick()
        rule.onNodeWithText("重试打开").assertIsDisplayed().performClick()
        rule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, retries); assertEquals(1, closed) }
    }

    @Test fun feedbackExpandsAndResetsWhenContentChanges() {
        val text = mutableStateOf("请求中断，请稍后继续。\n".repeat(100))
        rule.setContent { MaterialTheme { GenerationFeedbackText(text.value) } }
        rule.onNodeWithText("展开反馈").performClick()
        rule.onNodeWithText("收起反馈").assertIsDisplayed()
        rule.onNodeWithText("收起反馈").performClick()
        rule.onNodeWithText("展开反馈").assertIsDisplayed()
        rule.runOnIdle { text.value = "连接中断" }
        rule.onNodeWithText("连接中断").assertIsDisplayed()
        rule.onNodeWithText("展开反馈").assertDoesNotExist()
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
    @Test fun failedDetailOffersRetryWithInlineFailureAndBusyGuard() {
        var retrying by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var retries = 0
        val task = GenerationTaskEntity(id = 8, taskKind = GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI,
            title = "雾港世界", status = GenerationTaskStatus.FAILED, progressDone = 1, progressTotal = 3,
            payloadJson = "{}", errorMessage = "服务暂时不可用".repeat(100))
        rule.setContent { MaterialTheme {
            GenerationTaskDetailSheet(task, {}, true, {}, retrying = retrying, retryError = error,
                onRetry = { retries++ })
        } }
        rule.onNodeWithText("继续尝试").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { retrying = true }
        rule.onNodeWithText("重新排队中…").assertIsNotEnabled()
        rule.runOnIdle { retrying = false; error = "请再试一次" }
        rule.onNodeWithText("继续生成未完成").assertIsDisplayed()
        rule.onNodeWithText("继续尝试").performScrollTo().performClick()
        rule.onNodeWithText("查看已生成内容").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("关闭").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, retries) }
    }

    @Test fun activeDetailExposesCancellationAndCompletedDetailRemovesIt() {
        var status by mutableStateOf(GenerationTaskStatus.RUNNING)
        var busy by mutableStateOf(true)
        var cancels = 0
        val task = GenerationTaskEntity(id = 8, taskKind = GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI,
            title = "雾港世界", status = status, payloadJson = "{}")
        rule.setContent { MaterialTheme {
            GenerationTaskDetailSheet(task.copy(status = status), {}, true, {}, busy = busy, onCancel = { cancels++ })
        } }
        rule.onNodeWithText("取消生成").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { busy = false }
        rule.onNodeWithText("取消生成").performClick()
        rule.runOnIdle { assertEquals(1, cancels); status = GenerationTaskStatus.COMPLETED }
        rule.onNodeWithText("取消生成").assertDoesNotExist()
        rule.onNodeWithText("查看已生成内容").performScrollTo().assertIsDisplayed()
    }

}
