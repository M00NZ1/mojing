package com.mojing.app.ui.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.ui.creation.CreationWorkspaceContent
import com.mojing.app.ui.settings.SettingsSections
import com.mojing.app.ui.encyclopedia.WorldOverviewHeader
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.ui.theme.MoJingTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class PrototypeLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "prototype-layout").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            rule.onNodeWithTag("prototype").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun creationKeepsThreeParallelEntrancesAtLargeFont() {
        var scale by mutableFloatStateOf(1f)
        rule.setContent {
            MoJingTheme(themeMode = "light", contentFontScale = scale) {
                Surface(Modifier.width(320.dp).fillMaxHeight().testTag("prototype"), color = MaterialTheme.colorScheme.background) {
                    CreationWorkspaceContent({}, Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
                }
            }
        }
        for (font in listOf(1f, 1.45f)) {
            rule.runOnIdle { scale = font }
            val cards = listOf("小说创作", "角色", "世界").map { label ->
                rule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
            assertTrue(cards[0].right < cards[1].left && cards[1].right < cards[2].left)
            rule.onNodeWithText("故事素材").assertDoesNotExist()
            capture("creation-320-$font")
        }
    }

    @Test fun settingsShowsFiveContinuousRowsAndIndependentDetailBack() {
        rule.setContent {
            MoJingTheme(themeMode = "light") {
                Surface(Modifier.width(320.dp).fillMaxHeight().testTag("prototype"), color = MaterialTheme.colorScheme.background) {
                    SettingsSections(false, {}, { it() }, {}) { Text("详情 $it") }
                }
            }
        }
        for (label in listOf("平台与模型", "创作偏好", "个性化", "用量与费用", "应用更新")) rule.onNodeWithText(label).assertIsDisplayed()
        rule.onNodeWithText("连接与创作").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回故事库").assertDoesNotExist()
        capture("settings-320")
        rule.onNodeWithText("应用更新").performClick()
        rule.onNodeWithText("详情 4").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回设置").performClick()
        rule.onNodeWithText("平台与模型").assertIsDisplayed()
    }

    @Test fun worldOverviewCanCollapseWithoutLosingIdentity() {
        rule.setContent {
            MoJingTheme(themeMode = "light") {
                Column(Modifier.width(320.dp).testTag("prototype")) {
                    WorldOverviewHeader(EncyclopediaEntity(id = 1, name = "雾港", description = "本地设定百科", entryCount = 128))
                }
            }
        }
        rule.onNodeWithText("128 条目").assertIsDisplayed()
        rule.onNodeWithContentDescription("收起世界概览").performClick()
        rule.onNodeWithText("本地设定百科").assertDoesNotExist()
        rule.onNodeWithText("雾港").assertIsDisplayed()
        rule.onNodeWithContentDescription("展开世界概览").performClick()
        rule.onNodeWithText("本地设定百科").assertIsDisplayed()
    }
}
