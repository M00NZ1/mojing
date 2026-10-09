package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.SessionCharacterStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterStatePresentationTest {
    @Test
    fun parsesBoundedSnapshotForTheRequestedBranch() {
        val panel = parseCharacterStatePanel(
            SessionCharacterStateEntity(
                sessionId = 7L, characterId = 9L, branchId = "branch-a",
                dynamicStateJson = """{"mood":"警惕","currentGoal":"守住门口","recentKeyActions":["回头"]}""",
                updatedAt = 123L,
            ),
            sessionId = 7L, characterId = 9L, branchId = "branch-a", branchLabel = "雨夜线",
        )
        assertTrue(panel.snapshotIsValid)
        assertEquals(null, panel.displayError)
        assertEquals("警惕", panel.state?.mood)
        assertEquals("雨夜线", panel.branchLabel)
    }

    @Test
    fun invalidSnapshotIsShownAsUnavailableAndKeepsOriginalFlag() {
        val panel = parseCharacterStatePanel(
            SessionCharacterStateEntity(
                sessionId = 7L, characterId = 9L, branchId = "main",
                snapshotIsValid = false, dynamicStateJson = "not-json",
            ),
            7L, 9L, "main", "主线",
        )
        assertFalse(panel.snapshotIsValid)
        assertTrue(panel.hasOriginalText)
        assertEquals(null, panel.state)
    }

    @Test
    fun oversizedSnapshotDoesNotExposeOrParseUnboundedText() {
        val panel = parseCharacterStatePanel(
            SessionCharacterStateEntity(
                sessionId = 7L, characterId = 9L,
                dynamicStateJson = "{" + "x".repeat(33_000) + "}",
            ),
            7L, 9L, "main", "主线",
        )
        assertTrue(panel.snapshotIsValid)
        assertTrue(panel.displayError != null)
        assertTrue(panel.hasOriginalText)
        assertEquals(null, panel.state)
    }
}
