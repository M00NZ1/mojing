package com.mojing.app.media

import android.content.Context
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/** Small Azure Speech REST adapter. Audio is played locally and is never persisted. */
object AzureSpeech {
    private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
    private const val MAX_SSML_TEXT = 2_400
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

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
            throw IllegalStateException("无法读取 Azure 音色列表，请检查区域和 Key", e)
        }
        try {
            parseVoices(body)
        } catch (e: Exception) {
            throw IllegalStateException("Azure 音色列表格式无效", e)
        }
    }

    suspend fun speak(
        context: Context,
        text: String,
        region: String,
        key: String,
        voiceId: String,
    ): Boolean {
        val normalizedRegion = normalizeRegion(region) ?: return false
        val apiKey = key.trim()
        val cleaned = TtsSpeakText.normalizeForSpeech(text)
        if (apiKey.isEmpty() || cleaned.isBlank()) return false
        val voice = voiceId.trim().ifBlank { DEFAULT_VOICE }
        val chunks = SpeechChunks.split(cleaned, MAX_SSML_TEXT)
        if (chunks.isEmpty()) return false
        return try {
            for (chunk in chunks) {
                val bytes = synthesize(normalizedRegion, apiKey, voice, chunk) ?: return false
                var file: java.io.File? = null
                try {
                    withContext(Dispatchers.IO) {
                        file = java.io.File.createTempFile("azure_tts_", ".mp3", context.cacheDir)
                        file!!.writeBytes(bytes)
                    }
                    if (!playFile(file!!)) return false
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun playFile(file: java.io.File): Boolean {
        var handle: TtsPlayer.PlaybackHandle? = null
        var handedOff = false
        try {
            withContext(Dispatchers.Main) {
                handle = TtsPlayer.playOwned(file, deleteWhenFinished = true)
            }
            if (handle == null) return false
            handedOff = true
            return TtsPlayer.awaitCompletion(handle!!)
        } catch (e: CancellationException) {
            handle?.let { owned ->
                withContext(NonCancellable + Dispatchers.Main) { TtsPlayer.stop(owned) }
            }
            throw e
        } catch (_: Exception) {
            handle?.let { owned ->
                withContext(Dispatchers.Main) { TtsPlayer.stop(owned) }
            }
            return false
        } finally {
            if (!handedOff) file.delete()
        }
    }

    private suspend fun synthesize(region: String, key: String, voice: String, text: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                .header("Ocp-Apim-Subscription-Key", key)
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", "audio-24khz-160kbitrate-mono-mp3")
                .header("User-Agent", "MoJing/1.0")
                .post(buildSsml(voice, text).toRequestBody("application/ssml+xml; charset=utf-8".toMediaType()))
                .build()
            try {
                client.executeCancellable(request) { response ->
                    if (!response.isSuccessful) null else response.body?.bytes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
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
                add(VoiceOption(id, label.ifBlank { id }))
            }
        }.distinctBy { it.id }
            .sortedWith(compareByDescending<VoiceOption> { it.id.startsWith("zh-", ignoreCase = true) }.thenBy { it.name })
    }
}
