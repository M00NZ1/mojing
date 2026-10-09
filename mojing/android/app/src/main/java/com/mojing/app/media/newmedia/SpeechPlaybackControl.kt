package com.mojing.app.media.newmedia

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** Lifecycle and user control for one speech request. It owns no coroutine or playback job. */
class SpeechPlaybackControl {
    enum class Phase { IDLE, PREPARING, PLAYING, PAUSED }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val segmentIndex: Int = 0,
        val segmentCount: Int = 0,
        /** True when resume has to restart the current segment from its beginning. */
        val resumedFromSegmentStart: Boolean = false,
    )

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = mutableSnapshot.asStateFlow()

    internal class Lease internal constructor(internal val serial: Long)
    private data class OwnerState(val owner: Lease?, val paused: Boolean, val closed: Boolean)

    private val lock = Any()
    private var closed = false
    private var paused = false
    private var nextLease = 0L
    private var owner: Lease? = null
    private var pauseListener: (() -> Unit)? = null
    private var resumeListener: (() -> Unit)? = null
    private var closeListener: (() -> Unit)? = null
    private val ownerState = MutableStateFlow(OwnerState(null, false, false))

    fun pause() {
        val callback = synchronized(lock) {
            if (closed || paused) return
            paused = true
            updateLocked { it.copy(phase = Phase.PAUSED) }
            ownerState.value = OwnerState(owner, paused, closed)
            pauseListener
        }
        callback?.invoke()
    }

    fun resume() {
        val callback = synchronized(lock) {
            if (closed || !paused) return
            paused = false
            updateLocked { current -> if (current.phase == Phase.PAUSED) current.copy(phase = Phase.PREPARING) else current }
            ownerState.value = OwnerState(owner, paused, closed)
            resumeListener
        }
        callback?.invoke()
    }

    fun close() {
        val callback = synchronized(lock) {
            closed = true
            paused = false
            owner = null
            pauseListener = null
            resumeListener = null
            val callback = closeListener
            closeListener = null
            mutableSnapshot.value = Snapshot()
            ownerState.value = OwnerState(null, false, true)
            callback
        }
        callback?.invoke()
    }

    internal fun bind(
        onPause: () -> Unit,
        onResume: () -> Unit,
        onClose: (() -> Unit)? = null,
    ): Lease? {
        val (lease, oldClose) = synchronized(lock) {
            if (closed) return null
            val previousClose = closeListener
            val next = Lease(++nextLease)
            next.also {
            owner = it
            pauseListener = onPause
            resumeListener = onResume
            closeListener = onClose
            ownerState.value = OwnerState(it, paused, closed)
            mutableSnapshot.value = Snapshot(phase = if (paused) Phase.PAUSED else Phase.PREPARING)
            } to previousClose
        }
        oldClose?.invoke()
        return lease
    }

    /** Replace phase callbacks without acquiring a different request's ownership. */
    internal fun setCallbacks(
        lease: Lease,
        onPause: () -> Unit,
        onResume: () -> Unit,
        onClose: (() -> Unit)? = null,
    ): Boolean = synchronized(lock) {
        if (closed || owner !== lease) return false
        pauseListener = onPause
        resumeListener = onResume
        closeListener = onClose
        true
    }

    internal fun unbind(lease: Lease) = synchronized(lock) {
        if (owner === lease) {
            owner = null
            pauseListener = null
            resumeListener = null
            closeListener = null
            mutableSnapshot.value = Snapshot()
            ownerState.value = OwnerState(null, paused, closed)
        }
    }

    internal fun owns(lease: Lease): Boolean = synchronized(lock) { !closed && owner === lease }
    internal fun isPaused(): Boolean = synchronized(lock) { !closed && paused }
    internal suspend fun awaitResume(lease: Lease): Boolean {
        if (!owns(lease)) return false
        if (!isPaused()) return true
        ownerState.filter { it.owner !== lease || it.closed || !it.paused }.first()
        return owns(lease)
    }
    internal fun updateIfOwned(lease: Lease, transform: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            if (closed || owner !== lease) return
            updateLocked { current ->
                val next = transform(current)
                if (paused && next.phase != Phase.IDLE) next.copy(phase = Phase.PAUSED) else next
            }
        }
    }

    private fun updateLocked(transform: (Snapshot) -> Snapshot) {
        if (!closed) mutableSnapshot.value = transform(mutableSnapshot.value)
    }
}
