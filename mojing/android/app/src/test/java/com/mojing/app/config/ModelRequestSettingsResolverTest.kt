package com.mojing.app.config

import com.mojing.app.data.ModelPlatform
import com.mojing.app.domain.config.ModelRequestSettingsResolver
import org.junit.Assert.*
import org.junit.Test

class ModelRequestSettingsResolverTest {
    private fun platform(id: String, base: String, key: String = "key", capacity: Int? = 10000) =
        ModelPlatform(id, id, base, key, listOf("model"), "model", capacity?.let { mapOf("model" to it) }.orEmpty())
    private fun limit(platforms: List<ModelPlatform>, base: String, key: String = "key", model: String = "model") =
        ModelRequestSettingsResolver.contextWindow(platforms, key, base, model)

    @Test fun capacityBelongsToActualEndpointCredentialsAndModel() {
        val platforms = listOf(platform("one", "https://one.test", capacity = 10000),
            platform("two", "https://two.test/v1", capacity = 20000))
        assertEquals(10000, limit(platforms, "https://one.test/v1/chat/completions"))
        assertEquals(20000, limit(platforms, "https://two.test/v1"))
        assertNull(limit(platforms, "https://one.test", "other-key"))
        assertNull(limit(platforms, "https://one.test", model = "unknown-model"))
    }

    @Test fun multipleFallbackLinesShareOnlyTheirSavedPlatformIdentity() {
        val frozen = listOf(platform("one", "https://one.test/v1;https://fallback.test/v1", capacity = 12000))
        assertEquals(12000, limit(frozen, "https://fallback.test/v1"))
        assertNull(limit(frozen, "https://unrelated.test/v1"))
        val replacement = listOf(frozen.single().copy(modelContextWindows = mapOf("model" to 3000)))
        assertEquals(3000, limit(replacement, "https://fallback.test/v1"))
        assertEquals(12000, limit(frozen, "https://fallback.test/v1"))
    }

    @Test fun unknownAmbiguousAndNullDoNotBorrowAnotherCapacity() {
        val known = platform("known", "https://one.test/v1")
        assertNull(limit(emptyList(), known.baseUrl))
        assertNull(limit(listOf(known, known.copy(id = "duplicate")), known.baseUrl))
        assertNull(limit(listOf(known.copy(modelContextWindows = emptyMap())), known.baseUrl))
        assertEquals(10000, limit(listOf(known, known.copy(id = "other", apiKey = "other")), known.baseUrl, "Bearer key"))
    }

    @Test fun nativeAnthropicMatchesTheActualMessagesEndpoint() {
        val platforms = listOf(platform("native", "https://api.anthropic.com", capacity = 64000))
        assertEquals(64000, limit(platforms, "https://api.anthropic.com/v1/messages"))
        assertNull(limit(platforms, "https://other.test/anthropic.com/v1"))
    }

    @Test fun matchingUsesServiceRoutingRatherThanAssumingEveryVersionIsAChatRoot() {
        val platforms = listOf(platform("four", "https://one.test/v4", capacity = 16000))
        assertEquals(16000, limit(platforms, "https://one.test/v4/v1/chat/completions"))
        assertNull(limit(platforms, "https://one.test/v4/chat/completions"))
    }
}
