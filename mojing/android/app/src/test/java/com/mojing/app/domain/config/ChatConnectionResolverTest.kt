package com.mojing.app.domain.config

import org.junit.Assert.*
import org.junit.Test

class ChatConnectionResolverTest {
    @Test fun publicConnectionIsInheritedOnlyWhenNoOverrideExists() {
        val result = ChatConnectionResolver.resolve(publicKey = "public", publicBase = "https://public.example/v1")
        assertEquals("public", result.apiKey)
        assertEquals("https://public.example/v1", result.baseUrl)
        assertNull(result.error)
    }
    @Test fun partialOverridesNeverBorrowCredentialsOrAddress() {
        for (result in listOf(
            ChatConnectionResolver.resolve(characterKey = "private", publicKey = "public", publicBase = "https://public.example"),
            ChatConnectionResolver.resolve(characterBase = "https://private.example", publicKey = "public", publicBase = "https://public.example"),
            ChatConnectionResolver.resolve(worldKey = "world", characterKey = "character", characterBase = "https://character.example", publicKey = "public", publicBase = "https://public.example"),
            ChatConnectionResolver.resolve(worldBase = "https://world.example", publicKey = "public", publicBase = "https://public.example"),
        )) {
            assertNotNull(result.error)
            assertTrue(result.apiKey.isBlank() || result.baseUrl.isBlank())
            assertFalse(result.error!!.contains("private"))
        }
    }
    @Test fun explicitDeepSeekKeyAndEndpointRemainTogether() {
        val result = ChatConnectionResolver.resolve(characterKey = "private", characterBase = "https://api.deepseek.com", publicKey = "public", publicBase = "https://public.example")
        assertEquals("private", result.apiKey)
        assertEquals("https://api.deepseek.com", result.baseUrl)
        assertNull(result.error)
    }
    @Test fun legacyDefaultWithoutKeyStillInheritsPublicConnection() {
        val result = ChatConnectionResolver.resolve(characterBase = "https://api.deepseek.com", publicKey = "public", publicBase = "https://public.example")
        assertEquals("public", result.apiKey)
        assertEquals("https://public.example", result.baseUrl)
    }
    @Test fun worldOverridesWholeCharacterConnectionAndKeepsFallbackAddresses() {
        val addresses = "https://one.example\nhttps://two.example"
        val result = ChatConnectionResolver.resolve(worldKey = "world", worldBase = addresses, characterKey = "character", characterBase = "https://character.example", publicKey = "public", publicBase = "https://public.example")
        assertEquals("world", result.apiKey)
        assertEquals(addresses, result.baseUrl)
        assertNull(result.error)
    }
}
