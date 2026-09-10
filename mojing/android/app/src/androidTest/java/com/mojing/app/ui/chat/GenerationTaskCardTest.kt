package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Before
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.ui.generation.GenerationTaskCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GenerationTaskCardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun hideTestActionBar() { rule.runOnUiThread { rule.activity.actionBar?.hide() } }

    @Test fun failedTaskKeepsPrimaryRetryAndSavedResultReachable() {
        var retrying by mutableStateOf(false)
        var retries = 0
        var opens = 0
        var details = 0
        val task = GenerationTaskEntity(id = 1, taskKind = GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI,
            title = "长世界名称与生成任务标题".repeat(20), payloadJson = "{}", status = GenerationTaskStatus.FAILED,
            progressDone = 1, progressTotal = 3, errorMessage = "暂时无法连接，请稍后重试。".repeat(20))
        rule.setContent { MoJingTheme(themeMode = "light") { Box(Modifier.width(288.dp)) {
            GenerationTaskCard(task, "09-11 10:20", false, false, retrying, true, false,
                onDetail = { details++ }, onCancel = {}, onRetry = { retries++ }, onOpen = { opens++ })
        } } }
        rule.onNodeWithText("继续尝试").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "generation-card.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithText("继续尝试").assertIsDisplayed().performClick()
        rule.onNodeWithText("查看已保存内容").assertIsDisplayed().performClick()
        rule.onNodeWithText(task.title).performClick()
        rule.runOnIdle { retrying = true }
        rule.onNodeWithText("重新排队中…").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, retries); assertEquals(1, opens); assertEquals(1, details) }
    }

    @Test fun activeTaskUsesExplicitMenuForCancellation() {
        var busy by mutableStateOf(false)
        var cancels = 0
        val task = GenerationTaskEntity(id = 1, taskKind = GenerationTaskKinds.CHARACTER_PERSONA_AI,
            title = "角色人设", payloadJson = "{}", status = GenerationTaskStatus.RUNNING)
        rule.setContent { MoJingTheme(themeMode = "light") {
            GenerationTaskCard(task, "09-11 10:20", true, busy, false, true, false,
                onDetail = {}, onCancel = { cancels++ }, onRetry = {}, onOpen = {})
        } }
        rule.onNodeWithText("正在收尾").assertIsDisplayed()
        rule.onNodeWithText("取消生成").assertDoesNotExist()
        rule.onNodeWithContentDescription("任务操作").performClick()
        rule.runOnIdle { busy = true }
        rule.onNodeWithText("取消生成").assertIsNotEnabled()
        rule.runOnIdle { busy = false }
        rule.onNodeWithText("取消生成").performClick()
        rule.runOnIdle { assertEquals(1, cancels) }
    }
}
