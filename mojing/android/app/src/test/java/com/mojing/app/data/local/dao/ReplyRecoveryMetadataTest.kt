package com.mojing.app.data.local.dao

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyRecoveryMetadataTest {
    @Test
    fun withTokenPreservesExistingFieldsAndTokenReadsExactString() {
        val json = ReplyRecoveryMetadata.withToken("{\"kind\":\"reply\",\"count\":2}", "resume-7")
        val root = JsonParser.parseString(json).asJsonObject

        assertEquals("reply", root.get("kind").asString)
        assertEquals(2, root.get("count").asInt)
        assertEquals("resume-7", ReplyRecoveryMetadata.token(json))
    }

    @Test
    fun malformedOrNonStringTokenIsIgnored() {
        assertNull(ReplyRecoveryMetadata.token("broken"))
        assertNull(ReplyRecoveryMetadata.token("{\"reply_recovery_token\":7}"))
    }
}
