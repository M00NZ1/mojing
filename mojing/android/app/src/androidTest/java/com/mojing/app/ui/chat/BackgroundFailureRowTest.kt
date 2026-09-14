package com.mojing.app.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import com.mojing.app.ui.session.SessionListRowInner
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Rule
import org.junit.Test

class BackgroundFailureRowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun failedReplyReplacesStalePreviewAndRemainsReadable() {
        compose.setContent {
            MoJingTheme(themeMode = "sky") {
                SessionListRowInner(
                    row = SessionWithListMeta(session = SessionEntity(id = 1, title = "雨夜故事"),
                        messageCount = 1, participantCount = 2, lastMessagePreview = "旧回复", lastMessageSpeakerType = "narrator"),
                    backgroundFailure = "网络连接中断，请重新进入对话后继续",
                )
            }
        }
        compose.onNodeWithText("生成未完成", substring = true).assertIsDisplayed()
        compose.onNodeWithText("旧回复").assertDoesNotExist()
        compose.onNodeWithText("雨夜故事").assertIsDisplayed()
    }
}
