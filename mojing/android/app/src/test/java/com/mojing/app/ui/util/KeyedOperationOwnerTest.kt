package com.mojing.app.ui.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedOperationOwnerTest {

    @Test
    fun sameKeyIsAcceptedOnlyOnce() {
        val owner = KeyedOperationOwner<Long>()

        assertTrue(owner.tryStart(1L))
        assertFalse(owner.tryStart(1L))
        assertEquals(setOf(1L), owner.activeKeys.value)
    }

    @Test
    fun differentKeysCanRunInParallel() {
        val owner = KeyedOperationOwner<Long>()

        assertTrue(owner.tryStart(1L))
        assertTrue(owner.tryStart(2L))
        assertEquals(setOf(1L, 2L), owner.activeKeys.value)
    }

    @Test
    fun cancellationFinallyReleasesBusyKey() = runTest {
        val owner = KeyedOperationOwner<Long>()
        assertTrue(owner.tryStart(1L))

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } finally {
                owner.finish(1L)
            }
        }

        job.cancelAndJoin()
        assertTrue(owner.activeKeys.value.isEmpty())
        assertTrue(owner.tryStart(1L))
    }
}
