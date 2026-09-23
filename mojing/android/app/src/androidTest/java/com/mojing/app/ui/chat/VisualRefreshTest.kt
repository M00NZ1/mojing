package com.mojing.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import com.mojing.app.ui.common.LlmKeySetupHintCard
import com.mojing.app.ui.common.ConfirmDialog
import com.mojing.app.ui.common.SearchBar
import com.mojing.app.ui.common.ImeHideAwareNavigationBar
import com.mojing.app.ui.navigation.MoJingNavItem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.ui.common.MoJingTextField
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingOutlinedButton
import com.mojing.app.ui.splash.InkBrandSplashOverlay
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.io.File

class VisualRefreshTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun hideTestActivityActionBar() {
        rule.runOnUiThread { rule.activity.actionBar?.hide() }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "visual-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            rule.onNodeWithTag("visual-review").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun themedControlsRemainReadableAndEditable() {
        val mode = mutableStateOf("dark")
        val value = mutableStateOf("")
        var palette: ColorScheme? = null
        var clicked = false
        rule.setContent {
            MoJingTheme(themeMode = mode.value) {
                palette = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize().testTag("visual-review"), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("墨境", style = MaterialTheme.typography.headlineLarge)
                        Text("每一段对话，都通往新的世界。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoJingTextField(value.value, { value.value = it }, label = { Text("角色名称") },
                            placeholder = { Text("为故事中的角色命名") }, modifier = Modifier.fillMaxWidth())
                        MoJingTextField("灯塔外的雨还没有停。", {}, label = { Text("开场场景") },
                            minLines = 3, modifier = Modifier.fillMaxWidth())
                        MoJingButton({ clicked = true }, modifier = Modifier.fillMaxWidth()) { Text("开始对话") }
                        MoJingOutlinedButton({}, modifier = Modifier.fillMaxWidth()) { Text("导入角色") }
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("我会在灯塔等你。", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
        for (theme in listOf("dark", "light", "midnight", "rose", "ocean", "mint", "blush", "sky")) {
            rule.runOnIdle { mode.value = theme }
            rule.waitForIdle()
            rule.runOnIdle {
                val c = checkNotNull(palette)
                for ((fg, bg) in listOf(c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer,
                    c.onSurfaceVariant to c.surfaceContainerLow, c.onErrorContainer to c.errorContainer)) {
                    assertTrue("Insufficient text contrast: $theme", ColorUtils.calculateContrast(fg.toArgb(), bg.toArgb()) >= 4.5)
                }
            }
            if (theme == "dark" || theme == "light") capture("controls-$theme")
        }
        rule.onNodeWithText("角色名称").performTextInput("林汐")
        rule.onNodeWithText("林汐").assertExists()
        rule.onNodeWithText("开始对话").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(clicked) }
    }

    @Test fun navigationSelectionAndSearchClearRemainAccessible() {
        var query by mutableStateOf("雾港")
        var selected by mutableStateOf("对话")
        rule.setContent {
            MoJingTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                Column(Modifier.width(320.dp)) {
                    SearchBar(query, { query = it })
                    ImeHideAwareNavigationBar {
                        listOf("对话", "创作", "设置").forEach { label ->
                            MoJingNavItem(selected == label, { selected = label }, Icons.Default.Create, label)
                        }
                    }
                }
                }
            }
        }
        rule.onNodeWithContentDescription("清空搜索").performClick()
        rule.runOnIdle { assertTrue(query.isEmpty()) }
        rule.onNodeWithContentDescription("清空搜索").assertDoesNotExist()
        rule.onNodeWithText("创作").assertIsNotSelected().performClick().assertIsSelected()
        rule.onNodeWithText("对话").assertIsNotSelected().assertIsDisplayed()
        rule.onNodeWithText("设置").assertIsDisplayed().performClick().assertIsSelected()
        rule.onNodeWithText("创作").assertIsNotSelected()
    }

    @Test fun longConfirmationKeepsItsActionVisible() {
        var confirmed = false
        rule.setContent {
            MoJingTheme {
                ConfirmDialog("继续整理", "长说明，保留当前内容。\n".repeat(200),
                    onConfirm = { confirmed = true }, onDismiss = {})
            }
        }
        rule.onNodeWithText("确认").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(confirmed) }
        rule.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test fun setupCardKeepsLongGuidanceAndOneAccessibleAction() {
        var opened = 0
        var actionable by mutableStateOf(true)
        val message = "补全内容或生成封面前，请先在设置填写 API Key；手动编辑和保存不受影响"
        rule.setContent {
            MoJingTheme {
                Surface(Modifier.fillMaxSize().testTag("visual-review")) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        LlmKeySetupHintCard(message, { opened++ }, showActionButton = actionable)
                    }
                }
            }
        }
        rule.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        rule.onNodeWithText("配置模型服务").performClick()
        rule.runOnIdle { assertTrue(opened == 1) }
        capture("model-setup")
        rule.runOnIdle { actionable = false }
        rule.onNodeWithText(message).assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun splashKeepsTheOriginalTagline() {
        rule.setContent {
            MoJingTheme {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    Box(Modifier.fillMaxSize().testTag("visual-review")) { InkBrandSplashOverlay {} }
                }
            }
        }
        rule.onNodeWithText("以墨为界，入境如梦。").assertIsDisplayed()
        capture("splash")
    }
}
