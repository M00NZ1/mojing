package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.TokenCounter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatWindowTokenEstimateTest {
    @Test
    fun historyWindowUsesModelVisibleTextAndCurrentContextSelection() {
        val generated = MessageEntity(
            id = 1, sessionId = 7, speakerType = "character",
            content = "<NARRATION>门开了。</NARRATION><THOUGHT>不能告诉她。</THOUGHT>" +
                "<CHOICES><OPTION>离开</OPTION></CHOICES>",
        )
        val user = MessageEntity(id = 2, sessionId = 7, content = "我输入 <OPTION> 原样保留")
        val olderVersion = MessageEntity(id = 3, sessionId = 7, content = "旧回复", includeInContext = false)

        assertEquals(
            TokenCounter.estimateScaledPrefix("门开了。") +
                TokenCounter.estimateScaledPrefix(user.content),
            estimateLoadedContextTokens(listOf(generated, user, olderVersion), emptySet()),
        )
        assertEquals(
            TokenCounter.estimateScaledPrefix(user.content),
            estimateLoadedContextTokens(listOf(generated, user, olderVersion), setOf("m1")),
        )
    }
}
