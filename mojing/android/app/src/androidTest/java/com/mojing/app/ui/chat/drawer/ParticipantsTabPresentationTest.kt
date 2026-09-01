package com.mojing.app.ui.chat.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.SessionParticipantEntity
import org.junit.Rule
import org.junit.Test

class ParticipantsTabPresentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun participantCardUsesReadableLabelsAndReachableActions() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ParticipantsTab(
                        participants = listOf(
                            SessionParticipantEntity(
                                id = 1L,
                                sessionId = 2L,
                                characterId = 3L,
                                speakerStrategy = "natural",
                            ),
                        ),
                        characterNames = mapOf(3L to "林墨"),
                        onToggleMute = {},
                        onUpdateTalkativeness = { _, _, done -> done(true) },
                        onRemoveParticipant = {},
                        onAddParticipant = {},
                        onSpeakerTurnModeChange = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("自然发言 · 发言率 70%").assertIsDisplayed()
        composeRule.onAllNodesWithText("natural").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("林墨 发言状态").assertIsOn()
        composeRule.onNodeWithContentDescription("从对话移除林墨")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
