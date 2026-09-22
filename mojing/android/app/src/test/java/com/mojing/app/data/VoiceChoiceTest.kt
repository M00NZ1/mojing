package com.mojing.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceChoiceTest {
    @Test fun characterOverridesConversationAsAWhole() {
        assertEquals(VoiceChoice("android:example.engine", ""), resolveVoiceChoice("android:example.engine", "", VoiceChoice("azure", "xiaoxiao")))
        assertEquals(VoiceChoice("azure", "yunxi"), resolveVoiceChoice("azure", "yunxi", VoiceChoice()))
    }
    @Test fun inheritanceAndLegacyModelsDoNotBecomeVoiceIds() {
        val conversation = VoiceChoice("azure", "xiaoxiao")
        for (provider in listOf(null, "", "inherit", "openai")) {
            assertEquals(conversation, resolveVoiceChoice(provider, "tts-1", conversation))
        }
        assertEquals(VoiceChoice(), resolveVoiceChoice("system", "system", conversation))
    }
}
