package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.BranchSwipeSelectionEntity
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchSwipeSelectionPolicyTest {
    private val original = MessageEntity(
        id = 1L,
        sessionId = 42L,
        swipeGroupId = "reply-1",
        includeInContext = true,
        content = "原回复",
    )
    private val alternative = MessageEntity(
        id = 2L,
        sessionId = 42L,
        swipeGroupId = "reply-1",
        includeInContext = false,
        content = "新回复",
    )

    @Test
    fun selectedReplyOutsideLoadedWindowDoesNotActivateAlternative() {
        val projected = listOf(alternative).withEffectiveSwipeSelections(
            listOf(BranchSwipeSelectionEntity(42L, "main", "reply-1", original.id)),
        )
        assertFalse(projected.single().includeInContext)
    }

    @Test
    fun explicitStorylineSelectionOverridesFrozenDefault() {
        val projected = listOf(original, alternative).withEffectiveSwipeSelections(
            listOf(
                BranchSwipeSelectionEntity(42L, "branch-a", "reply-1", alternative.id),
            ),
        )

        assertFalse(projected.single { it.id == original.id }.includeInContext)
        assertTrue(projected.single { it.id == alternative.id }.includeInContext)
    }

    @Test
    fun storylineWithoutOverrideKeepsFrozenDefault() {
        val projected = listOf(original, alternative).withEffectiveSwipeSelections(emptyList())

        assertTrue(projected.single { it.id == original.id }.includeInContext)
        assertFalse(projected.single { it.id == alternative.id }.includeInContext)
    }

    @Test
    fun missingVisibleDefaultFallsBackToLatestVisibleVariant() {
        val projected = listOf(
            original.copy(includeInContext = false),
            alternative.copy(includeInContext = false),
        ).withEffectiveSwipeSelections(emptyList())

        assertFalse(projected.single { it.id == original.id }.includeInContext)
        assertTrue(projected.single { it.id == alternative.id }.includeInContext)
    }

    @Test
    fun nonSwipeDerivedMessageIsNeverActivatedBySelectionProjection() {
        val derived = MessageEntity(
            id = 3L,
            sessionId = 42L,
            parentMessageId = alternative.id,
            includeInContext = false,
            content = "",
        )

        val projected = listOf(original, alternative, derived).withEffectiveSwipeSelections(
            listOf(BranchSwipeSelectionEntity(42L, "main", "reply-1", alternative.id)),
        )

        assertFalse(projected.single { it.id == derived.id }.includeInContext)
        assertEquals("", projected.single { it.id == derived.id }.content)
    }
}
