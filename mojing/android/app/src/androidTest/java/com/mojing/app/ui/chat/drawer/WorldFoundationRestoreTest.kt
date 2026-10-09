package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionWorldEntity
import org.junit.Rule
import org.junit.Test

class WorldFoundationRestoreTest {
    @get:Rule val rule = createComposeRule()
    private val original = SessionWorldEntity(id = 9, sessionId = 42, encyclopediaId = 7,
        worldPrompt = "本场基础规则")
    private val foundation = (1..24).joinToString("\n\n") { "第${it}段：港口灯塔照亮海岸，居民按世界既定规则行动。" }

    @Test fun expandedTailAndCollapsedIntentRestore() {
        val tester = StateRestorationTester(rule)
        tester.setContent { MaterialTheme { WorldConfigTab(world = original,
            encyclopediaFoundation = foundation,
            onWorldSettingChanged = { _, _ -> error("Read wrote world") },
            onSaveSessionWorldCredentials = {}, onCredentialFieldsDirty = {}, onSessionThinkMax = {}) } }
        rule.onNodeWithText("展开设定").performScrollTo().performClick()
        rule.onNodeWithText("收起设定").performScrollTo()
        val top = rule.onNodeWithText("收起设定").fetchSemanticsNode().boundsInRoot.top
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("收起设定").assertIsDisplayed()
        org.junit.Assert.assertEquals(top, rule.onNodeWithText("收起设定").fetchSemanticsNode().boundsInRoot.top, 2f)
        rule.onNodeWithText("收起设定").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("展开设定").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("收起设定").assertDoesNotExist()
    }

    @Test fun differentWorldDoesNotInheritExpansion() {
        val world = mutableStateOf(original)
        rule.setContent { MaterialTheme { WorldConfigTab(world = world.value,
            encyclopediaFoundation = foundation,
            onWorldSettingChanged = { _, _ -> error("Read wrote world") },
            onSaveSessionWorldCredentials = {}, onCredentialFieldsDirty = {}, onSessionThinkMax = {}) } }
        rule.onNodeWithText("展开设定").performScrollTo().performClick()
        rule.runOnIdle { world.value = original.copy(id = 10, sessionId = 43) }
        rule.onNodeWithText("展开设定").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("收起设定").assertDoesNotExist()
    }

    @Test fun delayedFoundationReadRestoresTailAfterReady() {
        val loaded = mutableStateOf(true)
        val failure = mutableStateOf<String?>(null)
        var retries = 0
        var recreating = false
        val tester = StateRestorationTester(rule)
        tester.setContent { MaterialTheme { WorldConfigTab(world = original,
            encyclopediaFoundation = if (!recreating || loaded.value) foundation else "",
            foundationLoaded = !recreating || loaded.value,
            foundationLoading = recreating && !loaded.value,
            foundationLoadError = failure.value,
            onRetryFoundation = { retries++; failure.value = null; loaded.value = true },
            onWorldSettingChanged = { _, _ -> error("Read wrote world") },
            onSaveSessionWorldCredentials = {}, onCredentialFieldsDirty = {}, onSessionThinkMax = {}) } }
        rule.onNodeWithText("展开设定").performScrollTo().performClick()
        rule.onNodeWithText("收起设定").performScrollTo()
        val top = rule.onNodeWithText("收起设定").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { loaded.value = false }
        rule.waitForIdle()
        // The current tree still has its foundation until recreation starts.
        recreating = true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("正在读取百科基础设定…").assertExists()
        rule.runOnIdle { failure.value = "百科读取失败" }
        rule.onNodeWithText("百科读取失败").assertIsDisplayed()
        rule.onNodeWithText("重试读取").performClick()
        rule.onNodeWithText("收起设定").assertIsDisplayed()
        org.junit.Assert.assertEquals(top, rule.onNodeWithText("收起设定").fetchSemanticsNode().boundsInRoot.top, 2f)
        rule.runOnIdle { org.junit.Assert.assertEquals(1, retries) }
    }
}
