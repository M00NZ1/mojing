package com.mojing.app.domain.generation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SerialGenerationTaskRunnerTest {
    @Test fun cancelPersistsBeforeCleanupAndWaitsWithoutKillingWorker() = runTest {
        val runner = SerialGenerationTaskRunner()
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val worker = launch {
            runner.run(1, { true }) {
                events += "request"
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { events += "cleanup"; release.await() } }
            }
            events += "next"
            runner.run(2, { true }) { events += "second request" }
        }
        runCurrent()
        val cancel = launch { assertTrue(runner.cancel(1) { events += "persist"; true }); events += "cancel returned" }
        runCurrent()
        assertEquals(listOf("request", "persist", "cleanup"), events)
        assertFalse(cancel.isCompleted)
        release.complete(Unit)
        worker.join(); cancel.join()
        assertTrue(events.indexOf("next") > events.indexOf("cleanup"))
        assertTrue(events.contains("second request"))
    }

    @Test fun queuedAndStaleCancelCannotCancelAnotherTask() = runTest {
        val runner = SerialGenerationTaskRunner()
        val worker = launch { runner.run(2, { true }) { awaitCancellation() } }
        runCurrent()
        assertTrue(runner.cancel(1) { true })
        assertFalse(runner.cancel(2) { false })
        assertTrue(worker.isActive)
        assertTrue(runner.cancel(2) { true }); worker.join()
        assertFalse(runner.cancel(2) { false })
        runner.run(3, { true }) { assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive) }
    }

    @Test fun cancelBeforeClaimPreventsRequest() = runTest {
        val runner = SerialGenerationTaskRunner()
        var queued = true
        assertTrue(runner.cancel(1) { queued = false; true })
        runner.run(1, { queued }) { fail("cancelled queued task executed") }
    }

    @Test fun cancelDuringClaimWaitsForPublicationAndPreventsLazyExecution() = runTest {
        val runner = SerialGenerationTaskRunner()
        val claimGate = CompletableDeferred<Unit>()
        var persisted = false
        var requested = false
        val worker = launch { runner.run(1, { claimGate.await(); true }) { requested = true } }
        runCurrent()
        val cancel = launch { runner.cancel(1) { persisted = true; true } }
        runCurrent(); assertFalse(persisted)
        claimGate.complete(Unit)
        worker.join(); cancel.join()
        assertTrue(persisted); assertFalse(requested)
    }

    @Test fun failedPersistenceLeavesActiveRequestAndPropagatesFailure() = runTest {
        val runner = SerialGenerationTaskRunner()
        val worker = launch { runner.run(1, { true }) { awaitCancellation() } }
        runCurrent()
        val error = runCatching { runner.cancel(1) { error("storage failed") } }.exceptionOrNull()
        assertEquals("storage failed", error?.message); assertTrue(worker.isActive)
        runner.cancel(1) { true }; worker.join()
    }

    @Test fun parentShutdownCancelsRequestAndReleasesSlot() = runTest {
        val runner = SerialGenerationTaskRunner()
        var cleaned = false
        val worker = launch { runner.run(1, { true }) { try { awaitCancellation() } finally { cleaned = true } } }
        runCurrent(); worker.cancel(); worker.join()
        assertTrue(cleaned)
        runner.run(2, { true }) { }
    }
}
