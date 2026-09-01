package com.mojing.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VoicePlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build()
        player.repeatMode = Player.REPEAT_MODE_OFF

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun playAudio(audioUrl: String) {
        val mediaItem = MediaItem.fromUri(audioUrl)
        player.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    fun stopPlayback() {
        player.stop()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        player.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }
}
