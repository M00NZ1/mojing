package com.mojing.app.media

import android.media.MediaPlayer
import java.io.File

/** Single owner for locally downloaded speech playback. Call from the main thread. */
object TtsPlayer {
    private var player: MediaPlayer? = null
    private var temporaryFile: File? = null

    fun play(audioFile: File, deleteWhenFinished: Boolean = false): Boolean {
        stop()
        val candidate = MediaPlayer()
        player = candidate
        temporaryFile = audioFile.takeIf { deleteWhenFinished }
        return try {
            candidate.setDataSource(audioFile.absolutePath)
            candidate.setOnCompletionListener { if (player === it) stop() }
            candidate.setOnErrorListener { current, _, _ ->
                if (player === current) stop()
                true
            }
            candidate.prepare()
            candidate.start()
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        val current = player
        player = null
        current?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        temporaryFile?.delete()
        temporaryFile = null
    }

    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)
}
