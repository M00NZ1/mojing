package com.mojing.app.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.domain.engine.SpeakerScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerSchedulerTest {

    private fun p(
        id: Long,
        cid: Long,
        muted: Boolean = false,
        strategy: String = "natural",
        forceNext: Boolean = false,
        sortOrder: Int = 0,
    ) = SessionParticipantEntity(
        id = id,
        sessionId = 1L,
        characterId = cid,
        muted = muted,
        speakerStrategy = strategy,
        forceNext = forceNext,
        sortOrder = sortOrder,
    )

    private fun char(id: Long, name: String) = CharacterEntity(id = id, name = name, personaPrompt = "")

    @Test
    fun `empty participants reason`() {
        val r = SpeakerScheduler.pick(emptyList(), emptyMap(), emptyList(), "", 2)
        assertTrue(r.characterIds.isEmpty())
        assertEquals("当前会话没有可发言人物。", r.reason)
    }

    @Test
    fun `all muted reason`() {
        val r = SpeakerScheduler.pick(
            listOf(p(1, 10, muted = true)),
            mapOf(10L to char(10, "A")),
            emptyList(),
            "",
            2,
        )
        assertTrue(r.characterIds.isEmpty())
        assertEquals("所有参与者均被禁言。", r.reason)
    }

    @Test
    fun `force next clears ids and reason`() {
        val r = SpeakerScheduler.pick(
            listOf(p(1, 10, forceNext = true), p(2, 11)),
            mapOf(10L to char(10, "A"), 11L to char(11, "B")),
            emptyList(),
            "hi",
            2,
        )
        assertEquals(listOf(10L), r.characterIds)
        assertEquals(listOf(1L), r.clearForceNextParticipantIds)
        assertEquals("检测到强制发言标记，优先发言。", r.reason)
    }

    @Test
    fun `natural strategy includes talkativeness in reason`() {
        val r = SpeakerScheduler.pick(
            listOf(
                p(1, 10, strategy = "natural"),
                p(2, 11, strategy = "natural"),
                p(3, 12, strategy = "natural"),
            ),
            mapOf(10L to char(10, "A"), 11L to char(11, "B"), 12L to char(12, "C")),
            emptyList(),
            "hello A",
            2,
        )
        assertTrue(r.characterIds.isNotEmpty())
        assertTrue(r.reason.contains("发言率抽签"))
    }

    @Test
    fun `list strategy reason`() {
        val r = SpeakerScheduler.pick(
            listOf(p(1, 10, strategy = "list"), p(2, 11, strategy = "list")),
            mapOf(10L to char(10, "A"), 11L to char(11, "B")),
            emptyList(),
            "",
            2,
        )
        assertEquals(listOf(10L, 11L), r.characterIds)
        assertTrue(r.clearForceNextParticipantIds.isEmpty())
        assertEquals("按参与者列表顺序选择。", r.reason)
    }
}
