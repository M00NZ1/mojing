package com.mojing.app.media

import android.content.Context
import android.media.MediaPlayer
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.remote.DmxChatTtsApi
import com.mojing.app.data.remote.DmxGeminiApi
import com.mojing.app.data.remote.DmxResponsesApi
import com.mojing.app.data.remote.executeCancellable
import com.mojing.app.domain.config.DmxApiRouting
import com.mojing.app.domain.config.FishAudioRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 `POST /v1/audio/speech`（硅基流动等）。成功时在主线程播放临时 mp3。
 */
object HttpTts {
    @Volatile
    var lastFishAudioError: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 多行根地址「置顶赢家」时与存储字段比对用，逻辑同内部归一化。 */
    fun normalizeVoiceRoutingBase(baseUrl: String): String =
        OpenAiCompatibleRouting.normalizeBase(baseUrl)

    private fun speechUrl(baseUrl: String): String =
        OpenAiCompatibleRouting.buildAudioSpeechUrl(baseUrl)

    /**
     * 硅基流动 CosyVoice 等要求 `voice` 为 `模型名:预置音色`（如 `FunAudioLLM/CosyVoice2-0.5B:claire`）；
     * 用户可填完整串，或只填 `claire` / `alex`，由本方法用 [ttsModel] 或 [voicePrefixModel] 补全前半段。
     *
     * @param voicePrefixModel 可选；非空时优先用作 `前缀:音色` 里的前缀（仍应与官方文档一致）。
     */
    internal fun resolveSpeechVoice(
        baseUrl: String,
        ttsModel: String,
        rawVoice: String,
        voicePrefixModel: String = ""
    ): String {
        val v = rawVoice.trim()
        if (DmxApiRouting.isDmxHost(baseUrl)) {
            return when (DmxApiRouting.classifyTtsProtocol(ttsModel)) {
                DmxApiRouting.TtsProtocol.RESPONSES_MINIMAX28,
                DmxApiRouting.TtsProtocol.OPENAI_SPEECH ->
                    if (DmxApiRouting.isMinimaxSpeech26(ttsModel)) {
                        v.ifBlank { DmxApiRouting.DEFAULT_MINIMAX_VOICE }
                    } else {
                        v.ifBlank { DmxApiRouting.DEFAULT_OPENAI_TTS_VOICE }
                    }
                DmxApiRouting.TtsProtocol.CHAT_MIMO ->
                    v.ifBlank { DmxApiRouting.DEFAULT_MIMO_VOICE }
                DmxApiRouting.TtsProtocol.GEMINI_GENERATE ->
                    v.ifBlank { DmxApiRouting.DEFAULT_GEMINI_TTS_VOICE }
            }
        }
        val isSf = baseUrl.contains("siliconflow.cn", ignoreCase = true)
        val speechModel = ttsModel.trim().ifEmpty { "FunAudioLLM/CosyVoice2-0.5B" }
        val prefix = voicePrefixModel.trim().ifEmpty { speechModel }
        if (!isSf) return v.ifEmpty { "alloy" }
        if (v.isEmpty()) return "$prefix:claire"
        if (v.contains(":") || v.startsWith("speech:")) return v
        return "$prefix:$v"
    }

    private fun authHeader(baseUrl: String, apiKey: String): String =
        if (DmxApiRouting.isDmxHost(baseUrl)) DmxApiRouting.formatTokenAuth(apiKey)
        else OpenAiCompatibleRouting.bearerAuth(apiKey)

    /**
     * 使用**全局**朗读 Key/Base 尝试播放（仅 [SecureStorage]）。
     *
     * **会话 / 角色级 TTS** 必须由调用方先通过 [com.mojing.app.domain.config.ApiKeyResolver.resolveTtsParams]
     * 或聊天内的等价解析，再调用 [speakHttpTts]；勿指望本方法继承世界或角色覆盖。
     * 当前工程内无调用点，保留为调试或极简场景的便捷入口。
     */
    suspend fun speakIfPossible(context: Context, storage: SecureStorage, text: String): Boolean =
        speakHttpTts(
            context = context,
            apiKey = storage.voiceApiKey,
            baseUrl = storage.voiceBaseUrl,
            model = storage.voiceModel,
            speechVoice = storage.voiceSpeechVoice,
            presetPrefixModel = storage.voicePresetPrefixModel,
            text = text,
        )

    /**
     * 使用已解析的 Key/Base/音色参数请求 TTS（OpenAI 兼容 `/v1/audio/speech`）。
     * [baseUrl] 可为网关根路径；空 Key 或空文本返回 false。
     */
    private suspend fun playAudioBytes(context: Context, bytes: ByteArray, ext: String): Boolean {
        val file = withContext(Dispatchers.IO) {
            java.io.File.createTempFile("http_tts_", ".$ext", context.cacheDir).also { it.writeBytes(bytes) }
        }
        var handedOff = false
        try {
            return withContext(Dispatchers.Main) {
                TtsPlayer.play(file, deleteWhenFinished = true).also { handedOff = it }
            }
        } finally {
            if (!handedOff) file.delete()
        }
    }

