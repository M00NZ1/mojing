package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.SessionWorldEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RouteDraftRestoreTest {
    @get:Rule val rule = createComposeRule()

    @Test fun allFieldsRestoreAndExternalBaselineOrWorldChangeResetsOnlyTheirScope() {
        val tester = StateRestorationTester(rule)
        val id = mutableStateOf(17L)
        val baselines = (0..9).map { mutableStateOf("saved-$it") }
        val states = arrayOfNulls<androidx.compose.runtime.MutableState<String>>(10)
        tester.setContent { repeat(10) { states[it] = rememberWorldRouteField(id.value, baselines[it].value) } }
        rule.runOnIdle { repeat(10) { states[it]!!.value = "draft-$it" } }
        tester.emulateSavedInstanceStateRestore()
        rule.runOnIdle { repeat(10) { assertEquals("draft-$it", states[it]!!.value) } }
        rule.runOnIdle { baselines[3].value = "external-save" }
        tester.emulateSavedInstanceStateRestore()
        rule.runOnIdle { repeat(10) { assertEquals(if (it == 3) "external-save" else "draft-$it", states[it]!!.value) } }
        rule.runOnIdle { id.value = 18L }
        tester.emulateSavedInstanceStateRestore()
        rule.runOnIdle { repeat(10) { assertEquals(baselines[it].value, states[it]!!.value) } }
    }

    @Test fun collapsedDirtyAndExpandedFieldsRestoreWithoutSaving() {
        val tester = StateRestorationTester(rule)
        var saves = 0
        val dirty = mutableStateOf(false)
        tester.setContent { MaterialTheme {
            WorldConfigTab(world = SessionWorldEntity(id=17,sessionId=7),
                onWorldSettingChanged={_,_->},onSaveSessionWorldCredentials={saves++},
                onCredentialFieldsDirty={dirty.value=it},onSessionThinkMax={})
        } }
        fun key() = rule.onNodeWithText("对话 API Key 覆盖").onChildren().filter(hasSetTextAction()).onFirst()
        rule.onNodeWithText("专用线路").performScrollTo().performClick()
        key().performScrollTo().performTextInput("SYNTHETIC_DRAFT")
        rule.onNodeWithText("专用线路").performScrollTo().performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("未保存").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("对话 API Key 覆盖").assertDoesNotExist()
        rule.onNodeWithText("专用线路").performScrollTo().performClick()
        key().performScrollTo().assertTextContains("SYNTHETIC_DRAFT")
        tester.emulateSavedInstanceStateRestore()
        key().performScrollTo().assertTextContains("SYNTHETIC_DRAFT")
        rule.runOnIdle { assertEquals(true,dirty.value);assertEquals(0,saves) }
    }

    @Test fun savingStateRestoresAndPreventsDuplicateSubmit() {
        val tester = StateRestorationTester(rule)
        val saving = mutableStateOf(false)
        var saves = 0
        tester.setContent { MaterialTheme {
            WorldConfigTab(world=SessionWorldEntity(id=17,sessionId=7),worldCredentialsSaving=saving.value,
                onWorldSettingChanged={_,_->},onSaveSessionWorldCredentials={saves++;saving.value=true},
                onCredentialFieldsDirty={},onSessionThinkMax={})
        } }
        rule.onNodeWithText("专用线路").performScrollTo().performClick()
        rule.onNodeWithText("保存线路").performClick()
        rule.onNodeWithText("正在保存线路…").assertIsDisplayed()
        rule.onNodeWithText("保存线路").assertIsNotEnabled()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("正在保存线路…").assertIsDisplayed()
        rule.onNodeWithText("保存线路").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1,saves);saving.value=false }
        rule.onNodeWithText("线路配置已保存").assertIsDisplayed()
        rule.onNodeWithText("保存线路").assertIsEnabled()
    }
}
