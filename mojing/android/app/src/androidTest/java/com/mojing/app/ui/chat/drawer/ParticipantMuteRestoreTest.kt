package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionParticipantEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ParticipantMuteRestoreTest {
    @get:Rule val rule = createComposeRule()
    private fun restore(completes: Boolean) {
        val original = SessionParticipantEntity(id = 9, sessionId = 42, characterId = 7)
        val participant = mutableStateOf(original)
        val busy = mutableStateOf<Set<Long>>(emptySet())
        var calls = 0
        val tester = StateRestorationTester(rule)
        tester.setContent { MaterialTheme { ParticipantsTab(
            participants = listOf(participant.value), characterNames = mapOf(7L to "来信者"),
            characterSummaries = mapOf(7L to "真实角色资料"), participantMuteSaving = busy.value,
            onToggleMute = { calls++; busy.value = setOf(it) }, onRemoveParticipant = {}, onAddParticipant = {},
            onSpeakerTurnModeChange = {}, onUpdateTalkativeness = { _, _, _ -> },
        ) } }
        rule.onNodeWithContentDescription("来信者 发言设置").performClick()
        rule.onNodeWithContentDescription("来信者 发言状态").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithContentDescription("来信者 发言状态").assertIsNotEnabled().assertIsOn()
        rule.onNodeWithContentDescription("从对话移除来信者").assertIsNotEnabled()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsNotEnabled()
        rule.onNodeWithText("参与状态保存中…").assertIsDisplayed()
        rule.runOnIdle { if(completes) participant.value = original.copy(muted = true); busy.value = emptySet() }
        rule.waitForIdle()
        val switch = rule.onNodeWithContentDescription("来信者 发言状态").assertIsEnabled()
        if(completes) switch.assertIsOff() else switch.assertIsOn()
        rule.onNodeWithContentDescription("从对话移除来信者").assertIsEnabled()
        rule.onNodeWithText("参与状态保存中…").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun pendingRestoresAndCommittedMuteUpdatesSwitch() = restore(true)
    @Test fun pendingRestoresAndFailureKeepsOriginalSwitch() = restore(false)
}
