package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageEditDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun savingBlocksDismissalAndFailurePreservesDraftForRetry() {
        var saving by mutableStateOf(false)
        var committed by mutableStateOf(false)
        var failure by mutableStateOf<String?>(null)
        var saves = 0
        var dismissed = 0
        rule.setContent { MaterialTheme {
            MessageEditDialog("修改后的正文", {}, true, true, true,
                { saves++; saving = true }, { dismissed++ }, saving, failure, committed)
        } }
        rule.onNodeWithText("创建编辑分支").performClick()
        rule.onNodeWithText("正在保存…").assertIsNotEnabled()
        rule.onNodeWithContentDescription("关闭消息编辑").assertIsNotEnabled()
        rule.onNodeWithContentDescription("消息正文").assertIsNotEnabled()
        rule.runOnIdle { saving = false; failure = "消息编辑失败，请重试" }
        rule.onNodeWithText("消息编辑失败，请重试").assertIsDisplayed()
        rule.onNodeWithText("修改后的正文").assertExists()
        rule.onNodeWithText("创建编辑分支").performClick()
        rule.runOnIdle { assertEquals(2, saves); saving = false; committed = true; failure = "消息已编辑，请重新进入对话" }
        rule.onNodeWithText("已保存").assertIsNotEnabled()
        rule.onNodeWithContentDescription("关闭消息编辑").performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
        rule.onNodeWithText("放弃这次编辑？").assertDoesNotExist()
    }

    @Test fun longDraftKeepsActionsVisibleAndRequiresDiscardConfirmation() {
        val original = "雾港的灯塔依然亮着。\n".repeat(200)
        var draft by mutableStateOf(original)
        var saved: String? = null
        var dismissed = 0
        rule.setContent { MoJingTheme(themeMode = "dark") {
            MessageEditDialog(draft, { draft = it }, true, draft != original,
                draft.isNotBlank() && draft != original, { saved = draft }, { dismissed++ })
        } }
        rule.onNodeWithText("创建编辑分支").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithContentDescription("消息正文").performClick().performTextReplacement(original + "新的结尾")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("保存到新故事线，并重新生成回复。").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("创建编辑分支").assertIsDisplayed().assertIsEnabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        File(instrumentation.targetContext.getExternalFilesDir(null), "message-editor.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithContentDescription("关闭消息编辑").performClick()
        rule.onNodeWithText("继续编辑").performClick()
        rule.runOnIdle { assertEquals(original + "新的结尾", draft); assertEquals(0, dismissed) }
        rule.onNodeWithText("创建编辑分支").performClick()
        rule.runOnIdle { assertEquals(draft, saved) }
        rule.onNodeWithContentDescription("关闭消息编辑").performClick()
        rule.onNodeWithText("放弃修改").performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun unchangedDraftClosesDirectlyAndGenerationDisablesSave() {
        var dismissed = 0
        rule.setContent { MaterialTheme {
            MessageEditDialog("原文", {}, false, false, false, {}, { dismissed++ })
        } }
        rule.onNodeWithText("创建编辑分支").assertIsNotEnabled()
        rule.onNodeWithContentDescription("关闭消息编辑").performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
        rule.onNodeWithText("放弃这次编辑？").assertDoesNotExist()
    }
}
