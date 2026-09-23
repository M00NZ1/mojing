package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.navigation.compose.*
import com.mojing.app.ui.settings.SettingsSections
import com.mojing.app.ui.navigation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsSectionsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun restoredSectionKeepsModelShortcutBehindDraftGuard() {
        val restore = StateRestorationTester(rule)
        var request by mutableStateOf(false)
        var blocked = false
        var pending: (() -> Unit)? = null
        var consumed = 0
        restore.setContent {
            MaterialTheme { Column {
                SettingsSections(request, { request = false; consumed++ }, { action ->
                    if (blocked) pending = action else action()
                }, onBack = {}) { Text("当前分类：$it") }
            } }
        }
        rule.onNodeWithText("个性化").performClick()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("当前分类：2").assertIsDisplayed()
        rule.runOnIdle { blocked = true; request = true }
        rule.waitForIdle()
        rule.onNodeWithText("当前分类：2").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, consumed); assertNotNull(pending); pending?.invoke(); blocked = false }
        rule.onNodeWithText("当前分类：0").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回设置").performClick()
        rule.onNodeWithText("个性化").performClick()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("当前分类：2").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, consumed) }
    }

    @Test fun shortcutOpensModelsAfterRestoringAnotherSettingsSection() {
        rule.setContent {
            val nav = rememberNavController()
            MaterialTheme {
                NavHost(nav, startDestination = Routes.SESSION_LIST) {
                    composable(Routes.SESSION_LIST) {
                        TextButton({ nav.navigateToMainTab(Routes.SETTINGS) }) { Text("打开设置") }
                    }
                    composable(Routes.SETTINGS) { entry ->
                        val requested by entry.savedStateHandle.getStateFlow(SETTINGS_MODEL_REQUEST, false).collectAsState()
                        Column {
                            SettingsSections(requested, { entry.savedStateHandle[SETTINGS_MODEL_REQUEST] = false }, { it() }, onBack = {}) {
                                Column {
                                    Text("当前分类：$it")
                                    TextButton({ nav.navigateToMainTab(Routes.CREATION_HUB) }) { Text("进入创作中心") }
                                }
                            }
                        }
                    }
                    composable(Routes.CREATION_HUB) {
                        TextButton({ nav.navigateToModelSettings() }) { Text("配置模型") }
                    }
                }
            }
        }
        rule.onNodeWithText("打开设置").performClick()
        rule.onNodeWithText("个性化").performClick()
        rule.onNodeWithText("当前分类：2").assertIsDisplayed()
        rule.onNodeWithText("进入创作中心").performClick()
        rule.onNodeWithText("配置模型").performClick()
        rule.onNodeWithText("当前分类：0").assertIsDisplayed()
    }
}
