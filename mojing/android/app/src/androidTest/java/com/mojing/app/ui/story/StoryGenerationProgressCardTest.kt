package com.mojing.app.ui.story

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StoryGenerationProgressCardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowLongPreviewCanCopyAndStop() {
        var copied = ""
        var stopped = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    StoryGenerationProgressCard(
                        StorySimulationState(isGenerating = true, generationStage = "接收正文", generationModel = "very-long-model-name-that-must-stay-on-one-line", preview = "正文".repeat(300), receivedChars = 600),
                        onStop = { stopped = true }, onCopy = { copied = it.text }, onRetry = {},
                    )
                }
            }
        }
        rule.onNodeWithText("复制已接收预览").assertExists().performClick()
        rule.onNodeWithText("停止").assertExists().performClick()
        assertEquals(600, copied.length)
        assertEquals(true, stopped)
    }

    @Test fun failedStateKeepsRetryReachable() {
        var retried = false
        rule.setContent {
            MaterialTheme {
                StoryGenerationProgressCard(
                    StorySimulationState(generationStage = "失败", error = "网络失败", preview = "有限预览"),
                    onStop = {}, onCopy = {}, onRetry = { retried = true },
                )
            }
        }
        rule.onNodeWithText("重试").assertExists().performClick()
        assertEquals(true, retried)
    }

    @Test fun failedSaveOffersFullCopyAndLocalRetryOnNarrowScreen() {
        var copied = false
        var retried = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    StoryGenerationProgressCard(
                        StorySimulationState(hasPendingStory = true, generationStage = "保存失败", error = "正文已生成，保存未完成。可重试保存或复制完整正文。"),
                        onStop = {}, onCopy = {}, onRetry = { retried = true },
                        onCopyCompleted = { copied = true },
                    )
                }
            }
        }
        rule.onNodeWithText("复制完整正文").assertExists().performClick()
        rule.onNodeWithText("重试保存").assertExists().performClick()
        assertEquals(true, copied)
        assertEquals(true, retried)
    }

    @Test fun navigationFailureOffersExistingSession() {
        var opened = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    StoryGenerationProgressCard(
                        StorySimulationState(savedSessionId = 42L, generationStage = "已保存", error = "小说已保存，请重新打开会话。"),
                        onStop = {}, onCopy = {}, onRetry = { opened = true },
                    )
                }
            }
        }
        rule.onNodeWithText("打开已保存的会话").assertExists().performClick()
        assertEquals(true, opened)
    }
}
