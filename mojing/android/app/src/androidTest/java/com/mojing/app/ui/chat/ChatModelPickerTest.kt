package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.ModelPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatModelPickerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun followSettingsIsSearchableAndSharesTheSaveLock() {
        val saving = mutableStateOf(true)
        var followed = false
        rule.setContent { MaterialTheme {
            ChatModelPicker(emptyList(), onDismiss = {}, selectedLabel = "跟随角色与模型设置",
                isSaving = saving.value, onFollowSettings = { followed = true }) { _, _ -> }
        } }
        rule.onNodeWithText("搜索平台或模型").performTextInput("跟随")
        rule.onNodeWithTag("chat-model-follow-settings").assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithText("没有匹配模型，请在模型设置中添加平台和模型。").assertDoesNotExist()
        rule.runOnIdle { saving.value = false }
        rule.onNodeWithTag("chat-model-follow-settings").performClick()
        rule.runOnIdle { assertTrue(followed) }
    }

    @Test fun selectionUsesPlatformIdentityEvenWhenNamesMatch() {
        val selection = mutableStateOf("a" to "shared-model")
        rule.setContent { MaterialTheme {
            ChatModelPicker(
                platforms = listOf(
                    ModelPlatform("a", "同名平台", "https://a.test", "test-key", listOf("shared-model")),
                    ModelPlatform("b", "同名平台", "https://b.test", "test-key", listOf("shared-model")),
                ),
                onDismiss = {}, selectedModel = selection.value,
            ) { platform, model -> selection.value = platform to model }
        } }
        rule.onNodeWithTag("chat-model:a:shared-model").assertIsSelected()
        rule.onNodeWithTag("chat-model-list").performScrollToNode(hasTestTag("chat-model:b:shared-model"))
        rule.onNodeWithTag("chat-model:b:shared-model").assertIsNotSelected().performClick()
        rule.onNodeWithTag("chat-model:b:shared-model").assertIsSelected()
        rule.onNodeWithTag("chat-model-list").performScrollToNode(hasTestTag("chat-model:a:shared-model"))
        rule.onNodeWithTag("chat-model:a:shared-model").assertIsNotSelected()
        rule.runOnIdle { assertEquals("b" to "shared-model", selection.value) }
    }

    @Test fun longRequestInfoKeepsSearchAndCloseReachable() {
        var dismissed = false
        var selected: Pair<String, String>? = null
        val models = (1..200).map { "model-$it-extended-context" }
        rule.setContent {
            MaterialTheme {
                ChatModelPicker(
                    platforms = listOf(ModelPlatform("a", "平台 A", "https://a.test", "test-key", models)),
                    onDismiss = { dismissed = true }, selectedLabel = "平台 A · " + "long-model-".repeat(80),
                    lastRequestModel = "previous-model-".repeat(80), isGenerating = true
                ) { platform, model -> selected = platform to model }
            }
        }
        rule.onNodeWithText("关闭").assertIsDisplayed()
        rule.onNodeWithText("搜索平台或模型").assertIsDisplayed().performTextInput("model-200-")
        rule.onNodeWithTag("chat-model-list").performScrollToNode(hasText(models.last()))
        rule.onNodeWithText(models.last()).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("a" to models.last(), selected) }
        rule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun missingKeyIsDisabledAndSearchCanRecoverFromEmptyResults() {
        rule.setContent {
            MaterialTheme {
                ChatModelPicker(
                    platforms = listOf(ModelPlatform("b", "平台 B", "https://b.test", "", listOf("unavailable-model"))),
                    onDismiss = {}, selectedLabel = "平台 A · next-model", lastRequestModel = "previous-model"
                ) { _, _ -> error("An unconfigured platform must not be selected") }
            }
        }
        rule.onNodeWithText("下次发送：平台 A · next-model").assertIsDisplayed()
        rule.onNodeWithText("最近请求：previous-model").assertIsDisplayed()
        rule.onNodeWithText("unavailable-model").assertIsNotEnabled()
        rule.onNodeWithText("搜索平台或模型").performTextInput("missing")
        rule.onNodeWithText("没有匹配模型，请在模型设置中添加平台和模型。").assertIsDisplayed()
        rule.onNodeWithText("搜索平台或模型").performTextClearance()
        rule.onNodeWithTag("chat-model-list").performScrollToNode(hasText("unavailable-model"))
        rule.onNodeWithText("unavailable-model").assertIsNotEnabled()
    }

    @Test fun savingDisablesSelectionAndFailureAllowsRetry() {
        val saving = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var selected = false
        rule.setContent {
            MaterialTheme {
                ChatModelPicker(
                    platforms = listOf(ModelPlatform("a", "A", "https://a.test", "test-key", listOf("next-model"))),
                    onDismiss = {}, isSaving = saving.value, saveError = error.value
                ) { _, _ -> selected = true }
            }
        }
        rule.onNodeWithText("正在保存模型选择…").assertIsDisplayed()
        rule.onNodeWithText("next-model").assertIsNotEnabled()
        rule.runOnIdle { saving.value = false; error.value = "模型选择未保存，请检查平台配置后重试" }
        rule.onNodeWithText("模型选择未保存，请检查平台配置后重试").assertIsDisplayed()
        rule.onNodeWithText("正在保存模型选择…").assertDoesNotExist()
        rule.onNodeWithText("next-model").assertIsEnabled().performClick()
        rule.runOnIdle { assertTrue(selected) }
    }
}
