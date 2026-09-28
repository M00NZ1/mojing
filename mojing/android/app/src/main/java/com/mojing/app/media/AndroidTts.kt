package com.mojing.app.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.mojing.app.data.VoiceChoice
import com.mojing.app.util.UsbSessionLog
import java.util.Locale

/** Android TTS owner. Engine instances are replaced atomically when a voice is changed. */
object AndroidTts {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isInitializing = false
    private var pendingSpeak: PendingSpeak? = null
    private var appContext: Context? = null
    private var nextUtteranceId = 0L
    private var errorListener: ((String) -> Unit)? = null
    private var activeSpeakError: ((String) -> Unit)? = null
    private var requestToken = 0L
    private var engineToken = 0L
    private var activeEngineId = "system"
    private var activeVoiceId = ""
    private var lastUtteranceId: String? = null
    private var previewCompletion: Pair<Long, kotlinx.coroutines.CompletableDeferred<Boolean>>? = null

    /** Uses the same engine owner; cancellation stops only this speech request. */
    suspend fun speakAwaitCompletion(context: Context, text: String, choice: VoiceChoice): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (TtsSpeakText.normalizeForSpeech(text).isBlank()) return@withContext false
            val completion = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val token = synchronized(lock) {
                speakWithVoice(context, text, choice) { error -> completion.completeExceptionally(IllegalStateException(error)) }
                requestToken.also { if (!completion.isCompleted) previewCompletion = it to completion }
            }
            try { completion.await() }
            finally {
                synchronized(lock) {
                    if (previewCompletion?.first == token) stop()
                }
            }
        }

    suspend fun preview(context: Context, text: String, choice: VoiceChoice): Boolean =
        speakAwaitCompletion(context, text, choice)

    private fun finishPreview(success: Boolean) {
        previewCompletion?.second?.complete(success)
        previewCompletion = null
    }

    private data class PendingSpeak(val text: String, val choice: VoiceChoice)

    /** Existing API: initialize the device default engine and prefer an installed Chinese voice. */
    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (isInitialized || isInitializing) return
            startEngineLocked(appContext!!, VoiceChoice(), preferInstalledChinese = true)
        }
    }

    /** Uses the requested Android engine package and, when supplied, its exact voice id. */
    fun speakWithVoice(
        context: Context,
        text: String,
        choice: VoiceChoice,
        onError: ((String) -> Unit)?,
    ) {
        val cleaned = TtsSpeakText.normalizeForSpeech(text).trim()
        if (cleaned.isEmpty()) return
        if (choice.engineId == "azure") {
            onError?.invoke("Azure 语音请使用 AzureSpeech 播放接口")
            return
        }
        val packageName = when {
            choice.engineId == "system" -> null
            choice.engineId.startsWith("android:") -> choice.engineId.removePrefix("android:").trim()
                .takeIf { it.isNotEmpty() }
            else -> null
        }
        if (choice.engineId != "system" && packageName == null) {
            onError?.invoke("指定的系统朗读引擎无效，请重新选择")
            return
        }
        if (packageName != null) {
            val service = context.applicationContext.packageManager.resolveService(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(packageName),
                0,
            )
            if (service?.serviceInfo == null) {
                onError?.invoke("指定的系统朗读引擎未安装或不可用，请重新选择")
                return
            }
        }
        synchronized(lock) {
            appContext = context.applicationContext
            finishPreview(false)
            requestToken++
            activeSpeakError = onError
            pendingSpeak = PendingSpeak(cleaned, choice)
            val sameEngine = isInitialized && activeEngineId == choice.engineId && activeVoiceId == choice.voiceId.trim()
            if (sameEngine && tts != null) {
                val pending = pendingSpeak
                pendingSpeak = null
                enqueue(tts!!, requestToken, pending!!.text)
            } else {
                disposeEngineLocked()
                startEngineLocked(appContext!!, choice, preferInstalledChinese = false, packageName = packageName)
            }
        }
    }

    /** Optional UI hook; existing callers remain source compatible. */
    fun setErrorListener(listener: ((String) -> Unit)?) {
        synchronized(lock) { errorListener = listener }
    }

    fun speak(text: String, onError: ((String) -> Unit)? = null) {
        val cleaned = TtsSpeakText.normalizeForSpeech(text).trim()
        if (cleaned.isEmpty()) return
        synchronized(lock) {
            finishPreview(false)
            requestToken++
            activeSpeakError = onError
            if (isInitialized && tts != null) {
                enqueue(tts!!, requestToken, cleaned)
            } else {
                pendingSpeak = PendingSpeak(cleaned, VoiceChoice())
                appContext?.let { if (!isInitializing) startEngineLocked(it, VoiceChoice(), true) }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            finishPreview(false)
            requestToken++
            pendingSpeak = null
            activeSpeakError = null
            tts?.stop()
        }
    }

    private fun startEngineLocked(
        context: Context,
        choice: VoiceChoice,
        preferInstalledChinese: Boolean,
        packageName: String? = choice.engineId.removePrefix("android:").takeIf { choice.engineId.startsWith("android:") },
    ) {
        if (isInitializing) return
        isInitializing = true
        var candidate: TextToSpeech? = null
        var callbackStatus: Int? = null
        val candidateToken = ++engineToken
        val callback: (Int) -> Unit = { status ->
            val current = synchronized(lock) {
                if (candidate == null) {
                    callbackStatus = status
                    null
                } else {
                    candidate
                }
            }
            current?.let {
                mainHandler.post { finishInitialization(it, candidateToken, choice, preferInstalledChinese, status) }
            }
        }
        try {
            candidate = if (packageName == null) {
                TextToSpeech(context, callback)
            } else {
                TextToSpeech(context, callback, packageName)
            }
            tts = candidate
            candidate!!.setOnUtteranceProgressListener(listenerFor(candidate!!, candidateToken))
            callbackStatus?.let(callback)
        } catch (_: Exception) {
            isInitializing = false
            pendingSpeak = null
            activeSpeakError?.invoke("指定的系统朗读引擎无法启动，请检查系统语音设置")
            activeSpeakError = null
        }
    }

    private fun finishInitialization(
        candidate: TextToSpeech,
        candidateToken: Long,
        choice: VoiceChoice,
        preferInstalledChinese: Boolean,
        status: Int,
    ) {
        synchronized(lock) {
            if (tts !== candidate || engineToken != candidateToken) {
                candidate.shutdown()
                return
            }
            isInitializing = false
            if (status != TextToSpeech.SUCCESS) {
                fail(candidate, "指定的系统朗读引擎初始化失败")
                return
            }
            val requestedVoiceId = choice.voiceId.trim()
            if (requestedVoiceId.isNotEmpty()) {
                val selected = runCatching { candidate.voices.orEmpty().firstOrNull { it.name == requestedVoiceId } }.getOrNull()
                if (selected == null) {
                    fail(candidate, "指定的系统音色不可用，请重新选择该引擎的音色")
                    return
                }
                if (runCatching { candidate.setVoice(selected) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.ERROR) {
                    fail(candidate, "指定的系统音色无法使用，请更换音色后重试")
                    return
                }
            } else if (preferInstalledChinese) {
                val language = runCatching { candidate.setLanguage(Locale.CHINESE) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    fail(candidate, "系统朗读引擎不支持中文，请安装中文语音数据")
                    return
                }
                if (preferInstalledChinese) selectInstalledChineseVoice(candidate)
            }
            activeEngineId = choice.engineId
            activeVoiceId = requestedVoiceId
            isInitialized = true
            logVoiceState(candidate, choice)
            pendingSpeak?.let { pending ->
                if (pending.choice.engineId == choice.engineId && pending.choice.voiceId.trim() == requestedVoiceId) {
                    pendingSpeak = null
                    enqueue(candidate, requestToken, pending.text)
                }
            }
        }
    }

    private fun listenerFor(engine: TextToSpeech, token: Long) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                synchronized(lock) {
                    if (tts === engine && engineToken == token && utteranceId == lastUtteranceId) {
                        finishPreview(true)
                        activeSpeakError = null
                    }
                }
            }
        }
        override fun onError(utteranceId: String?) {
            handleError(engine, token, utteranceId, "系统朗读失败，请检查设备朗读引擎")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            UsbSessionLog.w("SystemTts", "synthesis_error code=$errorCode engineToken=$token")
            handleError(engine, token, utteranceId, TtsErrorPolicy.message(errorCode))
        }
    }

    private fun enqueue(engine: TextToSpeech, token: Long, text: String) {
        val chunks = SpeechChunks.split(text, TextToSpeech.getMaxSpeechInputLength())
        val ids = chunks.map { "mojing-$token-${++nextUtteranceId}" }
        lastUtteranceId = ids.lastOrNull()
        chunks.forEachIndexed { index, chunk ->
            val id = ids[index]
            val result = engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            if (result == TextToSpeech.ERROR) {
                fail(engine, "系统朗读失败，请检查设备朗读引擎", token)
                return
            }
        }
    }

    private fun selectInstalledChineseVoice(engine: TextToSpeech) {
        val voices = runCatching { engine.voices }.getOrNull() ?: return
        val selected = TtsVoicePolicy.chooseChineseVoice(voices.map { voice ->
            TtsVoiceInfo(
                name = voice.name,
                languageTag = voice.locale.toLanguageTag(),
                requiresNetwork = voice.isNetworkConnectionRequired,
                quality = voice.quality,
                latency = voice.latency,
                installed = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true,
            )
        }) ?: return
        voices.firstOrNull { it.name == selected.name }?.let { voice: Voice -> runCatching { engine.setVoice(voice) } }
    }

    private fun logVoiceState(engine: TextToSpeech, choice: VoiceChoice) {
        runCatching {
            val voice = engine.voice
            UsbSessionLog.i("SystemTts", "engine=${choice.engineId} voice=${voice?.name?.take(120)} locale=${voice?.locale?.toLanguageTag()}")
        }
    }

    private fun handleError(engine: TextToSpeech, token: Long, utteranceId: String?, message: String) {
        mainHandler.post {
            val idToken = utteranceId?.split('-')?.getOrNull(1)?.toLongOrNull() ?: return@post
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
            previewCompletion?.second?.completeExceptionally(IllegalStateException(message))
            previewCompletion = null
            activeSpeakError = null
            tts = null
            engine.shutdown()
            listener
        }
        callback?.invoke(message)
    }

    private fun disposeEngineLocked() {
        isInitialized = false
        isInitializing = false
        engineToken++
        tts?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        tts = null
    }
}
