package com.mojing.app.ui.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionDeleteDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pendingDeletionDisablesDismissAndDuplicateSubmission() {
        compose.setContent { MoJingTheme { SessionDeleteDialog("雨夜故事", true, null, {}, {}) } }
        compose.onNodeWithText("正在删除…").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }
    @Test fun failedDeletionShowsRetryAlongsideOriginalTitle() {
        var attempts = 0
        compose.setContent { MoJingTheme(themeMode = "sky") {
            SessionDeleteDialog("雨夜故事", false, "删除未完成，请重试", { attempts++ }, {})
        } }
        compose.onNodeWithText("雨夜故事").assertIsDisplayed()
        compose.onNodeWithText("删除未完成，请重试").assertIsDisplayed()
        compose.onNodeWithText("重试删除").performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
    }
}
