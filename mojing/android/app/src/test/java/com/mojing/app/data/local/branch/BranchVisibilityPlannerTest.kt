package com.mojing.app.data.local.branch

import com.mojing.app.data.local.entity.SessionBranchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BranchVisibilityPlannerTest {
    @Test
    fun plansNestedCutoffsFromTargetToMain() {
        val branches = listOf(
            branch("a", parent = "main", source = 10L),
            branch("b", parent = "a", source = 20L),
            branch("c", parent = "b", source = 30L),
        )

        val segments = BranchVisibilityPlanner.plan(1L, branches)
            .filter { it.targetBranchId == "c" }

        assertEquals(
            listOf("c" to Long.MAX_VALUE, "b" to 30L, "a" to 20L, "main" to 10L),
            segments.map { it.sourceBranchId to it.maxMessageId },
        )
    }

    @Test
    fun missingParentAndCycleStopWithoutInventingAncestors() {
        val missing = BranchVisibilityPlanner.plan(
            1L,
            listOf(branch("orphan", parent = "missing", source = 5L)),
        )
        assertEquals(listOf("orphan", "missing"), missing.map { it.sourceBranchId })

        val cycle = BranchVisibilityPlanner.plan(
            1L,
            listOf(branch("a", parent = "b", source = 10L), branch("b", parent = "a", source = 20L)),
        ).filter { it.targetBranchId == "a" }
        assertEquals(listOf("a", "b"), cycle.map { it.sourceBranchId })
    }

    @Test
    fun rejectsDuplicateOrReservedBranchIds() {
        assertThrows(IllegalArgumentException::class.java) {
            BranchVisibilityPlanner.plan(1L, listOf(branch("a"), branch("a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BranchVisibilityPlanner.plan(1L, listOf(branch("main")))
        }
    }

    @Test
    fun planAllKeepsSessionsIsolated() {
        val first = branch("a").copy(sessionId = 1L)
        val second = branch("b").copy(sessionId = 2L)

        val segments = BranchVisibilityPlanner.planAll(listOf(second, first))

        assertEquals(setOf(1L, 2L), segments.map { it.sessionId }.toSet())
        assertEquals(setOf("a"), segments.filter { it.sessionId == 1L }.map { it.targetBranchId }.toSet())
        assertEquals(setOf("b"), segments.filter { it.sessionId == 2L }.map { it.targetBranchId }.toSet())
    }

    private fun branch(
        id: String,
        parent: String = "main",
        source: Long = 1L,
    ) = SessionBranchEntity(
        sessionId = 1L,
        branchId = id,
        parentBranchId = parent,
        sourceMessageId = source,
    )
}
