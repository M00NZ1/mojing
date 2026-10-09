package com.mojing.app.domain.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicSamplingPolicyTest {
    @Test fun modernOfficialIdsOmitTemperature() {
        listOf("claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-4-7", "claude-sonnet-5", "claude-haiku-5-5",
            "claude-opus-5-5", "claude-mythos-5", "claude-fable-5-1", "claude-mythos-preview",
            "claude-opus-4-7-20261008", "claude-sonnet-5-latest", " CLAUDE-OPUS-4-7 ").forEach {
            assertFalse(it, AnthropicSamplingPolicy.acceptsTemperature(it))
        }
    }

    @Test fun legacyAndUnknownIdsKeepExistingBehavior() {
        listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5", "claude-opus-4-20250514",
            "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-latest", "claude-sonnet-4-5-20250929",
            "custom-claude-4-7", "claude-latest", "", "claude-opus-4.7", "anthropic/claude-opus-4-7",
            "anthropic.claude-opus-4-7-v1", "claude-unknown-5", "claude-opus-4-7-custom").forEach {
            assertTrue(it, AnthropicSamplingPolicy.acceptsTemperature(it))
        }
    }
}
