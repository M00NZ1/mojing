package com.mojing.app.media

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object AndroidTts {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: String? = null

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.CHINESE
                    isInitialized = true
                    pendingSpeak?.let { q ->
                        pendingSpeak = null
                        tts?.speak(q, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
            }
        }
    }

    fun speak(text: String) {
        val q = TtsSpeakText.normalizeForSpeech(text).trim()
        if (q.isEmpty()) return
        if (isInitialized) {
            tts?.speak(q, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            pendingSpeak = q
        }
    }

    fun stop() {
        tts?.stop()
    }
}
