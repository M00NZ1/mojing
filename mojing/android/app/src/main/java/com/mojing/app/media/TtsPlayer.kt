package com.mojing.app.media

import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Single owner for locally downloaded speech playback. Call from the main thread. */
object TtsPlayer {
    class PlaybackHandle internal constructor(internal val token: Long) {
        internal val completion = CompletableDeferred<Boolean>()
    }

    private data class Active(
        val handle: PlaybackHandle,
        val player: MediaPlayer,
        val temporaryFile: File?,
    )

    private var nextToken = 0L
    private var active: Active? = null

    fun playOwned(audioFile: File, deleteWhenFinished: Boolean = false): PlaybackHandle? {
        stop()
        val candidate = MediaPlayer()
        val handle = PlaybackHandle(++nextToken)
        synchronized(this) {
            active = Active(handle, candidate, audioFile.takeIf { deleteWhenFinished })
        }
        return try {
            candidate.setDataSource(audioFile.absolutePath)
            candidate.setOnCompletionListener { finish(handle, true) }
            candidate.setOnErrorListener { _, _, _ ->
                finish(handle, false)
                true
            }
            candidate.prepare()
            candidate.start()
            handle
        } catch (_: Exception) {
            finish(handle, false)
            null
        }
    }

    /** Backward-compatible fire-and-forget API. */
    fun play(audioFile: File, deleteWhenFinished: Boolean = false): Boolean =
        playOwned(audioFile, deleteWhenFinished) != null

    suspend fun awaitCompletion(handle: PlaybackHandle): Boolean = handle.completion.await()

    /** Stops only the playback represented by [handle]. */
    fun stop(handle: PlaybackHandle) {
        stopActive(handle.token)
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
}
