package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.story.NovelChapter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated rendering evidence; formal native App and rollback are verified separately. */
class ReaderNamedHeadingStyleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun namedHeadingUsesReaderScaleAndRefreshesAfterMetadataChange() {
        val title = "灯塔来信"
        var message by mutableStateOf(MessageEntity(id=3, sessionId=7, speakerType="narrator",
            content="<NARRATION>$title\n\n港口正文</NARRATION>",
            structuredContentJson=NovelChapter.metadata("{}",30,title)))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalChatReadingStyle provides ChatReadingStyle(font="serif")) {
                ReaderMessage(message, emptyList(), onImageClick={})
            }
        } }
        fun style(text:String): androidx.compose.ui.text.TextStyle {
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text,useUnmergedTree=true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(22.sp, style(title).fontSize)
        assertEquals(30.sp, style(title).lineHeight)
        assertEquals(FontWeight.SemiBold, style(title).fontWeight)
        assertEquals(FontFamily.Serif, style(title).fontFamily)
        assertNotEquals(22.sp, style("港口正文").fontSize)
        // Same ID/content, metadata no longer agrees with the first source paragraph.
        compose.runOnIdle { message=message.copy(structuredContentJson=NovelChapter.metadata("{}",30,"其他标题")) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        assertNotEquals(22.sp, style(title).fontSize)
        assertEquals("<NARRATION>$title\n\n港口正文</NARRATION>",message.content)
    }
}
