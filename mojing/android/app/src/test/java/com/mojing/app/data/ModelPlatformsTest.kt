package com.mojing.app.data

import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class ModelPlatformsTest {
    @Test fun draftChangesIncludeCredentialsModelsAndSelection() {
        val original = ModelPlatform("p", "平台", "https://example.test", "test", listOf("a", "b"), "b")
        assertFalse(ModelPlatformCodec.hasDraftChanges(original, original, "a，b\na"))
        assertTrue(ModelPlatformCodec.hasDraftChanges(original, original.copy(apiKey = "changed"), "a\nb"))
        assertTrue(ModelPlatformCodec.hasDraftChanges(original, original.copy(selectedModel = "a"), "a\nb"))
        assertTrue(ModelPlatformCodec.hasDraftChanges(original, original, "b\na"))
        assertTrue(ModelPlatformCodec.hasDraftChanges(original, original, "a\nb\nc"))
        assertFalse(ModelPlatformCodec.hasDraftChanges(original, original.copy(name = "平台"), "a\nb"))
    }

    @Test fun discoveryKeepsManualOrderAndOnlyAppendsNewNames() {
        val existing = listOf("manual", "chosen", "chat")
        val merged = ModelPlatformCodec.mergeDiscovered(existing, listOf("chat", " new ", "", "new"))
        assertEquals(listOf("manual", "chosen", "chat", "new"), merged)
        assertEquals(merged, ModelPlatformCodec.mergeDiscovered(merged, listOf("chat", "new")))
        assertEquals(existing, ModelPlatformCodec.mergeDiscovered(existing, emptyList()))
    }

    private fun storage(values: MutableMap<String, String>): SecureStorage {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } answers {
            val changes = mutableMapOf<String, String>()
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putString(any(), any()) } answers { changes[firstArg()] = secondArg(); editor }
            every { editor.commit() } answers { values.putAll(changes); true }
            every { editor.apply() } answers { values.putAll(changes) }
            editor
        }
        return SecureStorage().also {
            SecureStorage::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(it, prefs)
        }
    }

    @Test fun legacyMigrationAndPlatformSwitchKeepCredentialsSeparateAcrossReopen() {
        val values = mutableMapOf("public_api_key" to "test-old", "public_base_url" to "https://old.test/v1", "public_model" to "old-model")
        val s = storage(values)
        assertFalse(values.containsKey("model_platforms_v1"))
        assertEquals("test-old", s.modelPlatforms().single().apiKey)
        s.saveModelPlatform(ModelPlatform("new", "New", "https://new.test/v1", "test-new", listOf("a", "b")))
        val reopened = storage(values)
        assertEquals("test-old", reopened.modelPlatforms().first { it.id == "legacy" }.apiKey)
        assertEquals("test-new", reopened.publicApiKey)
        reopened.selectSessionModel(4, "legacy", "old-model")
        reopened.selectSessionModel(5, "new", "b")
        assertEquals("legacy" to "old-model", storage(values).sessionModelSelection(4))
        assertEquals("new" to "b", storage(values).sessionModelSelection(5))
        assertNull(reopened.sessionModelSelection(6))
        assertEquals("test-new", reopened.publicApiKey)
    }

    @Test fun corruptOrFutureDataIsNotSilentlyOverwritten() {
        val values = mutableMapOf("model_platforms_v1" to "{\"version\":99,\"platforms\":[]}")
        val before = values.toMap()
        assertTrue(runCatching { storage(values).saveModelPlatform(ModelPlatform("p", "P", "", "", emptyList())) }.isFailure)
        assertEquals(before, values)
    }

    @Test fun manualModelNamesPreserveOrderAndRemoveDuplicates() {
        assertEquals(listOf("a", "org/b", "c"), ModelPlatformCodec.modelNames(" a，org/b\na,c\r\n"))
    }

    @Test fun unknownModelCannotChangeSavedSessionSelection() {
        val values = mutableMapOf<String, String>()
        val s = storage(values)
        s.saveModelPlatform(ModelPlatform("p", "P", "https://p.test", "test-key", listOf("one")))
        s.selectSessionModel(1, "p", "one")
        assertTrue(runCatching { s.selectSessionModel(1, "p", "missing") }.isFailure)
        assertEquals("p" to "one", s.sessionModelSelection(1))
    }
}
