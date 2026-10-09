package com.mojing.app.media

import android.content.Context
import android.media.MediaExtractor
import android.os.Handler
import android.os.Looper
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.executeCancellable
import com.mojing.app.media.newmedia.SpeechPlaybackControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/** Small Azure Speech REST adapter. Audio is played locally and is never persisted. */
object AzureSpeech {
    class SpeechException(message: String) : Exception(message)

    /** Only locally defined descriptions reach the UI; never expose provider bodies or keys. */
    fun failureMessage(error: Exception): String = when (error) {
        is SpeechException -> error.message ?: "微软语音失败，请重试"
        is LlmHttpException -> when (error.status) {
            401 -> "微软语音认证失败，请检查 Speech Key 是否有效，以及区域是否与密钥所属资源一致（401）"
            403 -> "微软语音访问被拒绝，请检查资源权限和可用额度（403）"
            429 -> "微软语音请求受限，请稍后重试并检查资源配额（429）"
            400 -> "微软语音不接受当前请求，请刷新音色列表后重新选择（400）"
            404 -> "微软语音地址不可用，请检查区域配置（404）"
            in 500..599 -> "微软语音服务暂时不可用，请稍后重试（${error.status}）"
            else -> "微软语音请求失败，请检查配置后重试（${error.status}）"
        }
        is java.net.SocketTimeoutException -> "微软语音请求超时，请检查网络后重试"
        is java.io.IOException -> "无法连接微软语音，请检查网络和区域设置"
        is IllegalArgumentException -> "请在语音设置填写有效的 Azure 区域和 Speech Key"
        else -> "微软语音处理失败，请重试"
    }
    private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
    private const val MAX_SSML_TEXT = 2_400
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    internal var audioMimeDetector: (File) -> String? = { detectAudioMime(it) }
    internal var fileSynthesis: suspend (String, String, String, String) -> ByteArray = { region, key, voice, chunk ->
        synthesize(region, key, voice, chunk)
    }

