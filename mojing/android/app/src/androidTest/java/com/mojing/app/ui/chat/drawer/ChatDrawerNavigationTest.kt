package com.mojing.app.ui.chat.drawer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.SessionWorldCredentialDraft
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ChatDrawerNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private fun routeKeyField() = composeRule.onNodeWithText("对话 API Key 覆盖").onChildren().filter(hasSetTextAction()).onFirst()

    @Test
    fun tabSwitchKeepsWorldDraftUntilExplicitDiscard() {
        val world = SessionWorldEntity(id = 1L, sessionId = 7L)
        val dirty = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ChatDrawer(
                    participants = emptyList(), world = world,
                    onJumpToBookmark = {}, onRemoveBookmark = {}, onLoadMoreBookmarks = {}, onToggleMute = {},
                    onUpdateTalkativeness = { _, _, done -> done(true) },
                    onRemoveParticipant = {}, onAddParticipant = {},
                    onSpeakerTurnModeChange = {}, onClose = {},
                    onWorldSettingChanged = { _, _ -> },
                    onSaveSessionWorldCredentials = {},
                    onWorldCredentialFieldsDirty = { dirty.value = it },
                    worldCredentialFieldsDirty = dirty.value,
                    onToggleEventResolved = {}, onDeleteEventNode = {},
                    onJumpToMemorySource = { _, _ -> false }, onAddMemoryCorrection = { _, _ -> },
                    onEditMemoryCorrection = {}, onDeleteMemoryCorrection = {},
                    onRebuildContextMemory = {}, onClearContextMemory = { _ -> },
                    onContinueStorySummary = {}, onStopStorySummary = {},
                    onSessionThinkMax = {},
                )
            }
        }
        composeRule.onNodeWithText("世界").performClick()
        composeRule.onNodeWithText("专用线路").performScrollTo().performClick()
        routeKeyField().performScrollTo().performTextInput("draft-test-key")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("角色").performClick()
        composeRule.onNodeWithText("世界配置尚未保存").assertIsDisplayed()
        composeRule.onNodeWithText("继续编辑").performClick()
        routeKeyField().assertTextContains("draft-test-key")
        composeRule.onNodeWithText("角色").performClick()
        composeRule.onNodeWithText("放弃修改并切换").performClick()
        composeRule.onNodeWithText("添加角色到对话").assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(dirty.value) }
        composeRule.onNodeWithText("世界").performClick()
        composeRule.onNodeWithText("专用线路").performScrollTo().performClick()
        composeRule.onAllNodesWithText("draft-test-key").assertCountEquals(0)
        composeRule.onNodeWithText("未保存").assertDoesNotExist()
    }

    @Test
    fun eventSourceKeepsDrawerOpenUntilLoadedAndRetriesInPlace() {
        var completeLocation: ((Boolean) -> Unit)? = null
        var closes = 0
        var attempts = 0
        composeRule.setContent {
            MaterialTheme {
                ChatDrawer(
                    participants = emptyList(),
                    eventNodes = listOf(SessionEventNodeEntity(id = 1L, sessionId = 7L, messageId = 17L, title = "转折事件")),
                    onJumpToBookmark = {}, onRemoveBookmark = {}, onLoadMoreBookmarks = {}, onToggleMute = {},
                    onUpdateTalkativeness = { _, _, done -> done(true) },
                    onRemoveParticipant = {}, onAddParticipant = {}, onSpeakerTurnModeChange = {},
                    onClose = { closes++ }, onWorldSettingChanged = { _, _ -> },
                    onSaveSessionWorldCredentials = {}, onWorldCredentialFieldsDirty = {},
                    onToggleEventResolved = {}, onDeleteEventNode = {},
                    onJumpToMemorySource = { id, done ->
                        assertEquals(17L, id)
                        attempts++
                        completeLocation = done
                        true
                    },
                    onAddMemoryCorrection = { _, _ -> }, onEditMemoryCorrection = {}, onDeleteMemoryCorrection = {},
                    onRebuildContextMemory = {}, onClearContextMemory = { _ -> },
                    onContinueStorySummary = {}, onStopStorySummary = {}, onSessionThinkMax = {},
                )
            }
        }
        composeRule.onNodeWithText("事件").performClick()
        composeRule.onNodeWithText("原文消息 #17").performClick()
        composeRule.onNodeWithText("正在定位原文…").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, closes); completeLocation?.invoke(false) }
        composeRule.onNodeWithText("原文不可用或加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        composeRule.runOnIdle { assertEquals(2, attempts); completeLocation?.invoke(true) }
        composeRule.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun routeDraftSurvivesCollapsingAndUnrelatedWorldUpdates() {
        val world = mutableStateOf(SessionWorldEntity(id = 1L, sessionId = 7L, updatedAt = 10L))
        var saved: SessionWorldCredentialDraft? = null
        composeRule.setContent {
            MaterialTheme {
                WorldConfigTab(
                    world = world.value,
                    onWorldSettingChanged = { _, _ -> },
                    onSaveSessionWorldCredentials = { saved = it },
                    onCredentialFieldsDirty = {}, onSessionThinkMax = {},
                )
            }
        }
        composeRule.onNodeWithText("本场玩法").assertIsDisplayed()
        composeRule.onNodeWithText("对话 API Key 覆盖").assertDoesNotExist()
        composeRule.onNodeWithText("专用线路").performScrollTo().performClick()
        routeKeyField().performScrollTo().performTextInput("draft-route")
        composeRule.runOnIdle {
            world.value = world.value.copy(narratorEnabled = true, updatedAt = 11L)
        }
        routeKeyField().assertTextContains("draft-route")
        composeRule.onNodeWithText("专用线路").performScrollTo().performClick()
        composeRule.onNodeWithText("对话 API Key 覆盖").assertDoesNotExist()
        composeRule.onNodeWithText("专用线路").performClick()
        routeKeyField().assertTextContains("draft-route")
        composeRule.onNodeWithText("保存线路").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("draft-route", saved?.sessionLlmApiKey) }
    }

}
