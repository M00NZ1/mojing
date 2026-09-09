package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessageBubbleLayoutTest {
    @get:Rule val rule = createComposeRule()
    @Test fun longUserMessagePreservesAvatarAndThemeTextColorOnNarrowScreen() {
        val dark = mutableStateOf(false)
        val text = "这是一段足够长的用户消息，用于检查窄屏头像和文字。".repeat(8)
        rule.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.width(280.dp)) {
                    MessageBubble(MessageEntity(id = 1L, sessionId = 1L, speakerType = "user", content = text),
                        userAvatarImagePath = "/missing-test-avatar.png")
                }
            }
        }
        for (isDark in listOf(false, true)) {
            rule.runOnIdle { dark.value = isDark }
            rule.onNodeWithContentDescription("我的头像")
                .assertIsDisplayed().assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertEquals((if (isDark) darkColorScheme() else lightColorScheme()).onPrimaryContainer,
                layouts.first().layoutInput.style.color)
        }
    }
}
