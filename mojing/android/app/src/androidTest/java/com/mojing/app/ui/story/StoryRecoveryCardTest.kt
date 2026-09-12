package com.mojing.app.ui.story

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StoryRecoveryCardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingRecoveryKeepsCopySaveAndDiscardActionsReachableOnNarrowLargeText() {
        val actions = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        StoryRecoveryCard(
                            state = StorySimulationState(
                                isRestoring = false, hasPendingStory = true,
                                recoveredStory = true, draftPersisted = true,
                                storyTitle = "雨夜旧塔", chapterCount = 2,
                                preview = "完整正文".repeat(80),
                            ),
                            onSaveOrOpen = { actions += "save" },
                            onCopy = { actions += "copy" },
                            onDiscard = { actions += "discard" },
                            onNewStory = {}, onRetryRecovery = {},
                            onCopyRecovery = {}, onDiscardUnreadable = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("继续上次创作").assertIsDisplayed()
        rule.onNodeWithText("保存并进入会话").assertIsDisplayed().performClick()
        rule.onNodeWithText("复制完整正文").assertIsDisplayed().performClick()
        rule.onNodeWithText("放弃本次正文").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("save", "copy", "discard"), actions) }
    }

    @Test
    fun savedRecoveryOffersOpenAndStartNewStory() {
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    StoryRecoveryCard(
                        state = StorySimulationState(isRestoring = false, savedSessionId = 42L, storyTitle = "已保存的长篇"),
                        onSaveOrOpen = { actions += "open" }, onCopy = {}, onDiscard = {},
                        onNewStory = { actions += "new" }, onRetryRecovery = {},
                        onCopyRecovery = {}, onDiscardUnreadable = {},
                    )
                }
            }
        }
        rule.onNodeWithText("上次创作已保存").assertIsDisplayed()
        rule.onNodeWithText("打开已保存的会话").performClick()
        rule.onNodeWithText("开始新作").performClick()
        rule.runOnIdle { assertEquals(listOf("open", "new"), actions) }
    }

    @Test
    fun unreadableRecoveryOffersRetryAndSafeDataActions() {
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    StoryRecoveryCard(
                        state = StorySimulationState(
                            isRestoring = false,
                            recoveryError = "上次创作暂时无法读取，请重试。",
                            canCopyRecoveryData = true,
                        ),
                        onSaveOrOpen = {}, onCopy = {}, onDiscard = {}, onNewStory = {},
                        onRetryRecovery = { actions += "retry" },
                        onCopyRecovery = { actions += "copy-recovery" },
                        onDiscardUnreadable = { actions += "discard-unreadable" },
                    )
                }
            }
        }
        rule.onNodeWithText("恢复创作").assertIsDisplayed()
        rule.onNodeWithText("重新读取").performClick()
        rule.onNodeWithText("复制恢复数据").performClick()
        rule.onNodeWithText("清除无法读取的草稿").performClick()
        rule.runOnIdle { assertEquals(listOf("retry", "copy-recovery", "discard-unreadable"), actions) }
    }

    @Test
    fun savingStateDisablesRepeatedSaveAndDiscardActions() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        StoryRecoveryCard(
                            state = StorySimulationState(
                                isRestoring = false, hasPendingStory = true, isSaving = true,
                                storyTitle = "保存中的故事", chapterCount = 2,
                            ),
                            onSaveOrOpen = {}, onCopy = {}, onDiscard = {}, onNewStory = {},
                            onRetryRecovery = {}, onCopyRecovery = {}, onDiscardUnreadable = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("正在保存…").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("放弃本次正文").assertIsDisplayed().assertIsNotEnabled()
    }
}
