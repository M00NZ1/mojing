package com.mojing.app.test

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.util.concurrent.atomic.AtomicLong

/**
 * Test-only local TTS engine. It emits a bounded eight-second silent PCM
 * stream, so complete-app playback tests exercise Android's real engine path
 * without a network provider or a change to the device default engine.
 */
class AcceptanceSpeechService : TextToSpeechService() {
    private val synthesisSerial = AtomicLong(0L)
    @Volatile
    private var activeSynthesisThread: Thread? = null

    override fun onGetLanguage(): Array<String> = arrayOf("zho", "CHN", "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        when {
            lang.equals("zh", ignoreCase = true) || lang.equals("zho", ignoreCase = true) ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onStop() {
        synthesisSerial.incrementAndGet()
        activeSynthesisThread?.interrupt()
    }

    override fun onDestroy() {
        synthesisSerial.incrementAndGet()
        activeSynthesisThread?.interrupt()
        super.onDestroy()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        Thread.interrupted()
        val serial = synthesisSerial.incrementAndGet()
        activeSynthesisThread = Thread.currentThread()
        val sampleRate = 8_000
        val totalSamples = sampleRate * 8
        val silence = ByteArray(4_096)
        var remainingSamples = totalSamples
        try {
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            while (remainingSamples > 0 && synthesisSerial.get() == serial && !Thread.currentThread().isInterrupted) {
                val sampleCount = minOf(remainingSamples, silence.size / 2)
                callback.audioAvailable(silence, 0, sampleCount * 2)
                remainingSamples -= sampleCount
            }
            if (synthesisSerial.get() == serial && !Thread.currentThread().isInterrupted) {
                callback.done()
            } else {
                callback.error()
            }
        } catch (_: InterruptedException) {
            callback.error()
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            callback.error()
        } finally {
            if (activeSynthesisThread === Thread.currentThread()) activeSynthesisThread = null
            Thread.interrupted()
        }
    }
}
