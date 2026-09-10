package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.encyclopedia.EntrySourcePreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EntrySourcePreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sourcePagingRemainsAvailableWhenOneMessageIsMissing() {
        val index = mutableStateOf(1)
        rule.setContent { MaterialTheme {
            EntrySourcePreview(false, "原文", if (index.value == 1) "原始对话已不存在" else null,
                onClose = {}, onRetry = {}, sourceIndex = index.value, sourceCount = 3,
                onSourceChange = { index.value = it })
        } }
        rule.onNodeWithText("2 / 3").assertIsDisplayed()
        rule.onNodeWithText("上一条").performClick()
        rule.onNodeWithText("1 / 3").assertIsDisplayed()
        rule.onNodeWithText("上一条").assertIsNotEnabled()
        rule.onNodeWithText("下一条").performClick().performClick()
        rule.onNodeWithText("3 / 3").assertIsDisplayed()
        rule.onNodeWithText("下一条").assertIsNotEnabled()
    }

    @Test fun sourcePreviewKeepsCloseAndRetryReachable() {
        val loading = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var closes = 0
        var retries = 0
        var opened = 0
        rule.setContent { MaterialTheme {
            EntrySourcePreview(loading.value, "灯塔下的原始剧情。\n".repeat(200), error.value,
                onClose = { closes++ }, onRetry = { retries++ },
                onOpenConversation = if (!loading.value && error.value == null) ({ opened++ }) else null)
        } }
        rule.onNodeWithText("正在读取原文…").assertIsDisplayed()
        rule.onNodeWithText("返回编辑").assertIsDisplayed()
        rule.runOnIdle { loading.value = false }
        rule.onNodeWithText("进入来源故事线").assertIsDisplayed().performClick()
        rule.onNodeWithText("返回编辑").assertIsDisplayed().performClick()
        rule.runOnIdle { error.value = "原文读取失败，请重试。" }
        rule.onNodeWithText("重新读取").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, closes); assertEquals(1, retries); assertEquals(1, opened) }
    }
}
