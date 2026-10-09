package com.mojing.app.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationResultSnapshotCodecTest {
    @Test fun characterRoundTripOnlyKeepsPersonaField() {
        val encoded = GenerationResultSnapshotCodec.encode(GenerationResultSnapshot.CharacterPersona("新的设定"))
        assertTrue(encoded.contains("personaPrompt"))
        assertEquals("新的设定", (GenerationResultSnapshotCodec.decode(encoded) as GenerationResultSnapshot.CharacterPersona).personaPrompt)
    }

    @Test fun malformedOrUnknownFieldsAreRejected() {
        assertNull(GenerationResultSnapshotCodec.decode("{}"))
        assertNull(GenerationResultSnapshotCodec.decode("""{"schemaVersion":1,"taskKind":"character_persona_ai","fields":{"personaPrompt":"x","apiKey":"secret"}}"""))
        assertNull(GenerationResultSnapshotCodec.decode("""{"schemaVersion":2,"taskKind":"character_persona_ai","fields":{"personaPrompt":"x"}}"""))
        assertNull(GenerationResultSnapshotCodec.decode("""{"schemaVersion":1.2,"taskKind":"character_persona_ai","fields":{"personaPrompt":"x"}}"""))
        assertNull(GenerationResultSnapshotCodec.decode("""{"schemaVersion":1,"taskKind":"character_persona_ai","fields":{"personaPrompt":"   "}}"""))
    }

    @Test fun worldSnapshotAllowsOnlyGeneratedFields() {
        val value = GenerationResultSnapshot.WorldTemplate("摘要", "世界书")
        val decoded = GenerationResultSnapshotCodec.decode(GenerationResultSnapshotCodec.encode(value)) as GenerationResultSnapshot.WorldTemplate
        assertEquals("摘要", decoded.summary)
        assertEquals("世界书", decoded.worldPrompt)
    }
}
