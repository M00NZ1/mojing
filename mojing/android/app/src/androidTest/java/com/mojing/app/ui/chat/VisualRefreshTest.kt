package com.mojing.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
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
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
        rule.onNodeWithText("开始对话").performClick()
        rule.runOnIdle { assertTrue(clicked) }
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
