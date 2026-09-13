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

    @Test fun followSettingsRemainsReachableAndSharesTheSaveLock() {
        val saving = mutableStateOf(true)
        var followed = false
        rule.setContent { MaterialTheme {
            ChatModelPicker(emptyList(), onDismiss = {}, selectedLabel = "跟随角色与模型设置",
                isSaving = saving.value, onFollowSettings = { followed = true }) { _, _ -> }
        } }
        rule.onNodeWithTag("chat-model-follow-settings").assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithText("没有匹配的模型，试试其他关键词。").assertDoesNotExist()
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
        rule.onNodeWithTag("chat-platform:b").performClick()
        rule.onNodeWithTag("chat-model:b:shared-model").assertIsNotSelected().performClick()
        rule.onNodeWithTag("chat-model:b:shared-model").assertIsSelected()
        rule.onNodeWithTag("chat-platform:a").performClick()
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
                    lastRequestModel = "previous-model-".repeat(80), lastRequestPlatform = "旧平台-".repeat(80), isGenerating = true
                ) { platform, model -> selected = platform to model }
            }
        }
        rule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索模型").performClick()
        rule.onNodeWithTag("model-picker-search").assertIsDisplayed().performTextInput("model-200-")
        rule.onNodeWithTag("chat-model-list").performScrollToNode(hasText(models.last()))
        rule.onNodeWithText(models.last()).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("a" to models.last(), selected) }
        rule.onNodeWithContentDescription("关闭").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun missingKeyIsDisabledAndSearchCanRecoverFromEmptyResults() {
        rule.setContent {
            MaterialTheme {
                ChatModelPicker(
                    platforms = listOf(ModelPlatform("b", "平台 B", "https://b.test", "", listOf("unavailable-model"))),
                    onDismiss = {}, selectedLabel = "平台 A · next-model", lastRequestModel = "previous-model", lastRequestPlatform = "旧平台"
                ) { _, _ -> error("An unconfigured platform must not be selected") }
            }
        }
        rule.onNodeWithText("下次发送：平台 A · next-model").assertIsDisplayed()
        rule.onNodeWithText("最近请求：旧平台 · previous-model").assertIsDisplayed()
        rule.onNodeWithText("unavailable-model").assertIsNotEnabled()
        rule.onNodeWithContentDescription("搜索模型").performClick()
        rule.onNodeWithTag("model-picker-search").performTextInput("missing")
        rule.onNodeWithText("没有匹配的模型，试试其他关键词。").assertIsDisplayed()
        rule.onNodeWithTag("model-picker-search").performTextClearance()
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
    @Test fun platformBrowsingAndCollapsingSearchDoNotSelectAModel() {
        var result: Pair<String, String>? = null
        rule.setContent { MaterialTheme {
            ChatModelPicker(listOf(
                ModelPlatform("a", "平台 A", "https://a.test", "test-key", listOf("alpha")),
                ModelPlatform("b", "平台 B", "https://b.test", "test-key", listOf("beta")),
            ), onDismiss = {}, selectedModel = "a" to "alpha") { p, m -> result = p to m }
        } }
        rule.onNodeWithTag("model-picker-search").assertDoesNotExist()
        rule.onNodeWithTag("chat-platform:b").performClick().assertIsSelected()
        rule.onNodeWithText("alpha").assertDoesNotExist()
        rule.onNodeWithText("beta").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索模型").performClick()
        rule.onNodeWithTag("model-picker-search").performTextInput("missing")
        rule.onNodeWithContentDescription("收起搜索").performClick()
        rule.onNodeWithTag("model-picker-search").assertDoesNotExist()
        rule.onNodeWithText("beta").assertIsDisplayed()
        rule.runOnIdle { assertEquals(null, result) }
        rule.onNodeWithText("beta").performClick()
        rule.runOnIdle { assertEquals("b" to "beta", result) }
    }

    @Test fun compactLayoutSupportsLargeTextInDarkTheme() {
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.35f)
            ) {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    ChatModelPicker(listOf(
                        ModelPlatform("deepseek", "DeepSeek", "https://example.test", "test-key", listOf("deepseek-chat", "deepseek-reasoner")),
                        ModelPlatform("silicon", "硅基流动", "https://example.test", "test-key", listOf("DeepSeek-V3.2")),
                        ModelPlatform("openai", "OpenAI", "https://example.test", "test-key", listOf("gpt-model")),
                    ), onDismiss = {}, selectedModel = "deepseek" to "deepseek-chat",
                        selectedLabel = "DeepSeek · deepseek-chat", onFollowSettings = {}) { _, _ -> }
                }
            }
        }
        rule.onNodeWithText("deepseek-chat").assertIsDisplayed()
        rule.onNodeWithTag("chat-model-follow-settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        capturePicker("model-picker-dark")
        rule.onNodeWithContentDescription("搜索模型").performClick()
        rule.onNodeWithTag("model-picker-search").performTextInput("reasoner")
        rule.onNodeWithText("deepseek-reasoner").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        capturePicker("model-picker-search")
    }

    private fun capturePicker(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("captureModelPicker") != "true") return
        rule.waitForIdle()
        val file = java.io.File(instrumentation.targetContext.externalCacheDir, "$name.png")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun initiallySelectedPlatformIsVisibleInLongPlatformList() {
        rule.setContent { MaterialTheme {
            ChatModelPicker((1..20).map {
                ModelPlatform("p$it", "平台 $it", "https://example.test", "test-key", listOf("model-$it"))
            }, onDismiss = {}, selectedModel = "p20" to "model-20") { _, _ -> }
        } }
        rule.onNodeWithTag("chat-platform:p20").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("chat-model:p20:model-20").assertIsSelected()
        rule.onNodeWithTag("chat-model-platforms").performScrollToNode(hasTestTag("chat-platform:p1"))
        rule.onNodeWithTag("chat-platform:p1").performClick()
        rule.onNodeWithText("model-1").assertIsDisplayed()
    }
}
