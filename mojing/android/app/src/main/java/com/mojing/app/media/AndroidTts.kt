package com.mojing.app.media

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.mojing.app.data.VoiceChoice
import com.mojing.app.util.UsbSessionLog
import com.mojing.app.media.newmedia.SpeechPlaybackControl
import java.util.Locale
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Android TTS owner. Engine instances are replaced atomically when a voice is changed. */
object AndroidTts {
    internal var engineFactory: (Context, (Int) -> Unit, String?) -> TextToSpeech = { context, callback, packageName ->
        if (packageName == null) TextToSpeech(context, callback) else TextToSpeech(context, callback, packageName)
    }
    internal var textDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
    internal var audioMimeDetector: (File) -> String? = { detectAudioMime(it) }
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
    private var nextPlanToken = 0L
    private var engineToken = 0L
    private var activeEngineId = "system"
    private var activeVoiceId = ""
    private var lastUtteranceId: String? = null
    private var previewCompletion: Pair<Long, kotlinx.coroutines.CompletableDeferred<Boolean>>? = null
    private var activeControl: SpeechPlaybackControl? = null
    private var activeControlLease: SpeechPlaybackControl.Lease? = null
    private var activeChunks: List<String> = emptyList()
    private var activeChunkIndex = 0
    private var activeChunkText = ""
    private var activeRangeStart: Int? = null
    private var activePlanPaused = false
    private var activeStarted = false
    private var activePlanToken: Long? = null
    private var activeFileSynthesis: FileSynthesis? = null
    internal var fileValidationDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    private data class FileSynthesis(
        val context: Context,
        val chunks: List<String>,
        val outputDir: File,
        val attemptToken: String,
        val completion: kotlinx.coroutines.CompletableDeferred<List<SynthesizedSpeechFile>>,
        val requestToken: Long,
        var index: Int = 0,
        val files: MutableList<File> = mutableListOf(),
        val mimeTypes: MutableList<String> = mutableListOf(),
        var currentFile: File? = null,
        var currentUtteranceId: String? = null,
        var validating: Boolean = false,
    )

    /** Uses the same engine owner; cancellation stops only this speech request. */
    suspend fun speakAwaitCompletion(
        context: Context,
        text: String,
        choice: VoiceChoice,
        control: SpeechPlaybackControl? = null,
    ): Boolean {
        val prepared = kotlinx.coroutines.withContext(textDispatcher) {
            val cleaned = TtsSpeakText.normalizeForSpeech(text).trim()
            PreparedSpeech(cleaned, if (cleaned.isBlank()) emptyList()
                else SpeechChunks.split(cleaned, TextToSpeech.getMaxSpeechInputLength()))
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (prepared.chunks.isEmpty()) return@withContext false
            val completion = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val token = synchronized(lock) {
                speakPreparedWithVoice(
                    context, prepared.text, choice,
                    onError = { error -> completion.completeExceptionally(IllegalStateException(error)) },
                    preparedChunks = prepared.chunks,
                    control = control,
                )
                requestToken.also { if (!completion.isCompleted) previewCompletion = it to completion }
            }
            try { completion.await() }
            finally {
                synchronized(lock) {
                    if (previewCompletion?.first == token) stop()
                }
            }
        }
    }

    suspend fun preview(context: Context, text: String, choice: VoiceChoice): Boolean =
        speakAwaitCompletion(context, text, choice)