    suspend fun voices(region: String, key: String): List<VoiceOption> = withContext(Dispatchers.IO) {
        val normalizedRegion = normalizeRegion(region)
            ?: throw IllegalArgumentException("Azure 区域不能为空或格式无效")
        val apiKey = key.trim()
        if (apiKey.isEmpty()) throw IllegalArgumentException("Azure Speech Key 不能为空")
        val request = Request.Builder()
            .url("https://$normalizedRegion.tts.speech.microsoft.com/cognitiveservices/voices/list")
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .header("User-Agent", "MoJing/1.0")
            .get()
            .build()
        val body = try {
            client.executeCancellable(request) { response ->
                if (!response.isSuccessful) throw LlmHttpException(response.code)
                response.body?.string() ?: throw IllegalStateException("Azure 音色列表为空")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmHttpException) {
            throw e
        } catch (e: Exception) {
            throw SpeechException(failureMessage(e))
        }
        try {
            parseVoices(body)
        } catch (e: Exception) {
            throw SpeechException("微软返回的音色列表格式无效，请稍后刷新")
        }
    }

    suspend fun speak(
        context: Context,
        text: String,
        region: String,
        key: String,
        voiceId: String,
        control: SpeechPlaybackControl? = null,
    ): Boolean {
        val normalizedRegion = normalizeRegion(region)
            ?: throw SpeechException("请在语音设置填写有效的 Azure 区域")
        val apiKey = key.trim()
        val chunks = withContext(Dispatchers.Default) {
            SpeechChunks.split(TtsSpeakText.normalizeForSpeech(text), MAX_SSML_TEXT)
        }
        if (apiKey.isEmpty()) throw SpeechException("请在语音设置填写 Azure Speech Key")
        val voice = voiceId.trim().ifBlank { DEFAULT_VOICE }
        if (chunks.isEmpty()) return false
        val requestJob = currentCoroutineContext()[Job]
        val lease = control?.bind({}, {}, onClose = { requestJob?.cancel() })
        if (control != null && lease == null) return false
        return try {
            for ((index, chunk) in chunks.withIndex()) {
                if (control != null && !control.owns(lease!!)) return false
                control?.setCallbacks(lease!!, {}, {}, onClose = { requestJob?.cancel() })
                control?.updateIfOwned(lease!!) {
                    it.copy(
                        phase = if (control.isPaused()) SpeechPlaybackControl.Phase.PAUSED else SpeechPlaybackControl.Phase.PREPARING,
                        segmentIndex = index + 1,
                        segmentCount = chunks.size,
                    )
                }
                val bytes = synthesize(normalizedRegion, apiKey, voice, chunk)
                if (control != null && !control.awaitResume(lease!!)) return false
                var file: java.io.File? = null
                try {
                    withContext(Dispatchers.IO) {
                        file = java.io.File.createTempFile("azure_tts_", ".mp3", context.cacheDir)
                        file!!.writeBytes(bytes)
                    }
                    if (!playFile(file!!, control, lease, index + 1, chunks.size)) return false
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SpeechException(failureMessage(e))
        } finally {
            lease?.let { control?.unbind(it) }
        }
    }

    suspend fun synthesizeToFiles(
        text: String,
        region: String,
        key: String,
        voiceId: String,
        outputDir: File,
        attemptToken: String,
    ): List<SynthesizedSpeechFile> {
        val normalizedRegion = normalizeRegion(region)
            ?: throw SpeechException("请在语音设置填写有效的 Azure 区域")
        val apiKey = key.trim()
        if (apiKey.isEmpty()) throw SpeechException("请在语音设置填写 Azure Speech Key")
        require(attemptToken.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "无效的语音合成请求 token" }
        val chunks = withContext(Dispatchers.Default) {
            SpeechChunks.split(TtsSpeakText.normalizeForSpeech(text), MAX_SSML_TEXT)
        }
        if (chunks.isEmpty()) return emptyList()
        val directory = outputDir.canonicalFile
        require(directory.isDirectory || directory.mkdirs()) { "语音输出目录不可用" }
        val voice = voiceId.trim().ifBlank { DEFAULT_VOICE }
        val created = mutableListOf<File>()
        var completed = false
        try {
            chunks.forEachIndexed { index, chunk ->
                val bytes = fileSynthesis(normalizedRegion, apiKey, voice, chunk)
                val file = File(directory, "gen_voice_${attemptToken}_${index}.mp3")
                created += file
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                if (!file.isFile || file.length() <= 0L) throw SpeechException("微软未返回有效语音文件")
            }
            val result = withContext(Dispatchers.IO) {
                created.map { file ->
                    SynthesizedSpeechFile(file, audioMimeDetector(file)
                        ?: throw SpeechException("微软返回的音频格式无效"))
                }
            }
            completed = true
            return result
        } finally {
            if (!completed) withContext(NonCancellable + Dispatchers.IO) { created.forEach(File::delete) }
        }
    }

    internal suspend fun playFile(
        file: java.io.File,
        control: SpeechPlaybackControl? = null,
        lease: SpeechPlaybackControl.Lease? = null,
        segmentIndex: Int = 0,
        segmentCount: Int = 0,
    ): Boolean {
        var handle: TtsPlayer.PlaybackHandle? = null
        var completed = false
        try {
            withContext(Dispatchers.Main.immediate) {
                if (control != null && (lease == null || !control.owns(lease))) return@withContext
                val initialPaused = control?.isPaused() == true
                handle = TtsPlayer.playOwned(
                    file,
                    deleteWhenFinished = true,
                    initialPaused = initialPaused,
                    onPhaseChanged = { phase ->
                        lease?.let { control?.updateIfOwned(it) {
                            it.copy(
                                phase = when (phase) {
                                    TtsPlayer.Phase.PREPARING -> SpeechPlaybackControl.Phase.PREPARING
                                    TtsPlayer.Phase.PLAYING -> SpeechPlaybackControl.Phase.PLAYING
                                    TtsPlayer.Phase.PAUSED -> SpeechPlaybackControl.Phase.PAUSED
                                },
                                segmentIndex = segmentIndex,
                                segmentCount = segmentCount,
                            )
                        } }
                    },
                )
                if (handle != null && control != null) {
                    val ownedHandle = handle!!
                    val attached = control.setCallbacks(
                        lease!!,
                        onPause = { mainHandler.post { if (control.owns(lease) && control.isPaused()) TtsPlayer.pause(ownedHandle) } },
                        onResume = { mainHandler.post { if (control.owns(lease) && !control.isPaused()) TtsPlayer.resume(ownedHandle) } },
                        onClose = { mainHandler.post { TtsPlayer.stop(ownedHandle) } },
                    )
                    if (!attached) {
                        TtsPlayer.stop(ownedHandle)
                    } else if (control.isPaused()) {
                        TtsPlayer.pause(ownedHandle)
                    } else {
                        TtsPlayer.resume(ownedHandle)
                    }
                }
            }
            if (handle == null) return false
            completed = TtsPlayer.awaitCompletion(handle!!)
            return completed
        } catch (e: CancellationException) {
            handle?.let { owned ->
                withContext(NonCancellable + Dispatchers.Main) { TtsPlayer.stop(owned) }
            }
            throw e
        } catch (_: Exception) {
            handle?.let { owned ->
                withContext(NonCancellable + Dispatchers.Main) { TtsPlayer.stop(owned) }
            }
            return false
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (!completed) handle?.let(TtsPlayer::stop)
                if (control != null && lease != null) control.setCallbacks(lease, {}, {})
            }
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun synthesize(region: String, key: String, voice: String, text: String): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                .header("Ocp-Apim-Subscription-Key", key)
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", "audio-24khz-160kbitrate-mono-mp3")
                .header("User-Agent", "MoJing/1.0")
                .post(buildSsml(voice, text).toRequestBody("application/ssml+xml; charset=utf-8".toMediaType()))
                .build()
            client.executeCancellable(request) { response ->
                if (!response.isSuccessful) throw LlmHttpException(response.code)
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
                    ?: throw SpeechException("微软未返回语音内容，请重试")
            }
        }

    private fun detectAudioMime(file: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).asSequence()
                .mapNotNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("audio/") }
        } catch (_: Exception) { null } finally { extractor.release() }
    }

    internal fun buildSsml(voiceId: String, text: String): String =
        "<speak version=\"1.0\" xml:lang=\"zh-CN\"><voice name=\"${escapeXml(voiceId)}\">${escapeXml(text)}</voice></speak>"

    internal fun normalizeRegion(region: String): String? {
        val value = region.trim().lowercase(Locale.ROOT)
        return value.takeIf { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")) }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun parseVoices(raw: String): List<VoiceOption> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("ShortName").trim()
                if (id.isEmpty()) continue
                val display = item.optString("LocalName").trim()
                    .ifBlank { item.optString("DisplayName").trim() }
                val locale = item.optString("Locale").trim()
                val label = listOf(display, locale).filter(String::isNotBlank).joinToString(" · ")
                add(VoiceOption(id, label.ifBlank { id }, locale))
            }
        }.distinctBy { it.id }
            .sortedWith(compareByDescending<VoiceOption> { it.id.startsWith("zh-", ignoreCase = true) }.thenBy { it.name })
    }
}
