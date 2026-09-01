package com.mojing.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalNavigationTest {
    @Test
    fun `shortcut hosts map to existing destinations`() {
        assertEquals(
            ExternalNavigationTarget.NewSession,
            parseExternalNavigationTarget("new_chat", null, null),
        )
        assertEquals(
            ExternalNavigationTarget.Characters,
            parseExternalNavigationTarget("characters", null, null),
        )
    }

    @Test
    fun `widget and notification extras map to existing destinations`() {
        assertEquals(
            ExternalNavigationTarget.Encyclopedia,
            parseExternalNavigationTarget(null, "encyclopedia", null),
        )
        assertEquals(
            ExternalNavigationTarget.Chat(42L),
            parseExternalNavigationTarget(null, "chat", 42L),
        )
    }

    @Test
    fun `deep link takes precedence over stale extras`() {
        assertEquals(
            ExternalNavigationTarget.Characters,
            parseExternalNavigationTarget("characters", "chat", 42L),
        )
    }

    @Test
    fun `invalid or incomplete destinations are ignored`() {
        assertNull(parseExternalNavigationTarget(null, "chat", null))
        assertNull(parseExternalNavigationTarget(null, "chat", 0L))
        assertNull(parseExternalNavigationTarget(null, "chat", -1L))
        assertNull(parseExternalNavigationTarget(null, "unknown", 42L))
        assertNull(parseExternalNavigationTarget(null, null, null))
    }
}