    suspend fun synthesizeToFiles(
        context: Context,
        text: String,
        choice: VoiceChoice,
        outputDir: File,
        attemptToken: String,
    ): List<SynthesizedSpeechFile> {
        val prepared = kotlinx.coroutines.withContext(textDispatcher) {
            val cleaned = TtsSpeakText.normalizeForSpeech(text).trim()
            PreparedSpeech(cleaned, if (cleaned.isBlank()) emptyList()
                else SpeechChunks.split(cleaned, TextToSpeech.getMaxSpeechInputLength()))
        }
        if (prepared.chunks.isEmpty()) return emptyList()
        require(choice.engineId != "azure") { "Azure 语音请使用 AzureSpeech 文件合成接口" }
        require(attemptToken.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "无效的语音合成请求 token" }
        val completion = kotlinx.coroutines.CompletableDeferred<List<SynthesizedSpeechFile>>()
        var stateRef: FileSynthesis? = null
        var handedOff = false
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                synchronized(lock) {
                    if (activePlanToken != null || activeFileSynthesis != null || pendingSpeak != null ||
                        previewCompletion != null || isInitializing
                    ) throw IllegalStateException("朗读正忙，无法开始自动配音")
                    val packageName = requestedEnginePackage(context, choice)
                    val canonical = outputDir.canonicalFile
                    require(canonical.isDirectory || canonical.mkdirs()) { "语音输出目录不可用" }
                    requestToken++
                    val token = requestToken
                    val state = FileSynthesis(context.applicationContext, prepared.chunks, canonical, attemptToken, completion, token)
                    stateRef = state
                    activeFileSynthesis = state
                    if (!isInitialized || tts == null || activeEngineId != choice.engineId || activeVoiceId != choice.voiceId.trim()) {
                        disposeEngineLocked(preserveControlLease = false)
                        startEngineLocked(appContext ?: context.applicationContext, choice, preferInstalledChinese = false,
                            packageName = packageName)
                    } else beginFileSynthesisLocked(tts!!, state)
                    token
                }
            }
            val result = completion.await()
            handedOff = true
            return result
        } finally {
            synchronized(lock) {
                val state = stateRef
                if (!handedOff && state != null) {
                    val wasActive = activeFileSynthesis === state
                    if (wasActive) {
                        activeFileSynthesis = null
                    }
                    cleanupFileStateLocked(state, IllegalStateException("语音文件合成已取消"))
                    if (wasActive && requestToken == state.requestToken) {
                        tts?.stop()
                        requestToken++
                    }
                }
            }
        }
    }

    private fun beginFileSynthesisLocked(engine: TextToSpeech, state: FileSynthesis) {
        if (activeFileSynthesis !== state || tts !== engine) return
        lastUtteranceId = null
        synthesizeFileChunkLocked(engine, state)
    }

    private fun synthesizeFileChunkLocked(engine: TextToSpeech, state: FileSynthesis) {
        val file = File(state.outputDir, "gen_voice_${state.attemptToken}_${state.index}.wav")
        state.currentFile = file
        state.currentUtteranceId = "file_${state.attemptToken}_${state.index}_${++nextUtteranceId}"
        val result = runCatching {
            engine.synthesizeToFile(state.chunks[state.index], Bundle(), file, state.currentUtteranceId)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) failFileSynthesisLocked(IllegalStateException("系统语音文件合成失败"))
    }

    private fun finishFileChunkLocked(engine: TextToSpeech, utteranceId: String?) {
        val state = activeFileSynthesis ?: return
        if (tts !== engine || engineToken <= 0L || state.currentUtteranceId != utteranceId) return
        val file = state.currentFile ?: return failFileSynthesisLocked(IllegalStateException("系统语音文件缺失"))
        if (state.validating) return
        state.validating = true
        CoroutineScope(fileValidationDispatcher).launch {
            val mime = runCatching { audioMimeDetector(file) }.getOrNull()
            mainHandler.post {
                synchronized(lock) {
                    if (activeFileSynthesis !== state || state.currentUtteranceId != utteranceId) return@synchronized
                    state.validating = false
                    if (mime == null) {
                        failFileSynthesisLocked(IllegalStateException("系统语音返回的音频格式无效"))
                        return@synchronized
                    }
                    state.files += file
                    state.mimeTypes += mime
                    state.currentFile = null
                    if (++state.index < state.chunks.size) synthesizeFileChunkLocked(engine, state)
                    else {
                        val result = state.files.mapIndexed { index, item ->
                            SynthesizedSpeechFile(item, state.mimeTypes[index])
                        }
                        activeFileSynthesis = null
                        state.completion.complete(result)
                    }
                }
            }
        }
    }

    private fun failFileSynthesisLocked(error: Throwable) {
        val state = activeFileSynthesis ?: return
        activeFileSynthesis = null
        cleanupFileStateLocked(state, error)
    }

    private fun cleanupFileStateLocked(state: FileSynthesis, error: Throwable) {
        state.currentFile?.delete()
        state.files.forEach(File::delete)
        state.completion.completeExceptionally(error)
    }

    private fun cancelFileSynthesisLocked(error: Throwable) = failFileSynthesisLocked(error)

    private fun detectAudioMime(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).asSequence()
                .mapNotNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("audio/") }
        } catch (_: Exception) { null } finally { extractor.release() }
    }

    private fun finishPreview(success: Boolean) {
        previewCompletion?.second?.complete(success)
        previewCompletion = null
    }

    private fun publishPlanStateLocked() {
        if (activePlanToken == null) return
        activeControlLease?.let { lease -> activeControl?.updateIfOwned(lease) {
            it.copy(
                phase = when {
                    activePlanPaused -> SpeechPlaybackControl.Phase.PAUSED
                    isInitializing -> SpeechPlaybackControl.Phase.PREPARING
                    !activeStarted -> SpeechPlaybackControl.Phase.PREPARING
                    else -> SpeechPlaybackControl.Phase.PLAYING
                },
                segmentIndex = activeChunkIndex + 1,
                segmentCount = activeChunks.size,
            )
        } }
    }

    private data class PreparedSpeech(val text: String, val chunks: List<String>)
    private data class PendingSpeak(val text: String, val choice: VoiceChoice, val chunks: List<String>? = null)

    /** Existing API: initialize the device default engine and prefer an installed Chinese voice. */
    fun init(context: Context) {
        synchronized(lock) {
            if (activeFileSynthesis != null) cancelFileSynthesisLocked(IllegalStateException("系统朗读被新的朗读请求取代"))
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
        speakPreparedWithVoice(context, cleaned, choice, onError)
    }

    private fun speakPreparedWithVoice(
        context: Context,
        cleaned: String,
        choice: VoiceChoice,
        onError: ((String) -> Unit)?,
        preparedChunks: List<String>? = null,
        control: SpeechPlaybackControl? = null,
    ) {
        if (cleaned.isEmpty()) return
        if (choice.engineId == "azure") {
            onError?.invoke("Azure 语音请使用 AzureSpeech 播放接口")
            return
        }
        val packageName = try { requestedEnginePackage(context, choice) }
        catch (failure: Exception) {
            onError?.invoke(failure.message ?: "指定的系统朗读引擎不可用，请重新选择")
            return
        }
        synchronized(lock) {
            if (activeFileSynthesis != null) cancelFileSynthesisLocked(IllegalStateException("系统朗读被新的朗读请求取代"))
            appContext = context.applicationContext
            finishPreview(false)
            requestToken++
            val request = requestToken
            activeSpeakError = onError
            val oldControl = activeControl
            val oldLease = activeControlLease
            activeControl = control
            if (oldControl != null && oldLease != null) oldControl.unbind(oldLease)
            var callbackLease: SpeechPlaybackControl.Lease? = null
            callbackLease = control?.bind(
                onPause = { mainHandler.post { pauseSpeech(request, callbackLease) } },
                onResume = { mainHandler.post { resumeSpeech(request, callbackLease) } },
                onClose = { mainHandler.post { stopIfOwned(request, callbackLease) } },
            )
            activeControlLease = callbackLease
            if (control != null && activeControlLease == null) {
                stop()
                onError?.invoke("朗读控制已关闭")
                return
            }
            control?.let { owner -> activeControlLease?.let { lease ->
                owner.updateIfOwned(lease) {
                    it.copy(
                        phase = SpeechPlaybackControl.Phase.PREPARING,
                        segmentIndex = 1,
                        segmentCount = preparedChunks?.size ?: 0,
                    )
                }
            } }
            pendingSpeak = PendingSpeak(cleaned, choice, preparedChunks)
            val sameEngine = isInitialized && activeEngineId == choice.engineId && activeVoiceId == choice.voiceId.trim()
            if (sameEngine && tts != null) {
                val pending = pendingSpeak
                pendingSpeak = null
                enqueue(tts!!, requestToken, pending!!.text, pending.chunks)
            } else {
                disposeEngineLocked(preserveControlLease = true)
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
            if (activeFileSynthesis != null) cancelFileSynthesisLocked(IllegalStateException("系统朗读被新的朗读请求取代"))
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
            if (activeFileSynthesis != null) cancelFileSynthesisLocked(IllegalStateException("系统语音文件合成已停止"))
            finishPreview(false)
            requestToken++
            pendingSpeak = null
            activeSpeakError = null
            activeControlLease?.let { activeControl?.unbind(it) }
            activeControlLease = null
            activeControl = null
            activePlanToken = null
            activeChunks = emptyList()
            tts?.stop()
        }
    }

    private fun pauseSpeech(token: Long, lease: SpeechPlaybackControl.Lease?) {
        synchronized(lock) {
            if (requestToken != token || activePlanToken == null || activeControlLease !== lease) return
            activePlanPaused = true
            tts?.stop()
            publishPlanStateLocked()
        }
    }

    private fun resumeSpeech(token: Long, lease: SpeechPlaybackControl.Lease?) {
        synchronized(lock) {
            if (requestToken != token || activePlanToken == null || activeControlLease !== lease) return
            activePlanPaused = false
            if (!isInitialized || tts == null) {
                publishPlanStateLocked()
                return
            }
            var resumedFromStart = activeRangeStart == null
            activeRangeStart?.let { start ->
                val text = activeChunkText
                if (start in 1 until text.length) {
                    activeChunkText = text.substring(start)
                    resumedFromStart = false
                }
                activeRangeStart = null
            }
            activeControlLease?.let { lease -> activeControl?.updateIfOwned(lease) { it.copy(resumedFromSegmentStart = resumedFromStart) } }
            speakCurrentChunkLocked(tts!!, token)
        }
    }

    private fun stopIfOwned(token: Long, lease: SpeechPlaybackControl.Lease?) {
        synchronized(lock) {
            if (requestToken == token && activeControlLease === lease) stop()
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
            candidate = engineFactory(context, callback, packageName)
            tts = candidate
            candidate!!.setOnUtteranceProgressListener(listenerFor(candidate!!, candidateToken))
            callbackStatus?.let(callback)
        } catch (error: Exception) {
            UsbSessionLog.w("SystemTts", "init_error type=${error.javaClass.simpleName}")
            activeFileSynthesis?.let { failFileSynthesisLocked(IllegalStateException("指定的系统朗读引擎无法启动，请检查系统语音设置")) }
            engineToken++
            tts = null
            isInitialized = false
            isInitializing = false
            pendingSpeak = null
            runCatching { candidate?.shutdown() }
            previewCompletion?.second?.completeExceptionally(IllegalStateException("指定的系统朗读引擎无法启动，请检查系统语音设置"))
            previewCompletion = null
            activeControlLease?.let { activeControl?.unbind(it) }
            activeControlLease = null
            activeControl = null
            val listener = activeSpeakError ?: errorListener
            activeSpeakError = null
            listener?.invoke("指定的系统朗读引擎无法启动，请检查系统语音设置")
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
                val selected = runCatching {
                    candidate.voices.orEmpty()
                        .asSequence()
                        .filter { it.name == requestedVoiceId }
                        .sortedWith(compareBy<Voice> {
                            if (it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) 1 else 0
                        }.thenBy { TtsVoicePolicy.duplicateNameLanguageRank(it.locale.toLanguageTag()) }
                            .thenBy { it.locale.toLanguageTag() })
                        .firstOrNull()
                }.getOrNull()
                if (selected == null) {
                    fail(candidate, "指定的系统音色不可用，请重新选择该引擎的音色")
                    return
                }
                if (selected.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
                    fail(candidate, "该音色的语音数据尚未安装，请在系统文字转语音设置中安装后重试")
                    return
                }
                if (runCatching { candidate.setVoice(selected) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.ERROR) {
                    fail(candidate, "指定的系统音色无法使用，请更换音色后重试")
                    return
                }
                val activeVoice = runCatching { candidate.voice }.getOrNull()
                if (activeVoice != null && !TtsVoicePolicy.isRequestedVoiceActive(
                    selected.name, selected.locale.toLanguageTag(),
                    activeVoice.name, activeVoice.locale.toLanguageTag(),
                )) {
                    fail(candidate, "朗读引擎未切换到所选语种或音色，请更换语音或引擎")
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
                    enqueue(candidate, requestToken, pending.text, pending.chunks)
                }
            }
            activeFileSynthesis?.let { beginFileSynthesisLocked(candidate, it) }
        }
    }

    private fun listenerFor(engine: TextToSpeech, token: Long) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            synchronized(lock) {
                if (tts === engine && engineToken == token && utteranceId == lastUtteranceId && !activePlanPaused) {
                    activeStarted = true
                    activeControlLease?.let { lease -> activeControl?.updateIfOwned(lease) { it.copy(phase = SpeechPlaybackControl.Phase.PLAYING) } }
                }
            }
        }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            synchronized(lock) {
                if (tts === engine && engineToken == token && utteranceId == lastUtteranceId && !activePlanPaused && start in 0..activeChunkText.length) {
                    activeRangeStart = start.coerceAtMost(activeChunkText.length)
                    if (activeRangeStart!! > 0 && activeRangeStart!! < activeChunkText.length && Character.isLowSurrogate(activeChunkText[activeRangeStart!!])) {
                        activeRangeStart = activeRangeStart!! - 1
                    }
                }
            }
        }
        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                synchronized(lock) {
                    if (tts === engine && engineToken == token && activeFileSynthesis?.currentUtteranceId == utteranceId) {
                        finishFileChunkLocked(engine, utteranceId)
                    } else if (tts === engine && engineToken == token && utteranceId == lastUtteranceId) {
                        if (activePlanPaused) return@synchronized
                        if (activePlanToken != null && activeChunkIndex + 1 < activeChunks.size) {
                            activeChunkIndex++
                            activeRangeStart = null
                            activeChunkText = activeChunks[activeChunkIndex]
                            activeStarted = false
                            speakCurrentChunkLocked(engine, requestToken)
                        } else {
                            activePlanToken = null
                            activeChunks = emptyList()
                            activeControlLease?.let { lease -> activeControl?.updateIfOwned(lease) { it.copy(phase = SpeechPlaybackControl.Phase.IDLE) } }
                            activeControlLease?.let { lease -> activeControl?.unbind(lease) }
                            activeControlLease = null
                            activeControl = null
                            finishPreview(true)
                            activeSpeakError = null
                        }
                    }
                }
            }
        }
        override fun onError(utteranceId: String?) {
            synchronized(lock) {
                if (tts === engine && engineToken == token && activeFileSynthesis?.currentUtteranceId == utteranceId) {
                    failFileSynthesisLocked(IllegalStateException("系统语音文件合成失败"))
                } else {
                    handleError(engine, token, utteranceId, "系统朗读失败，请检查设备朗读引擎")
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            UsbSessionLog.w("SystemTts", "synthesis_error code=$errorCode engineToken=$token")
            synchronized(lock) {
                if (tts === engine && engineToken == token && activeFileSynthesis?.currentUtteranceId == utteranceId) {
                    failFileSynthesisLocked(IllegalStateException(TtsErrorPolicy.message(errorCode)))
                } else {
                    handleError(engine, token, utteranceId, TtsErrorPolicy.message(errorCode))
                }
            }
        }
    }

    private fun enqueue(engine: TextToSpeech, token: Long, text: String, preparedChunks: List<String>? = null) {
        val chunks = preparedChunks ?: SpeechChunks.split(text, TextToSpeech.getMaxSpeechInputLength())
        activePlanToken = ++nextPlanToken
        activeChunks = chunks
        activeChunkIndex = 0
        activeChunkText = chunks.firstOrNull().orEmpty()
        activeRangeStart = null
        activeStarted = false
        activePlanPaused = activeControl?.isPaused() == true
        publishPlanStateLocked()
        if (!activePlanPaused) speakCurrentChunkLocked(engine, token)
    }

    private fun speakCurrentChunkLocked(engine: TextToSpeech, token: Long) {
        if (activePlanToken == null || requestToken != token || activePlanPaused) {
            publishPlanStateLocked()
            return
        }
        val id = "mojing-$token-${++nextUtteranceId}"
        lastUtteranceId = id
        activeStarted = false
        val result = engine.speak(activeChunkText, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            fail(engine, "系统朗读失败，请检查设备朗读引擎", token)
        } else {
            publishPlanStateLocked()
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
        voices.firstOrNull {
            it.name == selected.name && it.locale.toLanguageTag() == selected.languageTag
        }?.let { voice: Voice -> runCatching { engine.setVoice(voice) } }
    }

    private fun logVoiceState(engine: TextToSpeech, choice: VoiceChoice) {
        runCatching {
            val voice = engine.voice
            UsbSessionLog.i("SystemTts", "requestedEngine=${choice.engineId} voice=${voice?.name?.take(120)} locale=${voice?.locale?.toLanguageTag()}")
        }
    }

    /** Match the framework's engine discovery; init success can still reflect bind-time fallback. */
    private fun requestedEnginePackage(context: Context, choice: VoiceChoice): String? {
        if (choice.engineId == "system") return null
        val packageName = choice.engineId.takeIf { it.startsWith("android:") }
            ?.removePrefix("android:")?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalStateException("指定的系统朗读引擎无效，请重新选择")
        val services = context.applicationContext.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(packageName),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        val service = services.singleOrNull()?.serviceInfo
        check(service != null && service.enabled && service.packageName == packageName) {
            "指定的系统朗读引擎未安装或不可用，请重新选择"
        }
        return packageName
    }

    private fun handleError(engine: TextToSpeech, token: Long, utteranceId: String?, message: String) {
        mainHandler.post {
            val accepted = synchronized(lock) {
                tts === engine && engineToken == token && utteranceId != null && utteranceId == lastUtteranceId && activePlanToken != null && !activePlanPaused
            }
            if (!accepted) return@post
            fail(engine, message, expectedUtteranceId = utteranceId)
        }
    }

    private fun fail(engine: TextToSpeech, message: String, token: Long? = null, expectedUtteranceId: String? = null) {
        val callback = synchronized(lock) {
            if (tts !== engine || (token != null && token != requestToken) ||
                (expectedUtteranceId != null && expectedUtteranceId != lastUtteranceId)) return@synchronized null
            if (activeFileSynthesis != null) {
                failFileSynthesisLocked(IllegalStateException(message))
            }
            isInitialized = false
            isInitializing = false
            pendingSpeak = null
            val listener = activeSpeakError ?: errorListener
            previewCompletion?.second?.completeExceptionally(IllegalStateException(message))
            previewCompletion = null
            activeSpeakError = null
            tts = null
            activeControlLease?.let { activeControl?.unbind(it) }
            activeControlLease = null
            activeControl = null
            activePlanToken = null
            activeChunks = emptyList()
            engine.shutdown()
            listener
        }
        callback?.invoke(message)
    }

    private fun disposeEngineLocked(preserveControlLease: Boolean = false) {
        isInitialized = false
        isInitializing = false
        engineToken++
        if (!preserveControlLease) {
            activeControlLease?.let { activeControl?.unbind(it) }
            activeControlLease = null
            activeControl = null
        }
        activePlanToken = null
        activeChunks = emptyList()
        tts?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        tts = null
    }
}
