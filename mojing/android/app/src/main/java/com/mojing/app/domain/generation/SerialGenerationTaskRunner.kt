package com.mojing.app.domain.generation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the serial worker's current task; database state remains the persisted authority. */
internal class SerialGenerationTaskRunner {
    private val mutex = Mutex()
    private var active: Pair<Long, Job>? = null

    suspend fun run(id: Long, claim: suspend () -> Boolean, execute: suspend () -> Unit) = coroutineScope {
        val job = mutex.withLock {
            check(active == null) { "Generation worker must remain serial" }
            if (!claim()) return@coroutineScope
            launch(start = CoroutineStart.LAZY) { execute() }.also { active = id to it }
        }
        try {
            job.start()
            job.join()
        } finally {
            // Parent shutdown also waits for the task's cancellation cleanup before releasing ownership.
            withContext(NonCancellable) {
                job.cancel()
                job.join()
                mutex.withLock { if (active?.second === job) active = null }
            }
        }
    }

    suspend fun cancel(id: Long, persist: suspend () -> Boolean): Boolean = withContext(NonCancellable) {
        val job = mutex.withLock {
            if (!persist()) return@withContext false
            active?.takeIf { it.first == id }?.second?.also { it.cancel() }
        }
        // Do not hold the publication lock while awaiting cleanup; the worker needs it to release the slot.
        job?.join()
        true
    }
}
