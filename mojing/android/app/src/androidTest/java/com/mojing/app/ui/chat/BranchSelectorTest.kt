package com.mojing.app.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BranchSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun opensAtCurrentBranchInLargeLibrary() {
        compose.setContent {
            MoJingTheme {
                BranchSelector(true, {}, (1..1000).map { "branch-$it" to "雨夜篇章$it" }, "branch-1000", {})
            }
        }
        compose.onNodeWithText("雨夜篇章1000").assertIsDisplayed()
        compose.onNodeWithText("雨夜篇章1").assertDoesNotExist()
    }

    @Test fun searchesLargeLibraryAndChoosesStableBranchId() {
        var chosen = ""
        var dismissed = false
        compose.setContent {
            MoJingTheme {
                BranchSelector(true, { dismissed = true }, (1..1000).map { "branch-$it" to "雨夜篇章$it" },
                    "branch-1", { chosen = it })
            }
        }
        compose.onNodeWithText("雨夜篇章1000").assertDoesNotExist()
        compose.onNodeWithText("搜索故事线").performTextInput("篇章1000")
        compose.onNodeWithText("雨夜篇章1000").performClick()
        compose.runOnIdle { assertEquals("branch-1000", chosen); assertTrue(dismissed) }
    }

    @Test fun currentBranchDoesNotTriggerDuplicateSwitchAndEmptySearchKeepsActions() {
        var switches = 0
        var dismissed = false
        compose.setContent {
            MoJingTheme(themeMode = "sky") {
                BranchSelector(true, { dismissed = true }, listOf("main" to "主线"), "main", { switches++ })
            }
        }
        compose.onNodeWithText("搜索故事线").performTextInput("不存在")
        compose.onNodeWithText("没有匹配的故事线").assertIsDisplayed()
        compose.onNodeWithText("新建").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithContentDescription("当前故事线", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(0, switches); assertTrue(dismissed) }
    }
}
