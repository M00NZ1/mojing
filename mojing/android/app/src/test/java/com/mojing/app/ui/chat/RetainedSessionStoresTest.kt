package com.mojing.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedSessionStoresTest {
    @Test fun backgroundFailureSurvivesOwnerReleaseAndAcknowledgementIsScoped() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        val model = registry.acquire(1) { store -> Model().also { store.put("model", it) } }
        val token = registry.beginReply(1)
        registry.release(1)
        registry.finishReply(1, token, "网络连接中断")
        assertTrue(model.cleared)
        assertEquals("网络连接中断", registry.failures.value[1L]?.message)
        registry.dismissFailure(2, token)
        assertTrue(registry.failures.value.containsKey(1L))
        registry.dismissFailure(1, token)
        assertTrue(registry.failures.value.isEmpty())
    }

    @Test fun oldFailureCannotOverwriteNewReplyAndVisibleFailuresAreNotDuplicated() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        val old = registry.beginReply(1)
        val current = registry.beginReply(1)
        registry.finishReply(1, old, "旧请求失败")
        assertTrue(registry.failures.value.isEmpty())
        registry.acquire(1) { store -> Model().also { store.put("model", it) } }
        registry.finishReply(1, current, "已在页面显示")
        assertTrue(registry.failures.value.isEmpty())
        registry.release(1)
    }

    @Test fun noticesRemainBoundedAndOldDismissalCannotHideRetryFailure() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        for (id in 1L..40L) registry.finishReply(id, registry.beginReply(id), "失败".repeat(500))
        assertEquals(32, registry.failures.value.size)
        assertFalse(registry.failures.value.containsKey(1L))
        assertEquals(500, registry.failures.value[40L]?.message?.length)
        val old = registry.failures.value.getValue(40L).token
        registry.finishReply(40, registry.beginReply(40), "重试失败")
        registry.dismissFailure(40, old)
        assertEquals("重试失败", registry.failures.value[40L]?.message)
    }
    private class Model : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    @Test fun leavingAndReturningReusesTheLiveOwnerAndRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val registry = RetainedSessionStores(backgroundScope)
            val model = registry.acquire(1) { store -> Model().also { store.put("model", it) } }
            val release = CompletableDeferred<Unit>()
            var persisted = false
            val request = model.viewModelScope.launch { release.await(); persisted = true }
            registry.retainJob(1, request)
            runCurrent()
            registry.release(1)
            assertFalse(model.cleared)
            assertTrue(request.isActive)
            val reopened = registry.acquire<Model>(1) { error("Must reuse the same owner") }
            assertSame(model, reopened)
            release.complete(Unit)
            runCurrent()
            assertTrue(persisted)
            assertFalse(model.cleared)
            registry.release(1)
            assertTrue(model.cleared)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun backgroundCompletionReleasesMemoryWithoutReopening() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        val model = registry.acquire(1) { store -> Model().also { store.put("model", it) } }
        val request = Job()
        registry.retainJob(1, request)
        registry.release(1)
        request.complete(); runCurrent()
        assertTrue(model.cleared)
        assertTrue(registry.running.value.isEmpty())
    }

    @Test fun cancellationCleanupAndOtherSessionsRemainIsolated() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        val first = registry.acquire(1) { store -> Model().also { store.put("model", it) } }
        val second = registry.acquire(2) { store -> Model().also { store.put("model", it) } }
        val cleanup = CompletableDeferred<Unit>()
        val request = backgroundScope.launch {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        val other = Job()
        registry.retainJob(1, request); registry.retainJob(2, other)
        registry.release(1); registry.release(2); runCurrent()
        registry.stop(1); runCurrent()
        assertFalse(first.cleared)
        cleanup.complete(Unit); runCurrent()
        assertTrue(first.cleared)
        assertFalse(second.cleared)
        assertEquals(setOf(2L), registry.running.value)
        other.complete(); runCurrent()
        assertTrue(second.cleared)
    }

    @Test fun lateOldCompletionDoesNotReleaseNewRequestOrMaintenance() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        val model = registry.acquire(1) { store -> Model().also { store.put("model", it) } }
        val old = Job(); val next = Job()
        registry.retainJob(1, old); registry.retainJob(1, next)
        registry.release(1)
        old.complete(); runCurrent()
        assertFalse(model.cleared)
        next.complete(); runCurrent()
        assertTrue(model.cleared)
    }

    @Test fun localWriteRetainsOwnerAcrossRecreationWithoutReportingGeneration() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        val model = registry.acquire(1) { store -> Model().also { store.put("model", it) } }
        val write = Job()
        registry.retainJob(1, write, reportRunning = false)
        registry.release(1)
        assertFalse(model.cleared)
        assertTrue(registry.running.value.isEmpty())
        val reopened = registry.acquire<Model>(1) { error("Must reuse pending write owner") }
        assertSame(model, reopened)
        write.complete(); runCurrent()
        assertFalse(model.cleared)
        registry.release(1)
        assertTrue(model.cleared)
    }

    @Test fun localWriteAndGenerationCompletionKeepIndependentLifetimes() = runTest {
        val registry = RetainedSessionStores(backgroundScope)
        val model = registry.acquire(1) { store -> Model().also { store.put("model", it) } }
        val write = Job(); val generation = Job()
        registry.retainJob(1, write, reportRunning = false)
        registry.retainJob(1, generation)
        registry.release(1)
        assertEquals(setOf(1L), registry.running.value)
        generation.complete(); runCurrent()
        assertTrue(registry.running.value.isEmpty())
        assertFalse(model.cleared)
        registry.stop(1); runCurrent()
        assertTrue(write.isCancelled)
        assertTrue(model.cleared)
    }
}
