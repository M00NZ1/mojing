package com.mojing.app.data.local.branch

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BranchContextMemoryInheritanceTest {
    private val branch = SessionBranchEntity(sessionId = 7, branchId = "child", sourceMessageId = 100)
    private val memory = SessionContextMemoryEntity(sessionId = 7, sourceStartMessageId = 1, sourceEndMessageId = 81)
    private val rows = (1L..81L).map { MessageEntity(id = it, sessionId = 7, content = "剧情$it", createdAt = it) }
    private suspend fun compatible(m: SessionContextMemoryEntity = memory, child: List<MessageEntity> = rows): Boolean =
        BranchContextMemoryInheritance.isCompatible(branch, m) { id, after, limit ->
            (if (id == "main") rows else child).filter { it.id > after && it.id <= m.sourceEndMessageId }.take(limit)
        }

    @Test fun exactPrefixAcrossMultiplePagesIsCompatible() = runTest { assertTrue(compatible()) }
    @Test fun futureInvalidAndUnsupportedSnapshotsAreRejectedWithoutReading() = runTest {
        listOf(memory.copy(sourceEndMessageId = 101), memory.copy(isValid = false), memory.copy(memoryVersion = 2),
            memory.copy(sourceStartMessageId = 0), memory.copy(sourceStartMessageId = 82),
            memory.copy(sessionId = 8), memory.copy(branchId = "sibling")).forEach { m ->
            assertFalse(BranchContextMemoryInheritance.isCompatible(branch, m) { _, _, _ -> error("must not read") })
        }
    }
    @Test fun missingOrAdditionalPrefixSourcesAreRejected() = runTest {
        assertFalse(compatible(child = rows.drop(1)))
        assertFalse(compatible(memory.copy(sourceStartMessageId = 2)))
        assertFalse(compatible(memory.copy(sourceEndMessageId = 82)))
        assertFalse(compatible(child = emptyList()))
    }
    @Test fun editsSelectionAndStructuredChangesAreRejected() = runTest {
        val source = rows[40]
        listOf(source.copy(id = 90), source.copy(content = "改稿"), source.copy(branchId = "sibling"),
            source.copy(structuredContentJson = "{\"changed\":true}"), source.copy(swipeGroupId = "new-group")).forEach { changed ->
            assertFalse(compatible(child = rows.toMutableList().apply { this[40] = changed }))
        }
    }
    @Test fun contextExcludedSourceInLaterPageRejectsWholeSnapshot() = runTest {
        assertFalse(compatible(child = rows.filter { it.id != 62L }))
    }
    @Test fun outOfOrderOrUnboundedPageIsRejected() = runTest {
        assertFalse(BranchContextMemoryInheritance.isCompatible(branch, memory) { _, _, _ -> rows.take(40).reversed() })
        assertFalse(BranchContextMemoryInheritance.isCompatible(branch, memory) { _, _, _ -> rows })
    }
    @Test fun cancellationPropagatesInsteadOfApprovingPartialPrefix() = runTest {
        var calls = 0
        try {
            BranchContextMemoryInheritance.isCompatible(branch, memory) { _, after, limit ->
                if (++calls == 3) throw CancellationException("stop")
                rows.filter { it.id > after }.take(limit)
            }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { assertEquals(3, calls) }
    }
}
