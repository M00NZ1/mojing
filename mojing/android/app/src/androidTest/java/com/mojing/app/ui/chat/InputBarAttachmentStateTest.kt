package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InputBarAttachmentStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun attachmentCopyKeepsDraftEditableButBlocksConflictingActions() {
        var sendCount = 0
        var clearCount = 0

        composeRule.setContent {
            MaterialTheme {
                InputBar(
                    value = TextFieldValue("准备发送"),
                    onValueChange = {},
                    onSend = { sendCount += 1 },
                    onStop = {},
                    isGenerating = false,
                    isAddingAttachment = true,
                    onRequestNarrator = {},
                    onInsertMacro = {},
                    pendingAttachmentCount = 1,
                    onClearPendingAttachments = { clearCount += 1 },
                    onVoiceClick = {},
                    onImageGenClick = {},
                    onAttachImageClick = {},
                    onOpenEmoji = {},
                    onPreviewSpeak = {},
                )
            }
        }

        composeRule.onNodeWithText("正在把所选图片保存到本机…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("发送").assertIsNotEnabled()
        composeRule.onNodeWithText("清除").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("更多输入工具")
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.runOnIdle {
            assertEquals(0, sendCount)
            assertEquals(0, clearCount)
        }
    }
}
