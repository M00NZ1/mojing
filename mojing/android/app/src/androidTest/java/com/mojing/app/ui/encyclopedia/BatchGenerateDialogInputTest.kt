package com.mojing.app.ui.encyclopedia

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mojing.app.ui.encyclopedia.components.BatchGenerateDialog
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BatchGenerateDialogInputTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun countDefaultsToOneAndCanBeClearedAndSubmitted() {
        val submissions = mutableStateListOf<Int>()
        showDialog { _, count, _, _, _, _, _, _ -> submissions.add(count) }

        val countField = rule.onAllNodes(hasSetTextAction()).get(0)
        countField.performScrollTo().assertIsDisplayed()
        countField.assertTextContains("1")
        countField.performTextClearance()
        countField.assertTextContains("")
        countField.performTextInput("2")
        rule.onNodeWithText("加入队列").performClick()

        rule.runOnIdle { assertEquals(listOf(2), submissions.toList()) }
    }

    @Test
    fun invalidCountAndReversedWordRangeDisableSubmission() {
        val submissions = mutableStateListOf<Int>()
        showDialog { _, count, _, _, _, _, _, _ -> submissions.add(count) }

        val fields = rule.onAllNodes(hasSetTextAction())
        val countField = fields.get(0)
        countField.performTextClearance()
        countField.performTextInput("0")
        rule.onNodeWithText("加入队列").assertIsNotEnabled()

        countField.performTextClearance()
        countField.performTextInput("1")

        val minField = fields.get(1)
        val maxField = fields.get(2)
        minField.performTextClearance()
        minField.performTextInput("800")
        maxField.performTextClearance()
        maxField.performTextInput("200")
        rule.onNodeWithText("加入队列").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(emptyList<Int>(), submissions.toList()) }
    }

    @Test
    fun actionRemainsVisibleOnSmallSurface() {
        showDialog { _, _, _, _, _, _, _, _ -> }

        rule.onNodeWithText("加入队列").assertIsDisplayed()
    }

    private fun showDialog(onGenerate: (String, Int, Int, Int, String, String, Boolean, String) -> Unit) {
        rule.setContent {
            MoJingTheme {
                Surface(Modifier.fillMaxSize()) {
                    BatchGenerateDialog(worldAnchorReady = true, onDismiss = {}, onGenerate = onGenerate)
                }
            }
        }
    }
}
