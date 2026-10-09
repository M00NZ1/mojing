package com.mojing.app.media

import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Single owner for locally downloaded speech playback. Call from the main thread. */
object TtsPlayer {
    enum class Phase { PREPARING, PLAYING, PAUSED }

    class PlaybackHandle internal constructor(internal val token: Long) {
        internal val completion = CompletableDeferred<Boolean>()
    }

    private data class Active(
        val handle: PlaybackHandle,
        val player: MediaPlayer,
        val temporaryFile: File?,
        val phaseCallback: ((Phase) -> Unit)?,
        var prepared: Boolean = false,
        var paused: Boolean = false,
    )

    private var nextToken = 0L
    private var active: Active? = null

    fun playOwned(
        audioFile: File,
        deleteWhenFinished: Boolean = false,
        initialPaused: Boolean = false,
        onPhaseChanged: ((Phase) -> Unit)? = null,
    ): PlaybackHandle? {
        stop()
        val candidate = try {
            MediaPlayer()
        } catch (_: RuntimeException) {
            if (deleteWhenFinished) runCatching { audioFile.delete() }
            return null
        }
        val handle = PlaybackHandle(++nextToken)
        synchronized(this) {
            active = Active(handle, candidate, audioFile.takeIf { deleteWhenFinished }, onPhaseChanged, paused = initialPaused)
        }
        onPhaseChanged?.invoke(if (initialPaused) Phase.PAUSED else Phase.PREPARING)
        return try {
            candidate.setDataSource(audioFile.absolutePath)
            candidate.setOnCompletionListener { finish(handle, true) }
            candidate.setOnErrorListener { _, _, _ ->
                finish(handle, false)
                true
            }
            candidate.setOnPreparedListener {
                // A stopped/replaced request may still deliver its queued prepared callback.
                val current = synchronized(this) { active?.handle?.token == handle.token }
                if (current) {
                    val shouldStart = synchronized(this) {
                        active?.takeIf { it.handle.token == handle.token }?.also { it.prepared = true }?.paused != true
                    }
                    if (!shouldStart) {
                        onPhaseChanged?.invoke(Phase.PAUSED)
                        return@setOnPreparedListener
                    }
                    try {
                        candidate.start()
                        onPhaseChanged?.invoke(Phase.PLAYING)
                    } catch (_: Exception) {
                        finish(handle, false)
                    }
                }
            }
            candidate.prepareAsync()
            handle
        } catch (_: Exception) {
            finish(handle, false)
            null
        }
    }

    /** Returns whether playback was queued; completion/errors belong to the playback handle. */
    fun play(audioFile: File, deleteWhenFinished: Boolean = false): Boolean =
        playOwned(audioFile, deleteWhenFinished) != null

    suspend fun awaitCompletion(handle: PlaybackHandle): Boolean = handle.completion.await()

    /** Stops only the playback represented by [handle]. */
    fun stop(handle: PlaybackHandle) {
        stopActive(handle.token)
    }

    fun pause(handle: PlaybackHandle) {
        val prepared = synchronized(this) {
            active?.takeIf { it.handle.token == handle.token }?.let {
                if (it.paused) return
                it.paused = true
                it.prepared
            } ?: return
        }
        if (prepared && runCatching { handlePlayer(handle)?.pause() }.isFailure) {
            finish(handle, false)
            return
        }
        if (isCurrent(handle)) activePhase(handle, Phase.PAUSED)
    }

    fun resume(handle: PlaybackHandle) {
        val prepared = synchronized(this) {
            active?.takeIf { it.handle.token == handle.token }?.let {
                if (!it.paused) return
                it.paused = false
                it.prepared
            } ?: return
        }
        if (prepared) {
            if (runCatching { handlePlayer(handle)?.start() }.isFailure) {
                finish(handle, false)
                return
            }
            if (isCurrent(handle)) activePhase(handle, Phase.PLAYING)
        } else if (isCurrent(handle)) {
            activePhase(handle, Phase.PREPARING)
        }
    }

    /** Stops the current playback for the existing user-facing stop action. */
    fun stop() {
        stopActive(null)
    }

    fun isPlaying(): Boolean = synchronized(this) {
        runCatching { active?.player?.isPlaying == true }.getOrDefault(false)
    }

    private fun finish(handle: PlaybackHandle, success: Boolean) {
        val detached = synchronized(this) {
            if (active?.handle?.token != handle.token) return
            active.also { active = null }
        } ?: return
        release(detached, success)
    }

    private fun stopActive(token: Long?) {
        val detached = synchronized(this) {
            val current = active ?: return
            if (token != null && current.handle.token != token) return
            active = null
            current
        }
        release(detached, false)
    }

    private fun release(value: Active, success: Boolean) {
        runCatching { value.player.stop() }
        runCatching { value.player.release() }
        value.temporaryFile?.delete()
        value.handle.completion.complete(success)
    }

    private fun handlePlayer(handle: PlaybackHandle): MediaPlayer? = synchronized(this) {
        active?.takeIf { it.handle.token == handle.token }?.player
    }

    private fun isCurrent(handle: PlaybackHandle): Boolean = synchronized(this) {
        active?.handle?.token == handle.token
    }

    private fun activePhase(handle: PlaybackHandle, phase: Phase) {
        synchronized(this) { active?.takeIf { it.handle.token == handle.token }?.phaseCallback }?.invoke(phase)
    }
}
