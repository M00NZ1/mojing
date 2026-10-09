package com.mojing.app.ui.chat

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.ui.theme.MoJingTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in component evidence for the ReaderMessage style branches. Persistence
 * and complete-app settings/re-entry remain for the parent acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPreferencesCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    private val args get() = InstrumentationRegistry.getArguments()
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var currentFont by mutableStateOf("system")
    private var currentNarratorItalic by mutableStateOf(false)
    private var currentMessage by mutableStateOf(readerMessage("character", "第一章\n\n潮声穿过窗棂。"))
    private var contentInstalled = false

    @Test
    fun chapterTitlesFollowSelectedFontAndNarratorStyleIsVisible() {
        requireExplicitGenericCapture()
        render(font = "system", narratorItalic = false, message = readerMessage("character", "第一章\n\n潮声穿过窗棂。"))
        rule.onNodeWithTag("reader-preferences-capture-root").assertIsDisplayed()
        rule.onNodeWithText("第一章").assertIsDisplayed()
        assertTextStyle("第一章", FontFamily.Default, FontStyle.Normal)
        capture("reader-system-character")

        listOf("sans", "serif", "mono").forEach { font ->
            render(font = font, narratorItalic = false, message = readerMessage("character", "第一章\n\n潮声穿过窗棂。"))
            rule.onNodeWithText("第一章").assertIsDisplayed()
            assertTextStyle("第一章", fontFamily(font), FontStyle.Normal)
            capture("reader-$font-character")
        }

        render(font = "sans", narratorItalic = false, message = readerMessage("narrator", "旁白在潮声里记录这一页。"))
        rule.onNodeWithText("旁白在潮声里记录这一页。").assertIsDisplayed()
        assertTextStyle("旁白在潮声里记录这一页。", FontFamily.SansSerif, FontStyle.Normal)
        capture("reader-sans-narrator-normal")

        render(font = "sans", narratorItalic = true, message = readerMessage("narrator", "旁白在潮声里记录这一页。"))
        rule.onNodeWithText("旁白在潮声里记录这一页。").assertIsDisplayed()
        assertTextStyle("旁白在潮声里记录这一页。", FontFamily.SansSerif, FontStyle.Italic)
        capture("reader-sans-narrator-italic")

        render(font = "mono", narratorItalic = true, message = readerMessage("user", "用户台词保留正常字形。"))
        rule.onNodeWithText("用户台词保留正常字形。").assertIsDisplayed()
        assertTextStyle("用户台词保留正常字形。", FontFamily.Monospace, FontStyle.Normal)
        capture("reader-mono-user-normal")

        render(font = "serif", narratorItalic = true, message = readerMessage("narrator", "第一节\n\n标题不随旁白开关倾斜。"))
        rule.onNodeWithText("第一节").assertIsDisplayed()
        assertTextStyle("第一节", FontFamily.Serif, FontStyle.Normal)
        capture("reader-serif-narrator-title-normal")
    }

    private fun render(font: String, narratorItalic: Boolean, message: MessageEntity) {
        currentFont = font
        currentNarratorItalic = narratorItalic
        currentMessage = message
        if (!contentInstalled) {
            contentInstalled = true
            rule.setContent {
                MoJingTheme(themeMode = args.getString("captureTheme") ?: "dark") {
                    CompositionLocalProvider(
                        LocalChatDensityMetrics provides ChatDensityMode.Reader.toMetrics(),
                        LocalChatReadingStyle provides ChatReadingStyle(currentFont, currentNarratorItalic),
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f),
                    ) {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                .width(320.dp)
                                .testTag("reader-preferences-capture-root"),
                        ) {
                            ReaderMessage(currentMessage, emptyList()) {}
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertTextStyle(text: String, family: FontFamily, style: FontStyle) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("font family for $text", family, layouts.single().layoutInput.style.fontFamily)
        assertEquals("font style for $text", style, layouts.single().layoutInput.style.fontStyle ?: FontStyle.Normal)
    }

    private fun fontFamily(font: String): FontFamily = when (font) {
        "sans" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.Default
    }

    private fun capture(name: String) {
        val runName = args.getString("captureRun") ?: "reader-preferences-20261006"
        require(runName.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe capture run name" }
        val directory = File(targetContext.getExternalFilesDir(null), runName).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(rule.onNodeWithTag("reader-preferences-capture-root").captureToImage()
                .asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "screenshot encoding failed for $name"
            }
        }
    }

    private fun requireExplicitGenericCapture() {
        assumeTrue(
            "readerPreferencesCapture=true is required",
            args.getString("readerPreferencesCapture") == "true",
        )
        val generic = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.contains("emulator", ignoreCase = true)
        assumeTrue("reader preferences capture is restricted to a generic emulator", generic)
    }

    private fun readerMessage(speakerType: String, content: String) = MessageEntity(
        id = if (speakerType == "narrator") 2L else 1L,
        sessionId = 1L,
        speakerType = speakerType,
        content = content,
    )
}
