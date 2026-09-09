package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.mojing.app.ui.generation.GenerationTaskFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GenerationTaskFeedbackTest {
    @get:Rule val rule = createComposeRule()
    @Test fun feedbackStaysVisibleUntilDismissedAndThenIsConsumed() {
        val host = SnackbarHostState()
        val message = mutableStateOf<String?>("将在当前步骤保存后暂停")
        val consumed = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                GenerationTaskFeedback(message.value, host) {
                    consumed.add(it)
                    if (message.value == it) message.value = null
                }
                SnackbarHost(host)
            }
        }
        rule.onNodeWithText("将在当前步骤保存后暂停").assertIsDisplayed()
        rule.runOnIdle {
            assertTrue(consumed.isEmpty())
            host.currentSnackbarData?.dismiss()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("将在当前步骤保存后暂停"), consumed) }
    }
}
