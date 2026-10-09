package com.mojing.app.data

import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class ModelPlatformsTest {
    @Test fun optionalContextCapacityKeepsOldV1AndRoundTripsPerPlatformModel() {
        val legacy = ModelPlatform("a", "A", "https://a.test", "synthetic", listOf("same", "other"))
        val raw = ModelPlatformCodec.encode(listOf(legacy))
        assertFalse(raw.contains("modelContextWindows"))
        assertEquals(legacy, ModelPlatformCodec.decode(raw).single())
        val updated = legacy.copy(modelContextWindows = mapOf("same" to 32768, "other" to Int.MAX_VALUE))
        val second = legacy.copy(id = "b", modelContextWindows = mapOf("same" to 200000))
        val values = mutableMapOf("model_platforms_v1" to ModelPlatformCodec.encode(listOf(legacy)))
        storage(values).saveModelPlatform(updated, makeDefault = false)
        storage(values).saveModelPlatform(second, makeDefault = false)
        assertEquals(listOf(updated, second), storage(values).modelPlatforms())
        assertFalse(values.containsKey("public_api_key"))
    }

    @Test fun capacitiesRejectInvalidValuesAndDiscardRemovedModelDrafts() {
        assertNull(ModelPlatformCodec.parseContextWindow(" "))
        assertEquals(32768, ModelPlatformCodec.parseContextWindow("32768"))
        for (raw in listOf("0", "-1", "1.5", "2147483648", "1e5", "no")) {
            assertTrue(raw, runCatching { ModelPlatformCodec.parseContextWindow(raw) }.isFailure)
        }
        assertEquals(mapOf("kept" to 200000), ModelPlatformCodec.contextWindows(listOf("kept", "unknown"),
            mapOf("kept" to "200000", "deleted" to "bad")))
        val p = ModelPlatform("a", "A", "https://a.test", "synthetic", listOf("m"), "m", mapOf("m" to 1))
        for (raw in listOf("0", "-1", "1.5", "2147483648", "\"100\"")) {
            val encoded = ModelPlatformCodec.encode(listOf(p)).replace("\"m\":1", "\"m\":$raw")
            assertTrue(raw, runCatching { ModelPlatformCodec.decode(encoded) }.isFailure)
        }
    }

    @Test fun failedCapacitySaveRestoresOldPlatformMetadataExactly() {
        val old = ModelPlatform("a", "A", "https://a.test", "synthetic", listOf("m"), "m", mapOf("m" to 32000))
        val values = mutableMapOf("model_platforms_v1" to ModelPlatformCodec.encode(listOf(old)))
        val before = values.toMap()
        val s = storage(values, failCommits = 1)
        assertTrue(runCatching { s.saveModelPlatform(old.copy(modelContextWindows = mapOf("m" to 128000)), false) }.isFailure)
        assertEquals(before, values)
        assertEquals(old, storage(values).modelPlatforms().single())
    }

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

    private fun storage(
        values: MutableMap<String, String>,
        failCommits: Int = 0,
        throwFirstCommit: Boolean = false,
    ): SecureStorage {
        val prefs = mockk<SharedPreferences>()
        var remainingFailures = failCommits
        var shouldThrow = throwFirstCommit
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } answers {
            val changes = mutableMapOf<String, String>()
            val removed = mutableSetOf<String>()
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putString(any(), any()) } answers { changes[firstArg()] = secondArg(); editor }
            every { editor.remove(any()) } answers { removed += firstArg<String>(); editor }
            every { editor.commit() } answers {
                removed.forEach(values::remove); values.putAll(changes)
                if (shouldThrow) { shouldThrow = false; throw IllegalStateException("disk") }
                if (remainingFailures > 0) { remainingFailures--; false } else true
            }
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

    @Test fun savedDuplicateModelNamesAreDeduplicatedWithoutRewritingStorage() {
        val raw = ModelPlatformCodec.encode(listOf(ModelPlatform(
            "p", "平台", "https://example.test", "test-key", listOf("chat", "reason", "chat"), "chat",
        )))
        val values = mutableMapOf("model_platforms_v1" to raw)
        val platform = storage(values).modelPlatforms().single()
        assertEquals(listOf("chat", "reason"), platform.models)
        assertEquals("chat", platform.selectedModel)
        assertEquals(raw, values["model_platforms_v1"])
    }

    @Test fun followingSettingsClearsOnlyTheSelectedSessionAndSurvivesReopen() {
        val values = mutableMapOf<String, String>()
        val s = storage(values)
        s.saveModelPlatform(ModelPlatform("p", "P", "https://p.test", "test-key", listOf("one")))
        s.selectSessionModel(1, "p", "one")
        s.selectSessionModel(2, "p", "one")
        val before = values.toMap()
        s.clearSessionModelSelection(1)
        s.clearSessionModelSelection(1)
        assertEquals(before.filterKeys { it != "chat_platform_1" && it != "chat_model_1" }, values)
        assertNull(storage(values).sessionModelSelection(1))
        assertEquals("p" to "one", storage(values).sessionModelSelection(2))
    }

    @Test fun unknownModelCannotChangeSavedSessionSelection() {
        val values = mutableMapOf<String, String>()
        val s = storage(values)
        s.saveModelPlatform(ModelPlatform("p", "P", "https://p.test", "test-key", listOf("one")))
        s.selectSessionModel(1, "p", "one")
        assertTrue(runCatching { s.selectSessionModel(1, "p", "missing") }.isFailure)
        assertEquals("p" to "one", s.sessionModelSelection(1))
    }

    @Test fun deletingNonDefaultPlatformKeepsDefaultProjection() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("default", "默认", "https://default.test", "default-key", listOf("one"), "one")),
            ),
            "active_model_platform" to "default",
            "public_api_key" to "default-key",
            "public_base_url" to "https://default.test",
            "public_model" to "one",
        )
        val s = storage(values)
        s.saveModelPlatform(ModelPlatform("secondary", "备用", "https://secondary.test", "secondary-key", listOf("two")), makeDefault = false)

        s.deleteModelPlatform("secondary")

        assertEquals(listOf("default"), s.modelPlatforms().map { it.id })
        assertEquals("default", s.activeModelPlatformId())
        assertEquals("default-key", s.publicApiKey)
        assertEquals("https://default.test", s.publicBaseUrl)
        assertEquals("one", s.publicModel)
    }

    @Test fun deletingDefaultPlatformClearsProjectionWithoutRevivingLegacy() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("only", "唯一", "https://only.test", "key", listOf("model"), "model")),
            ),
            "active_model_platform" to "only",
            "public_api_key" to "key",
            "public_base_url" to "https://only.test",
            "public_model" to "model",
        )
        val s = storage(values)
        s.selectSessionModel(7, "only", "model")

        s.deleteModelPlatform("only")

        assertTrue(s.modelPlatforms().isEmpty())
        assertEquals("", s.activeModelPlatformId())
        assertEquals("", s.publicApiKey)
        assertEquals("", s.publicBaseUrl)
        assertEquals("", s.publicModel)
        assertEquals("only" to "model", s.sessionModelSelection(7))
    }

    @Test fun deletingDefaultKeepsOtherPlatformAndAllowsReAddWithoutSelectingIt() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(
                    ModelPlatform("default", "默认", "https://default.test", "default-key", listOf("one"), "one"),
                    ModelPlatform("other", "其它", "https://other.test", "other-key", listOf("two"), "two"),
                ),
            ),
            "active_model_platform" to "default",
            "public_api_key" to "default-key",
            "public_base_url" to "https://default.test",
            "public_model" to "one",
        )
        val s = storage(values)

        s.deleteModelPlatform("default")

        assertEquals(listOf("other"), s.modelPlatforms().map { it.id })
        assertEquals("", s.activeModelPlatformId())
        assertEquals("", s.publicApiKey)
        s.saveModelPlatform(ModelPlatform("replacement", "替代", "https://replacement.test", "replacement-key", listOf("three")))
        assertEquals("replacement", s.activeModelPlatformId())
        assertEquals("replacement", s.modelPlatforms().last().id)
    }

    @Test fun failedDefaultDeletionRestoresProjectionForRetry() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("only", "唯一", "https://only.test", "key", listOf("model"), "model")),
            ),
            "active_model_platform" to "only",
            "public_api_key" to "key",
            "public_base_url" to "https://only.test",
            "public_model" to "model",
        )
        val s = storage(values, failCommits = 1)

        assertTrue(runCatching { s.deleteModelPlatform("only") }.isFailure)
        assertEquals(listOf("only"), s.modelPlatforms().map { it.id })
        assertEquals("only", s.activeModelPlatformId())
        assertEquals("key", s.publicApiKey)
        assertEquals("https://only.test", s.publicBaseUrl)
        assertEquals("model", s.publicModel)
    }

    @Test fun failedDeletionAndRollbackFailureReportsRecoveryRisk() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("only", "唯一", "https://only.test", "key", listOf("model"), "model")),
            ),
            "active_model_platform" to "only",
            "public_api_key" to "key",
            "public_base_url" to "https://only.test",
            "public_model" to "model",
        )

        val failure = runCatching { storage(values, failCommits = 2).deleteModelPlatform("only") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("恢复失败"))
    }

    @Test fun failedDefaultSaveRestoresAllTouchedProjectionKeys() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("old", "旧", "https://old.test", "old-key", listOf("old-model"), "old-model")),
            ),
            "active_model_platform" to "old",
            "public_api_key" to "old-key",
            "public_base_url" to "https://old.test",
            "public_model" to "old-model",
        )
        val s = storage(values, failCommits = 1)
        val before = values.toMap()

        assertTrue(runCatching {
            s.saveModelPlatform(ModelPlatform("new", "新", "https://new.test", "new-key", listOf("new-model")))
        }.isFailure)

        assertEquals(before, values)
        assertEquals("old", s.activeModelPlatformId())
        assertEquals("old-key", s.publicApiKey)
        assertEquals("https://old.test", s.publicBaseUrl)
        assertEquals("old-model", s.publicModel)
    }

    @Test fun failedNonDefaultSaveRestoresOnlyPlatformListAndProtectsPublicProjection() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("old", "旧", "https://old.test", "old-key", listOf("old-model"), "old-model")),
            ),
            "active_model_platform" to "old",
            "public_api_key" to "old-key",
            "public_base_url" to "https://old.test",
            "public_model" to "old-model",
        )
        val s = storage(values, failCommits = 1)
        val before = values.toMap()

        assertTrue(runCatching {
            s.saveModelPlatform(
                ModelPlatform("new", "新", "https://new.test", "new-key", listOf("new-model")),
                makeDefault = false,
            )
        }.isFailure)

        assertEquals(before, values)
        assertEquals("old-key", s.publicApiKey)
        assertEquals("old", s.activeModelPlatformId())
    }

    @Test fun thrownSaveCommitAfterMemoryWriteRestoresAndCanRetry() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("old", "旧", "https://old.test", "old-key", listOf("old-model"), "old-model")),
            ),
            "active_model_platform" to "old",
            "public_api_key" to "old-key",
            "public_base_url" to "https://old.test",
            "public_model" to "old-model",
        )
        val s = storage(values, throwFirstCommit = true)
        val replacement = ModelPlatform("new", "新", "https://new.test", "new-key", listOf("new-model"), "new-model")

        assertTrue(runCatching { s.saveModelPlatform(replacement) }.isFailure)
        assertEquals("old", s.activeModelPlatformId())
        s.saveModelPlatform(replacement)
        assertEquals("new", s.activeModelPlatformId())
        assertEquals("new-key", s.publicApiKey)
    }

    @Test fun saveRollbackFailureReportsRecoveryRisk() {
        val values = mutableMapOf(
            "model_platforms_v1" to ModelPlatformCodec.encode(
                listOf(ModelPlatform("old", "旧", "https://old.test", "old-key", listOf("old-model"), "old-model")),
            ),
            "active_model_platform" to "old",
            "public_api_key" to "old-key",
            "public_base_url" to "https://old.test",
            "public_model" to "old-model",
        )
        val failure = runCatching {
            storage(values, failCommits = 2).saveModelPlatform(
                ModelPlatform("new", "新", "https://new.test", "new-key", listOf("new-model")),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("恢复失败"))
    }
}
