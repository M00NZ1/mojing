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

class ParticipantRemoveRestoreTest {
    @get:Rule val rule = createComposeRule()
    private fun restore(completes: Boolean) {
        val original = SessionParticipantEntity(id = 9, sessionId = 42, characterId = 7)
        val participants = mutableStateOf(listOf(original))
        val busy = mutableStateOf<Set<Long>>(emptySet())
        var calls = 0
        val tester = StateRestorationTester(rule)
        tester.setContent { MaterialTheme { ParticipantsTab(
            participants = participants.value, characterNames = mapOf(7L to "来信者"),
            characterSummaries = mapOf(7L to "原角色资料"), participantRemoving = busy.value,
            onToggleMute = {}, onRemoveParticipant = { calls++; busy.value = setOf(it) }, onAddParticipant = {},
            onSpeakerTurnModeChange = {}, onUpdateTalkativeness = { _, _, _ -> },
        ) } }
        rule.onNodeWithContentDescription("来信者 发言设置").performClick()
        rule.onNodeWithContentDescription("从对话移除来信者").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithContentDescription("从对话移除来信者").assertIsNotEnabled()
        rule.onNodeWithContentDescription("来信者 发言状态").assertIsNotEnabled()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsNotEnabled()
        rule.onNodeWithText("正在移除角色…").assertIsDisplayed()
        rule.runOnIdle { if(completes) participants.value = emptyList(); busy.value = emptySet() }
        rule.waitForIdle()
        if(completes) {
            rule.onNodeWithText("请先添加至少一个角色").assertIsDisplayed()
            rule.onNodeWithContentDescription("从对话移除来信者").assertDoesNotExist()
        } else {
            rule.onNodeWithContentDescription("从对话移除来信者").assertIsEnabled()
            rule.onNodeWithContentDescription("来信者 发言状态").assertIsEnabled()
        }
        rule.onNodeWithText("正在移除角色…").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun pendingRestoresAndCommittedRemovalShowsEmptyState() = restore(true)
    @Test fun pendingRestoresAndFailureKeepsParticipant() = restore(false)
}