    suspend fun speakHttpTts(
        context: Context,
        apiKey: String,
        baseUrl: String,
        model: String,
        speechVoice: String,
        presetPrefixModel: String,
        text: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            lastFishAudioError = null
            if (model.trim().equals("system", ignoreCase = true)) return@withContext false
            if (OpenAiCompatibleRouting.isLocalBuiltinTts(baseUrl)) return@withContext false
            val key = apiKey.trim()
            val cleaned = TtsSpeakText.normalizeForSpeech(text)
            if (key.isEmpty() || cleaned.isBlank()) return@withContext false
            if (FishAudioRouting.shouldUseFishDirectApi(baseUrl, model)) {
                val (bytes, err) = FishAudioTts.synthesizeMp3(key, baseUrl, model, speechVoice, cleaned)
                if (bytes != null) return@withContext playAudioBytes(context, bytes, "mp3")
                lastFishAudioError = err
                return@withContext false
            }
            synthesizeDmxOrNull(key, baseUrl, model, speechVoice, cleaned)?.let { (bytes, ext) ->
                return@withContext playAudioBytes(context, bytes, ext)
            }
            val url = speechUrl(baseUrl)
            if (!url.startsWith("http")) return@withContext false
            val m = model.trim().ifEmpty { "tts-1" }
            val voice = resolveSpeechVoice(
                baseUrl,
                m,
                speechVoice,
                presetPrefixModel
            )
            val json = JSONObject().apply {
                put("model", m)
                put("input", cleaned)
                put("voice", voice)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader(baseUrl, key))
                .post(body)
                .build()
            val bytes = try {
                client.executeCancellable(req) { response ->
                    if (!response.isSuccessful) null else response.body?.bytes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext false
            }
            if (bytes == null) return@withContext false
            playAudioBytes(context, bytes, "mp3")
        }

    private suspend fun synthesizeDmxOrNull(
        apiKey: String,
        baseUrl: String,
        model: String,
        speechVoice: String,
        text: String,
    ): Pair<ByteArray, String>? {
        if (!DmxApiRouting.isDmxHost(baseUrl)) return null
        val voice = resolveSpeechVoice(baseUrl, model, speechVoice)
        return when (DmxApiRouting.classifyTtsProtocol(model)) {
            DmxApiRouting.TtsProtocol.RESPONSES_MINIMAX28 -> {
                val bytes = DmxResponsesApi.synthesizeMinimax28Mp3(apiKey, baseUrl, model, voice, text)
                    ?: return null
                bytes to "mp3"
            }
            DmxApiRouting.TtsProtocol.CHAT_MIMO -> {
                val bytes = DmxChatTtsApi.synthesizeMimoWav(apiKey, baseUrl, voice, text)
                    ?: return null
                bytes to "wav"
            }
            DmxApiRouting.TtsProtocol.GEMINI_GENERATE -> {
                val bytes = DmxGeminiApi.synthesizeWavBytes(apiKey, model, voice, text)
                    ?: return null
                bytes to "wav"
            }
            DmxApiRouting.TtsProtocol.OPENAI_SPEECH -> null
        }
    }

    /**
     * 请求 TTS 并写入临时 mp3，返回绝对路径；失败 return null。
     */
    suspend fun synthesizeToMp3File(
        context: Context,
        apiKey: String,
        baseUrl: String,
        model: String,
        speechVoice: String,
        presetPrefixModel: String,
        text: String,
    ): String? {
        var generatedFile: java.io.File? = null
        fun writeTtsBytes(bytes: ByteArray, extension: String): String {
            val dir = java.io.File(context.filesDir, "tts_cache").apply { mkdirs() }
            val file = java.io.File(dir, "tts_${System.currentTimeMillis()}.$extension")
            generatedFile = file
            file.writeBytes(bytes)
            return file.absolutePath
        }
        try {
            return withContext(Dispatchers.IO) {
                if (model.trim().equals("system", ignoreCase = true)) return@withContext null
                if (OpenAiCompatibleRouting.isLocalBuiltinTts(baseUrl)) return@withContext null
                val key = apiKey.trim()
                val cleaned = TtsSpeakText.normalizeForSpeech(text)
                if (key.isEmpty() || cleaned.isBlank()) return@withContext null
                if (FishAudioRouting.shouldUseFishDirectApi(baseUrl, model)) {
                    val (bytes, err) = FishAudioTts.synthesizeMp3(key, baseUrl, model, speechVoice, cleaned)
                    if (bytes == null) {
                        lastFishAudioError = err
                        return@withContext null
                    }
                    return@withContext writeTtsBytes(bytes, "mp3")
                }
                synthesizeDmxOrNull(key, baseUrl, model, speechVoice, cleaned)?.let { (bytes, ext) ->
                    return@withContext writeTtsBytes(bytes, ext)
                }
                val url = speechUrl(baseUrl)
                if (!url.startsWith("http")) return@withContext null
                val m = model.trim().ifEmpty { "tts-1" }
                val voice = resolveSpeechVoice(baseUrl, m, speechVoice, presetPrefixModel)
                val json = JSONObject().apply {
                    put("model", m)
                    put("input", cleaned)
                    put("voice", voice)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", authHeader(baseUrl, key))
                    .post(body)
                    .build()
                val bytes = try {
                    client.executeCancellable(req) { response ->
                        if (!response.isSuccessful) null else response.body?.bytes()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withContext null
                }
                if (bytes == null) return@withContext null
                writeTtsBytes(bytes, "mp3")
            }
        } catch (e: CancellationException) {
            generatedFile?.let { runCatching { it.delete() } }
            throw e
        }
    }
}
