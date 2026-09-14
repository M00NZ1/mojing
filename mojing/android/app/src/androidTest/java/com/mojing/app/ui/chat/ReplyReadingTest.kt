package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReplyReadingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun inlinePriceRefreshesAfterPricingWithoutReopeningMessage() {
        val record = kotlinx.coroutines.flow.MutableStateFlow<com.mojing.app.data.local.entity.CostRecordEntity?>(null)
        val json = """{"generation_duration_ms":1500,"generation_usage":{"record_id":7,"platform_id":"a","model":"m","total_tokens":123,"cost_known":false}}"""
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReplyUsageLookup provides { record }) { ReplyUsageCaption(json, 2) }
        } }
        compose.onNodeWithText("价格待配置", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            record.value = com.mojing.app.data.local.entity.CostRecordEntity(id = 7, sessionId = 2,
                platformId = "a", modelName = "m", estimatedCost = 3.25, currency = "CNY")
        }
        compose.onNodeWithText("价格待配置", substring = true).assertDoesNotExist()
        compose.onNodeWithText("3.25", substring = true).assertIsDisplayed()
        compose.onNodeWithText("123", substring = true).assertIsDisplayed()
    }

    @Test fun replyUsageIsInlineWithoutActionEntry() {
        compose.setContent { MaterialTheme {
            ReplyUsageCaption("""{"generation_duration_ms":1500,"generation_usage":{"total_tokens":123,"cost_known":false}}""", 1)
        } }
        compose.onNodeWithText("123", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("本条回复生成用量").assertHasNoClickAction()
    }
    @Test fun chatRendererHighlightsAndScrollsInsideLongReply() {
        val body = (1..100).joinToString("\n") { "背景行 $it" } + "\n夜雨落在窗前"
        compose.setContent { MaterialTheme {
            LazyColumn(Modifier.height(400.dp).fillMaxWidth()) { item {
                CompositionLocalProvider(LocalMessageSearchHighlight provides remember { MessageSearchHighlight("夜雨", true) }) {
                    NarratorMessageBubble(MessageEntity(sessionId = 1, speakerType = "narrator", content = body))
                }
            } }
        } }
        compose.waitForIdle()
        val node = compose.onNodeWithText("夜雨", substring = true).fetchSemanticsNode()
        val annotated = node.config[SemanticsProperties.Text].single()
        assertTrue(annotated.spanStyles.isNotEmpty())
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("夜雨", substring = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val line = layout.getLineForOffset(annotated.text.indexOf("夜雨"))
        val hitY = node.positionInRoot.y + layout.getLineTop(line)
        assertTrue("keyword should be inside viewport: $hitY", hitY >= 0 && hitY < 1200)
    }
}
