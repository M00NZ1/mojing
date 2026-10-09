package com.mojing.app.ui.theme

import android.graphics.Bitmap
import android.os.Build
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.ui.character.components.CardImageCropSheet
import com.mojing.app.ui.common.ImagePreviewDialog
import com.mojing.app.ui.common.MoJingLongTextField
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * Opt-in focused Dialog Window appearance checks. This is a component host;
 * it does not exercise MainActivity navigation, Room, preferences, or APIs.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenWindowAppearanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        assumeTrue("fullScreenBars=true is required", args.getString("fullScreenBars") == "true")
        assumeTrue("full-screen appearance capture is restricted to a generic emulator", isGenericEmulator())
        assumeTrue("WindowInspector/systemBarsAppearance requires API 30+", Build.VERSION.SDK_INT >= 30)
    }

    @Test
    fun longTextFieldDialogLightAndCloseRestoreActivityWindow() {
        verifyLongTextField("light")
    }

    @Test
    fun longTextFieldDialogDarkAndCloseRestoreActivityWindow() {
        verifyLongTextField("dark")
    }

    @Test
    fun blackImagePreviewDialogLightAndCloseRestoreActivityWindow() {
        verifyImagePreview("light")
    }

    @Test
    fun blackImagePreviewDialogDarkAndCloseRestoreActivityWindow() {
        verifyImagePreview("dark")
    }

    @Test
    fun cardCropDialogLightAndCloseRestoreActivityWindow() {
        verifyCropSheet("light")
    }

    @Test
    fun cardCropDialogDarkAndCloseRestoreActivityWindow() {
        verifyCropSheet("dark")
    }

    private fun verifyLongTextField(theme: String) {
        setHost(theme) {
            var value by remember { mutableStateOf("") }
            MoJingLongTextField(value, { value = it }, "长文本", "请输入", Modifier.fillMaxSize())
        }
        waitForActivityWindow(theme)
        rule.onAllNodesWithText("全屏编辑").onFirst().performClick()
        waitForDialogWindow()
        assertFocusedWindowAppearance(theme, dialog = true)
        capture("long-text-$theme")
        rule.onAllNodesWithText("完成").onFirst().performClick()
        waitForActivityWindow(theme)
    }

    private fun verifyImagePreview(theme: String) {
        val showing = mutableStateOf(true)
        setHost(theme) { if (showing.value) ImagePreviewDialog("content://full-screen-test/missing", { showing.value = false }) }
        onDialogTag("image_preview").assertIsDisplayed()
        waitForDialogWindow()
        assertFocusedWindowAppearance("dark", dialog = true)
        capture("black-preview-$theme")
        rule.onAllNodesWithContentDescription("关闭图片预览").onFirst().performClick()
        waitForActivityWindow(theme)
    }

    private fun verifyCropSheet(theme: String) {
        val bitmap = Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
        val showing = mutableStateOf(true)
        setHost(theme) {
            if (showing.value) CardImageCropSheet(bitmap, { showing.value = false }, { _, _, _, _, _ -> })
        }
        rule.onAllNodesWithText("2∶3 封面 · 拖动 / 双指缩放").onFirst().assertIsDisplayed()
        waitForDialogWindow()
        assertFocusedWindowAppearance(theme, dialog = true)
        capture("card-crop-$theme")
        rule.onAllNodesWithContentDescription("关闭").onFirst().performClick()
        waitForActivityWindow(theme)
        bitmap.recycle()
    }

    @Composable
    private fun HostContent(theme: String, content: @Composable () -> Unit) {
        MoJingTheme(themeMode = theme) {
            SystemBarAppearance(rule.activity, MaterialTheme.colorScheme.background, splashVisible = false)
            content()
        }
    }

    private fun setHost(theme: String, content: @Composable () -> Unit) {
        rule.setContent { HostContent(theme, content) }
        rule.waitForIdle()
    }

    private fun onDialogTag(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun waitForDialogWindow() {
        rule.waitUntil(5_000) { focusedWindow(isDialog = true) != null }
        rule.waitForIdle()
    }

    private fun waitForActivityWindow(theme: String) {
        rule.waitUntil(5_000) { focusedWindow(isDialog = false) != null }
        rule.waitForIdle()
        assertFocusedWindowAppearance(theme, dialog = false)
    }

    private fun assertFocusedWindowAppearance(theme: String, dialog: Boolean) {
        val view = focusedWindow(dialog) ?: error("focused ${if (dialog) "Dialog" else "Activity"} window unavailable")
        val controller = view.windowInsetsController ?: error("focused window has no WindowInsetsController")
        val expectedLight = theme == "light"
        val appearance = controller.systemBarsAppearance
        val lightStatus = appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0
        val lightNavigation = appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0
        check(lightStatus == expectedLight) { "status bar flags=$appearance theme=$theme dialog=$dialog" }
        check(lightNavigation == expectedLight) { "navigation bar flags=$appearance theme=$theme dialog=$dialog" }
    }

    private fun focusedWindow(isDialog: Boolean): android.view.View? {
        val activityToken = rule.activity.window.decorView.windowToken
        return WindowInspector.getGlobalWindowViews()
            .filter { it.hasWindowFocus() }
            .firstOrNull { (it.windowToken != activityToken) == isDialog }
    }

    private fun capture(name: String) {
        val raw = args.getString("captureRun") ?: "fullscreen-bars-20261006"
        require(raw.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), raw).apply { mkdirs() }
        val file = File(directory, "$name.png")
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        screenshot.recycle()
        val active = focusedWindow(true) ?: error("focused Dialog disappeared before capture")
        File(directory, "$name-window.txt").writeText("focusedDialogAppearance=${active.windowInsetsController?.systemBarsAppearance}")
    }

    private fun isGenericEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.contains("emulator", ignoreCase = true)
}
