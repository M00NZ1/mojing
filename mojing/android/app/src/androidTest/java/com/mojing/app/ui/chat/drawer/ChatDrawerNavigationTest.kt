package com.mojing.app.ui.chat.drawer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mojing.app.data.local.entity.SessionWorldEntity
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ChatDrawerNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tabSwitchKeepsWorldDraftUntilExplicitDiscard() {
        val world = SessionWorldEntity(id = 1L, sessionId = 7L)
        val dirty = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ChatDrawer(
                    participants = emptyList(), world = world,
                    onJumpToBookmark = {}, onRemoveBookmark = {}, onToggleMute = {},
                    onUpdateTalkativeness = { _, _, done -> done(true) },
                    onRemoveParticipant = {}, onAddParticipant = {},
                    onSpeakerTurnModeChange = {}, onClose = {},
                    onWorldSettingChanged = { _, _ -> },
                    onSaveSessionWorldCredentials = {},
                    onWorldCredentialFieldsDirty = { dirty.value = it },
                    worldCredentialFieldsDirty = dirty.value,
                    onToggleEventResolved = {}, onDeleteEventNode = {},
                    onJumpToMemorySource = {}, onAddMemoryCorrection = { _, _ -> },
                    onEditMemoryCorrection = {}, onDeleteMemoryCorrection = {},
                    onRebuildContextMemory = {}, onClearContextMemory = {},
                    onSessionThinkMax = {},
                )
            }
        }
        composeRule.onNodeWithText("世界").performClick()
        composeRule.onNodeWithText("对话 API Key 覆盖").performTextInput("draft-test-key")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("角色").performClick()
        composeRule.onNodeWithText("世界配置尚未保存").assertIsDisplayed()
        composeRule.onNodeWithText("继续编辑").performClick()
        composeRule.onNodeWithText("对话 API Key 覆盖").assertTextContains("draft-test-key")
        composeRule.onNodeWithText("角色").performClick()
        composeRule.onNodeWithText("放弃修改并切换").performClick()
        composeRule.onNodeWithText("添加角色到对话").assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(dirty.value) }
        composeRule.onNodeWithText("世界").performClick()
        composeRule.onAllNodesWithText("draft-test-key").assertCountEquals(0)
        composeRule.onNodeWithText("未保存").assertDoesNotExist()
    }
}
