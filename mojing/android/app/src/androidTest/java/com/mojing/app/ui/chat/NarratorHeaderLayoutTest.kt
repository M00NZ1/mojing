package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NarratorHeaderLayoutTest {
    @get:Rule val rule = createComposeRule()
    private data class Case(val width: Int, val scale: Float, val dark: Boolean, val long: Boolean)
    @Test fun namesLeaveSingleReadableTimestampAcrossNarrowWidthsThemesAndFontScales() {
        val state = mutableStateOf(Case(280, 1f, false, true))
        val longName = "守望北塔的记录者与见证者".repeat(4)
        val time = "2025/1/1 08:23"
        val body = "这里是完整旁白正文，名称截断不会影响剧情。"
        rule.setContent {
            val c = state.value
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, c.scale)) {
                MaterialTheme(colorScheme = if (c.dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.width(c.width.dp).testTag("viewport")) {
                        NarratorMessageBubble(MessageEntity(id = 1, sessionId = 1, speakerType = "narrator", content = body),
                            senderLabel = if (c.long) longName else "旁白", timeText = time, showHeader = true)
                    }
                }
            }
        }
        for (width in listOf(280, 360)) for (scale in listOf(1f, 1.5f)) for (dark in listOf(false, true)) for (long in listOf(false, true)) {
            rule.runOnIdle { state.value = Case(width, scale, dark, long) }
            val name = if (long) longName else "旁白"
            val nameLayout = mutableListOf<TextLayoutResult>(); val timeLayout = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(name).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(nameLayout) }
            rule.onNodeWithText(time).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(timeLayout) }
            assertEquals(1, nameLayout.single().lineCount)
            assertEquals(1, timeLayout.single().lineCount)
            val tl = timeLayout.single()
            // A cached paragraph can retain a wider constraint after short-name remeasurement:
            // paragraph=400px, glyph edge=172px, measured box=173px. Check actual glyph coverage.
            assertEquals(time.length, tl.getLineEnd(0))
            assertTrue("timestamp glyphs clipped at $width/$scale", tl.getLineLeft(0) >= 0f && tl.getLineRight(0) <= tl.size.width)
            assertFalse(tl.didOverflowHeight)
            if (long) assertTrue(nameLayout.single().isLineEllipsized(0))
            val viewport = rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val nameBounds = rule.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
            val timeBounds = rule.onNodeWithText(time).fetchSemanticsNode().boundsInRoot
            assertTrue(nameBounds.right <= timeBounds.left)
            assertTrue(timeBounds.right <= viewport.right)
            rule.onNodeWithText(body).assertIsDisplayed()
            rule.onNodeWithContentDescription("叙述者头像").assertIsDisplayed()
        }
    }
}
