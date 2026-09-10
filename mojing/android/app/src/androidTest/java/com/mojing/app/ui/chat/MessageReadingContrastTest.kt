package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.junit.Before
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.ui.theme.AppThemes
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.floor
import kotlin.math.ceil

class MessageReadingContrastTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun hideActionBar() { rule.activityRule.scenario.onActivity { it.actionBar?.hide() } }

    @Test fun allThemesKeepMessageAndStoryTextReadable() {
        val theme = mutableStateOf("dark")
        lateinit var colors: ColorScheme
        rule.setContent {
            MoJingTheme(themeMode = theme.value) {
                colors = MaterialTheme.colorScheme
                Surface(color = colors.background) {
                    Column(Modifier.width(280.dp)) {
                        MessageBubble(MessageEntity(id = 1, sessionId = 1, speakerType = "user",
                            content = "> 旁白：原文\n\n正文测试"))
                        MessageBubble(MessageEntity(id = 2, sessionId = 1, speakerType = "character",
                            content = "<THOUGHT>心声测试</THOUGHT><NARRATION>场景测试</NARRATION><SPEECH>对话测试</SPEECH>"))
                    }
                }
            }
        }
        for (id in AppThemes.ORDER) {
            rule.runOnIdle { theme.value = id }
            rule.waitForIdle()
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val samples = listOf(
                "正文测试" to colors.primaryContainer,
                "引用 · 旁白：原文" to colors.surfaceContainerHigh,
                "💭 心声测试" to colors.background,
                "🎭 场景测试" to colors.surfaceContainer,
                "对话测试" to colors.surfaceContainerHigh,
            )
            for ((text, background) in samples) {
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("$id $text has a text layout", layouts.isNotEmpty())
                // Text may apply color through its draw callback rather than TextLayoutResult.
                // Sample the final Chinese glyph, excluding the colored emoji prefix.
                val bounds = layouts.first().getBoundingBox(text.lastIndex)
                val pixels = rule.onNodeWithText(text, useUnmergedTree = true).captureToImage().toPixelMap()
                var ratio = 1f
                for (y in floor(bounds.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom).toInt().coerceAtMost(pixels.height)) {
                    for (x in floor(bounds.left).toInt().coerceAtLeast(0) until ceil(bounds.right).toInt().coerceAtMost(pixels.width)) {
                        val foreground = pixels[x, y]
                        val high = maxOf(foreground.luminance(), background.luminance())
                        val low = minOf(foreground.luminance(), background.luminance())
                        ratio = maxOf(ratio, (high + .05f) / (low + .05f))
                    }
                }
                if (ratio < 4.5f) {
                    val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "contrast-$id.png")
                    out.outputStream().use { rule.onNodeWithText(text, useUnmergedTree = true).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                    println("$id $text bounds=$bounds image=${pixels.width}x${pixels.height} bg=$background style=${layouts.first().layoutInput.style.color}")
                }
                assertTrue("$id $text contrast = $ratio", ratio >= 4.5f)
            }
        }
    }
}
