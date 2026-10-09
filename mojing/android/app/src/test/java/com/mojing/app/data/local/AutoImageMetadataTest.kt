package com.mojing.app.data.local

import com.mojing.app.data.local.dao.MessageRecallPolicy
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoImageMetadataTest {
    @Test
    fun extendedV1MediaIsRecalledOnlyWithItsSourceBranch() {
        val source = MessageEntity(id = 41L, sessionId = 42L, branchId = "main")
        val owned = MessageEntity(
            id = 42L, sessionId = 42L, branchId = "main", parentMessageId = source.id,
            includeInContext = false,
            structuredContentJson = AutoImageMetadata.create(
                "可精确重试的提示", AutoImageMetadata.STATE_FAILED, "attempt-1",
            ),
        )
        val inherited = owned.copy(id = 43L, branchId = "story-2")
        val plan = MessageRecallPolicy.plan(source, listOf(owned, inherited))
        assertEquals(listOf(owned.id, source.id), plan.messagesToDelete.map { it.id })
    }

    @Test
    fun failedMetadataRetainsPromptAndCanTransitionToFreshAttempt() {
        val original = AutoImageMetadata.create("雾港灯塔", AutoImageMetadata.STATE_FAILED, "old-token")
        val parsed = AutoImageMetadata.parse(original)
        assertNotNull(parsed)
        assertEquals("雾港灯塔", parsed?.prompt)
        assertEquals("old-token", parsed?.attemptToken)
        assertTrue(parsed?.retryable == true)

        val running = AutoImageMetadata.update(original, AutoImageMetadata.STATE_RUNNING, "new-token")
        val next = AutoImageMetadata.parse(running)
        assertEquals("雾港灯塔", next?.prompt)
        assertEquals("new-token", next?.attemptToken)
        assertFalse(next?.retryable == true)
    }

    @Test
    fun legacyVersionOneWithoutPromptHasNoRetryButton() {
        val legacy = """{"derived_media_version":1,"derived_media_kind":"image"}"""
        val parsed = AutoImageMetadata.parse(legacy)
        assertNotNull(parsed)
        assertNull(parsed?.prompt)
        assertFalse(parsed?.retryable == true)
    }

    @Test
    fun unrelatedStructuredContentIsIgnored() {
        assertNull(AutoImageMetadata.parse("{\"derived_media_version\":2,\"derived_media_kind\":\"image\"}"))
        assertNull(AutoImageMetadata.parse("not-json"))
    }
}
