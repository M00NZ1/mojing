package com.mojing.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object AndroidTts {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isInitializing = false
    private var pendingSpeak: String? = null
    private var appContext: Context? = null
    private var nextUtteranceId = 0L
    private var errorListener: ((String) -> Unit)? = null
    private var activeSpeakError: ((String) -> Unit)? = null
    private var requestToken = 0L
    private var engineToken = 0L

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (isInitialized || isInitializing) return
            isInitializing = true
            lateinit var candidate: TextToSpeech
            candidate = TextToSpeech(appContext) { status ->
                // Some engines invoke the callback from the constructor. Posting ensures
                // the candidate has been assigned and all callbacks share the main thread.
                mainHandler.post {
                    synchronized(lock) {
                        if (tts !== candidate) return@synchronized
                    isInitializing = false
                    if (status != TextToSpeech.SUCCESS) {
                        fail(candidate, "系统朗读引擎初始化失败")
                        return@synchronized
                    }
                    val languageResult = candidate.setLanguage(Locale.CHINESE)
                    if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                        languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        fail(candidate, "系统朗读引擎不支持中文，请安装中文语音数据")
                        return@synchronized
                    }
                    isInitialized = true
                    pendingSpeak?.let { q ->
                        pendingSpeak = null
                        enqueue(candidate, requestToken, q)
                    }
                }
            }
            }
            tts = candidate
            engineToken++
            val candidateToken = engineToken
            candidate.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    handleError(candidate, candidateToken, utteranceId, "系统朗读失败，请检查设备朗读引擎")
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleError(candidate, candidateToken, utteranceId, "系统朗读失败（错误码 $errorCode）")
                }
            })
        }
    }

    /** Optional UI hook; existing callers remain source compatible. */
    fun setErrorListener(listener: ((String) -> Unit)?) {
        synchronized(lock) { errorListener = listener }
    }

    fun speak(text: String, onError: ((String) -> Unit)? = null) {
        val q = TtsSpeakText.normalizeForSpeech(text).trim()
        if (q.isEmpty()) return
        synchronized(lock) {
            requestToken++
            activeSpeakError = onError
            if (isInitialized && tts != null) {
                enqueue(tts!!, requestToken, q)
            } else {
                pendingSpeak = q
                appContext?.let { init(it) }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            requestToken++
            pendingSpeak = null
            activeSpeakError = null
            tts?.stop()
        }
    }

    private fun enqueue(engine: TextToSpeech, token: Long, text: String) {
        val chunks = SpeechChunks.split(text, TextToSpeech.getMaxSpeechInputLength())
        chunks.forEachIndexed { index, chunk ->
            val id = "mojing-$token-${++nextUtteranceId}"
            val result = engine.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                id,
            )
            if (result == TextToSpeech.ERROR) {
                fail(engine, "系统朗读失败，请检查设备朗读引擎", token)
                return
            }
        }
    }

    private fun handleError(engine: TextToSpeech, token: Long, utteranceId: String?, message: String) {
        mainHandler.post {
            val idToken = utteranceId?.split('-')?.getOrNull(1)?.toLongOrNull()
            if (idToken == null) return@post
            synchronized(lock) {
                if (tts !== engine || engineToken != token || requestToken != idToken) return@synchronized
            }
            fail(engine, message, idToken)
        }
    }

    private fun fail(engine: TextToSpeech, message: String, token: Long? = null) {
        val callback = synchronized(lock) {
            if (tts !== engine || (token != null && token != requestToken)) return@synchronized null
            isInitialized = false
            isInitializing = false
            pendingSpeak = null
            val listener = activeSpeakError ?: errorListener
            activeSpeakError = null
            tts = null
            engine.shutdown()
            listener
        }
        callback?.invoke(message)
    }
}
