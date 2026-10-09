package com.mojing.app.data.local

import org.junit.Assert.*
import org.junit.Test

class AutoVoiceMetadataTest {
    @Test fun createKeepsFullTextAndNeverStoresCredentials() {
        val text = "章节标题\n" + "长文本".repeat(20_000)
        val raw = AutoVoiceMetadata.create(text, AutoVoiceMetadata.STATE_FAILED, "nonce-1")
        val parsed = AutoVoiceMetadata.parse(raw)

        assertNotNull(parsed)
        assertEquals(text, parsed!!.text)
        assertEquals("nonce-1", parsed.attemptToken)
        assertTrue(parsed.retryable)
        assertFalse(raw.contains("apiKey", ignoreCase = true))
        assertFalse(raw.contains("authorization", ignoreCase = true))
    }

    @Test fun legacyOrWrongKindDoesNotBecomeRetryableVoice() {
        assertNull(AutoVoiceMetadata.parse("{\"derived_media_version\":1,\"derived_media_kind\":\"image\"}"))
        val legacy = AutoVoiceMetadata.parse("{\"derived_media_version\":1,\"derived_media_kind\":\"voice\"}")
        assertNotNull(legacy)
        assertFalse(legacy!!.retryable)
        assertFalse(AutoVoiceMetadata.parse(
            AutoVoiceMetadata.create("text", AutoVoiceMetadata.STATE_COMPLETE, "nonce"),
        )!!.retryable)
        assertFalse(AutoVoiceMetadata.parse(
            AutoVoiceMetadata.create("text", AutoVoiceMetadata.STATE_FAILED, ""),
        )!!.retryable)
        assertFalse(AutoVoiceMetadata.parse(
            "{\"derived_media_version\":1,\"derived_media_kind\":\"voice\",\"auto_media_state\":\"failed\",\"auto_media_text\":\"text\"}",
        )!!.retryable)
    }

    @Test fun updatePreservesOriginalTextAndChangesOnlyStateAndNonce() {
        val original = AutoVoiceMetadata.create("原文", AutoVoiceMetadata.STATE_RUNNING, "old")
        val updated = AutoVoiceMetadata.update(original, AutoVoiceMetadata.STATE_INTERRUPTED, "new")
        val parsed = AutoVoiceMetadata.parse(updated)!!

        assertEquals("原文", parsed.text)
        assertEquals(AutoVoiceMetadata.STATE_INTERRUPTED, parsed.state)
        assertEquals("new", parsed.attemptToken)
    }
}
