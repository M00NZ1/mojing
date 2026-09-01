package com.mojing.app.media

import android.content.Context
import android.media.MediaPlayer
import java.io.File

object TtsPlayer {
    private var player: MediaPlayer? = null

    fun play(audioFile: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            start()
        }
    }

    fun stop() {
        player?.stop()
        player?.release()
        player = null
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
}
