package com.mojing.app.ui.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.ui.common.MoJingTextField
import com.mojing.app.ui.creation.CreationWorkspaceContent
import com.mojing.app.ui.navigation.Routes
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkspaceDesignTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun prepare() { rule.runOnUiThread { rule.activity.actionBar?.hide() } }

    @Test fun creationActionsStayReachableOnNarrowScreens() {
        val opened = mutableListOf<String>()
        val theme = mutableStateOf("dark")
        rule.setContent {
            MoJingTheme(themeMode = theme.value) {
                Surface(Modifier.fillMaxSize().testTag("workspace"), color = MaterialTheme.colorScheme.background) {
                    CreationWorkspaceContent({ opened.add(it) },
                        Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(16.dp))
                }
            }
        }
        for (mode in listOf("dark", "light")) {
            rule.runOnIdle { theme.value = mode }
            rule.onNodeWithText("小说创作").performScrollTo().assertIsDisplayed()
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "workspace-design").apply { mkdirs() }
            File(directory, "creation-$mode.png").outputStream().use {
                rule.onNodeWithTag("workspace").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        for (label in listOf("小说创作", "角色", "世界")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        rule.runOnIdle { assertEquals(listOf(Routes.STORY_SIMULATION, Routes.CHARACTER_LIST,
            Routes.ENCYCLOPEDIA_LIST), opened) }
    }

    @Test fun longModelNamesKeepOneReachableSwitchAction() {
        var opened = 0
        rule.setContent {
            MoJingTheme {
                Box(Modifier.width(200.dp).padding(8.dp)) {
                    ChatModelTitle("雾港来信与灯塔深处的秘密", "deepseek-chat-long-model-name", true, { opened++ })
                }
            }
        }
        rule.onNodeWithText("雾港来信与灯塔深处的秘密").assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        rule.onNodeWithContentDescription("切换模型").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun filledFieldsKeepSelectionPasswordAndErrorSemantics() {
        var text by mutableStateOf(TextFieldValue("雾港来信", TextRange(2, 4)))
        rule.setContent {
            MoJingTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    MoJingTextField(text, { text = it }, label = { Text("故事名称") },
                        modifier = Modifier.fillMaxWidth().testTag("name"))
                    MoJingTextField("private-test-value", {}, label = { Text("访问密钥") },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("secret"))
                    MoJingTextField("", {}, label = { Text("必填项") }, isError = true,
                        supportingText = { Text("请输入名称") }, modifier = Modifier.testTag("error"))
                    MoJingTextField("只读资料", {}, readOnly = true, modifier = Modifier.testTag("readonly"))
                    MoJingTextField("不可编辑", {}, enabled = false, modifier = Modifier.testTag("disabled"))
                }
            }
        }
        fun field(tag: String) = rule.onNode(hasAnyAncestor(hasTestTag(tag)) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.EditableText), useUnmergedTree = true)
        field("name").performTextInput("夜话")
        rule.runOnIdle { assertEquals("雾港夜话", text.text) }
        field("secret").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        rule.onNodeWithText("请输入名称").assertIsDisplayed()
        field("disabled").assertIsNotEnabled()
        field("readonly").assertTextEquals("只读资料")
    }
}
