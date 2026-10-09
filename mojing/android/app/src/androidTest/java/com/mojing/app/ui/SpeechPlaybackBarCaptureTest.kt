package com.mojing.app.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.media.newmedia.SpeechPlaybackControl
import com.mojing.app.ui.chat.SpeechPlaybackBar
import com.mojing.app.ui.theme.MoJingTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in component evidence only. This verifies the local playback bar's
 * layout and callbacks; it does not verify audio output or a speech provider.
 */
@RunWith(AndroidJUnit4::class)
class SpeechPlaybackBarCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var contentInstalled = false
    private var currentTheme by mutableStateOf("light")
    private var currentSnapshot by mutableStateOf(SpeechPlaybackControl.Snapshot())
    private var currentOnPause by mutableStateOf<() -> Unit>({})
    private var currentOnResume by mutableStateOf<() -> Unit>({})
    private var currentOnStop by mutableStateOf<() -> Unit>({})

    @Test
    fun captureLightAndDarkPlaybackStatesAtNarrowLargeText() {
        requireExplicitGenericCapture()
        val pauseCalls = AtomicInteger(0)
        val resumeCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)

        render(
            theme = "light",
            snapshot = SpeechPlaybackControl.Snapshot(
                phase = SpeechPlaybackControl.Phase.PREPARING,
                segmentIndex = 2,
                segmentCount = 12,
            ),
        )
        rule.onNodeWithText("准备朗读").assertIsDisplayed()
        rule.onNodeWithText("第2/12段").assertIsDisplayed()
        rule.onNodeWithText("本段从头继续").assertDoesNotExist()
        rule.onNodeWithContentDescription("暂停朗读").assertIsEnabled()
        rule.onNodeWithContentDescription("继续朗读").assertIsNotEnabled()
        rule.onNodeWithContentDescription("停止朗读").assertIsEnabled()
        capture("light-preparing")

        render(
            theme = "light",
            snapshot = SpeechPlaybackControl.Snapshot(
                phase = SpeechPlaybackControl.Phase.PLAYING,
                segmentIndex = 2,
                segmentCount = 12,
            ),
            onPause = { pauseCalls.incrementAndGet() },
            onResume = { resumeCalls.incrementAndGet() },
            onStop = { stopCalls.incrementAndGet() },
        )
        rule.onNodeWithText("正在朗读").assertIsDisplayed()
        rule.onNodeWithText("Azure", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("暂停朗读").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("继续朗读").assertIsNotEnabled()
        assertTrue("pause callback", pauseCalls.get() == 1)
        assertControlBoundsDoNotOverlap()
        capture("light-playing")

        render(
            theme = "light",
            snapshot = SpeechPlaybackControl.Snapshot(
                phase = SpeechPlaybackControl.Phase.PAUSED,
                segmentIndex = 2,
                segmentCount = 12,
                resumedFromSegmentStart = true,
            ),
            onPause = { pauseCalls.incrementAndGet() },
            onResume = { resumeCalls.incrementAndGet() },
            onStop = { stopCalls.incrementAndGet() },
        )
        rule.onNodeWithText("已暂停").assertIsDisplayed()
        rule.onNodeWithText("本段从头继续").assertIsDisplayed()
        rule.onNodeWithContentDescription("继续朗读").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("停止朗读").performClick()
        assertTrue("resume callback", resumeCalls.get() == 1)
        assertTrue("stop callback", stopCalls.get() == 1)
        capture("light-paused-fallback")

        render(
            theme = "dark",
            snapshot = SpeechPlaybackControl.Snapshot(
                phase = SpeechPlaybackControl.Phase.PREPARING,
                segmentIndex = 0,
                segmentCount = 0,
            ),
        )
        rule.onNodeWithText("准备朗读").assertIsDisplayed()
        rule.onNodeWithText("第0/0段").assertDoesNotExist()
        capture("dark-preparing")

        render(
            theme = "dark",
            snapshot = SpeechPlaybackControl.Snapshot(
                phase = SpeechPlaybackControl.Phase.PLAYING,
                segmentIndex = 2,
                segmentCount = 12,
            ),
        )
        capture("dark-playing")

        render(
            theme = "dark",
            snapshot = SpeechPlaybackControl.Snapshot(
                phase = SpeechPlaybackControl.Phase.PAUSED,
                segmentIndex = 2,
                segmentCount = 12,
                resumedFromSegmentStart = true,
            ),
        )
        rule.onNodeWithText("已暂停").assertIsDisplayed()
        rule.onNodeWithText("本段从头继续").assertIsDisplayed()
        capture("dark-paused-fallback")
    }

    private fun render(
        theme: String,
        snapshot: SpeechPlaybackControl.Snapshot,
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        currentTheme = theme
        currentSnapshot = snapshot
        currentOnPause = onPause
        currentOnResume = onResume
        currentOnStop = onStop
        if (!contentInstalled) {
            contentInstalled = true
            rule.setContent {
                MoJingTheme(themeMode = currentTheme) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f),
                ) {
                    Box(Modifier.width(320.dp)) {
                        SpeechPlaybackBar(
                            snapshot = currentSnapshot,
                            voiceRequestLabel = "Azure · 中文女声 · 很长的来源名称用于验证窄屏省略",
                            onPause = currentOnPause,
                            onResume = currentOnResume,
                            onStop = currentOnStop,
                        )
                    }
                }
            }
            }
        }
        rule.waitForIdle()
    }

    private fun assertControlBoundsDoNotOverlap() {
        val descriptions = listOf("暂停朗读", "继续朗读", "停止朗读")
        val bounds = descriptions.map { description ->
            val node = rule.onNodeWithContentDescription(description)
            val rect = node.getUnclippedBoundsInRoot()
            // Pixel-to-dp conversion can produce 47.99999dp for an exact 48dp target.
            assertTrue("$description must keep a 48dp target: $rect", rect.right - rect.left >= 47.9.dp && rect.bottom - rect.top >= 47.9.dp)
            rect
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("playback controls overlap", left.right <= right.left || right.right <= left.left)
        }
    }

    private fun capture(name: String) {
        val runName = args.getString("speechPlaybackBarCaptureRun") ?: "speech-playback-bar-capture"
        require(runName.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe capture run name" }
        val directory = File(targetContext.getExternalFilesDir(null), runName).apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun requireExplicitGenericCapture() {
        assumeTrue(
            "speechPlaybackBarCapture=true is required",
            args.getString("speechPlaybackBarCapture") == "true",
        )
        val generic = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.contains("emulator", ignoreCase = true)
        assumeTrue("speech playback bar capture is restricted to a generic emulator", generic)
    }
}
