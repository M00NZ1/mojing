package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingTextField
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Rule
import org.junit.Test

class SharedFormControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collapsedGroupHeaderOpensBoundedMultilineField() {
        compose.setContent {
            MoJingTheme(themeMode = "sky") {
                var expanded by remember { mutableStateOf(false) }
                var text by remember { mutableStateOf("") }
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    ExpandableSettingsCard("剧情设置", expanded, { expanded = !expanded }) {
                        MoJingTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("field"),
                            label = { Text("剧情方向") }, minLines = 2, maxLines = 5)
                        Text("底部操作")
                    }
                }
            }
        }
        compose.onNodeWithTag("field").assertDoesNotExist()
        compose.onNodeWithText("剧情设置").performClick()
        compose.onNodeWithTag("field").performTextInput((1..80).joinToString("\n") { "第${it}行故事内容" })
        compose.onNodeWithText("底部操作").assertIsDisplayed()
        compose.onNodeWithText("剧情设置").performClick()
        compose.onNodeWithTag("field").assertDoesNotExist()
    }
}
