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

class WorldSettingRestoreTest {
    @get:Rule val rule = createComposeRule()
    private val switches = listOf("旁白解说", "每轮选项", "反作弊", "自动沉淀百科", "回复内自动配图", "回复内自动配音")
    private val keys = listOf("narratorEnabled", "choiceGenerationEnabled", "antiCheatEnabled", "autoSedimentEnabled", "autoCharacterImageGen", "autoCharacterSpeech")
    private fun changed(w: SessionWorldEntity, key: String) = when(key) {
        "narratorEnabled" -> w.copy(narratorEnabled=true)
        "choiceGenerationEnabled" -> w.copy(choiceGenerationEnabled=true)
        "antiCheatEnabled" -> w.copy(antiCheatEnabled=true)
        "autoSedimentEnabled" -> w.copy(autoSedimentEnabled=true)
        "autoCharacterImageGen" -> w.copy(autoCharacterImageGen=true)
        else -> w.copy(autoCharacterSpeech=true)
    }
    private fun matrix(completes: Boolean) {
        val original=SessionWorldEntity(id=9,sessionId=42,narratorEnabled=false,choiceGenerationEnabled=false,
            antiCheatEnabled=false,autoSedimentEnabled=false,autoCharacterImageGen=false,autoCharacterSpeech=false)
        val world=mutableStateOf(original)
        val saving=mutableStateOf(false)
        var calls=0;var selected=""
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { WorldConfigTab(
            world=world.value,worldSettingSaving=saving.value,
            onWorldSettingChanged={ key,_ -> calls++;selected=key;saving.value=true },
            onSaveSessionWorldCredentials={},onCredentialFieldsDirty={},onSessionThinkMax={},
        ) } }
        switches.forEachIndexed { index,label ->
            rule.runOnIdle { world.value=original }
            rule.onNodeWithContentDescription(label+"开关").performScrollTo().assertIsEnabled().assertIsOff().performClick()
            tester.emulateSavedInstanceStateRestore()
            switches.forEach { rule.onNodeWithContentDescription(it+"开关").assertIsNotEnabled().assertIsOff() }
            rule.onNodeWithText("专用线路").performScrollTo().performClick()
            rule.onNodeWithText("保存线路").assertIsNotEnabled()
            rule.runOnIdle { assertEquals(keys[index],selected);saving.value=false;if(completes) world.value=changed(original,selected) }
            rule.waitForIdle()
            rule.onNodeWithText("保存线路").assertIsEnabled()
            rule.onNodeWithText("专用线路").performScrollTo().performClick()
            rule.onNodeWithContentDescription(label+"开关").performScrollTo().assertIsEnabled()
            if(completes) rule.onNodeWithContentDescription(label+"开关").assertIsOn()
            else rule.onNodeWithContentDescription(label+"开关").assertIsOff()
            rule.runOnIdle { assertEquals(index+1,calls) }
        }
    }
    @Test fun sixPendingSwitchesRestoreAndCommit() = matrix(true)
    @Test fun sixPendingSwitchesRestoreAndFailureReleasesPreviousChoice() = matrix(false)
    @Test fun credentialsPendingDisablesAllWorldSwitches() {
        val pending=mutableStateOf(true)
        rule.setContent { MaterialTheme { WorldConfigTab(
            world=SessionWorldEntity(id=9,sessionId=42),worldCredentialsSaving=pending.value,
            onWorldSettingChanged={ _,_->error("Busy world changed") },onSaveSessionWorldCredentials={},
            onCredentialFieldsDirty={},onSessionThinkMax={},
        ) } }
        switches.forEach { rule.onNodeWithContentDescription(it+"开关").assertIsNotEnabled() }
        rule.runOnIdle { pending.value=false }
        switches.forEach { rule.onNodeWithContentDescription(it+"开关").assertIsEnabled() }
    }
}
