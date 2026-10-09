package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionParticipantEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TalkativenessRestoreTest {
    @get:Rule val rule = createComposeRule()
    private fun restore(completes: Boolean) {
        val original = SessionParticipantEntity(id = 9, sessionId = 42, characterId = 7, talkativeness = 0.7f)
        val participant = mutableStateOf(original)
        val pending = mutableStateOf<Map<Long, Float>>(emptyMap())
        var calls = 0
        val tester = StateRestorationTester(rule)
        tester.setContent { MaterialTheme { ParticipantsTab(
            participants = listOf(participant.value), characterNames = mapOf(7L to "来信者"),
            characterSummaries = mapOf(7L to "真实资料摘要"),
            onToggleMute = {}, onRemoveParticipant = {}, onAddParticipant = {}, onSpeakerTurnModeChange = {},
            participantTalkativenessSaving = pending.value,
            onUpdateTalkativeness = { id, value, _ -> calls++; pending.value = mapOf(id to value) },
        ) } }
        rule.onNodeWithContentDescription("来信者 发言设置").performClick()
        fun slider() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(mapOf(9L to 0.25f), pending.value); assertEquals(1, calls) }
        tester.emulateSavedInstanceStateRestore()
        slider().assertIsNotEnabled()
        slider().assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo(0.25f, 0.05f..1f)))
        rule.onNodeWithText("发言率保存中…").assertIsDisplayed()
        rule.runOnIdle {
            if (completes) participant.value = original.copy(talkativeness = 0.25f)
            pending.value = emptyMap()
        }
        rule.waitForIdle()
        slider().assertIsEnabled()
        slider().assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo(if (completes) 0.25f else 0.7f, 0.05f..1f)))
        rule.onNodeWithText("发言率保存中…").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun pendingRestoresAndCommittedValueRemainsVisible() = restore(true)
    @Test fun pendingRestoresAndFailureReturnsToPersistedValue() = restore(false)
}
