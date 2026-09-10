package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.encyclopedia.SedimentPageControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SedimentPageControlsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun failedPageAllowsRetryAndBackButNotForward() {
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var retries = 0
        var previous = 0
        rule.setContent { MaterialTheme { Column {
            SedimentPageControls(2, true, loading, false, error, { previous++ }, {}, { retries++ })
        } } }
        rule.onNodeWithText("上一页").assertIsNotEnabled()
        rule.onNodeWithText("下一页").assertIsNotEnabled()
        rule.runOnIdle { loading = false; error = "资料读取失败，请重试" }
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        rule.onNodeWithText("下一页").assertIsNotEnabled()
        rule.onNodeWithText("重试").assertIsDisplayed().performClick()
        rule.onNodeWithText("上一页").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, retries); assertEquals(1, previous) }
    }
}
