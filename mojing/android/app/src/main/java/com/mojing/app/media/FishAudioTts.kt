package com.mojing.app.media

import com.mojing.app.domain.config.FishAudioRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import com.mojing.app.data.remote.executeCancellable
import com.mojing.app.util.UsbSessionLog
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fish Audio 官方 TTS：`POST https://api.fish.audio/v1/tts`
 * - Header: `Authorization: Bearer <api_key>`（fish.audio 控制台专用 Key）
 * - Header: `model: s1 | s2-pro`（必填，推荐 s2-pro）
 * - Body JSON: text, format, reference_id, …
 */
object FishAudioTts {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun synthesizeMp3(
        apiKey: String,
        baseUrl: String,
        model: String,
        referenceId: String,
        text: String,
    ): Pair<ByteArray?, String?> {
        val cleaned = TtsSpeakText.normalizeForSpeech(text)
        if (cleaned.isBlank()) return null to "Fish Audio：朗读文本为空"
        val key = apiKey.trim().removePrefix("Bearer ").trim()
        if (key.isEmpty()) {
            return null to "Fish Audio：请填写 fish.audio 控制台 API Key（设置 → 语音 API Key，与对话 Key 分开）"
        }

        val headerModel = FishAudioRouting.resolveTtsModelHeader(model)
        val ref = FishAudioRouting.resolveReferenceId(referenceId)
        if (referenceId.trim().isNotEmpty() && ref == null) {
            UsbSessionLog.w(
                "FishTts",
                "reference_id 格式无效（需 fish.audio 音色模型 ID，32 位十六进制），已忽略: ${referenceId.take(40)}",
            )
        }

        val url = FishAudioRouting.resolveTtsUrl(baseUrl)
        val body = JSONObject().apply {
            put("text", cleaned)
            put("format", "mp3")
            put("mp3_bitrate", 128)
            put("latency", "balanced")
            put("normalize", true)
            put("temperature", 0.7)
            put("top_p", 0.7)
            ref?.let { put("reference_id", it) }
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", OpenAiCompatibleRouting.bearerAuth(key))
            .header("Content-Type", "application/json")
            .header("model", headerModel)
            .post(body.toString().toRequestBody(JSON))
            .build()

        return try {
            client.executeCancellable(req) { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(400).orEmpty()
                    UsbSessionLog.w("FishTts", "HTTP ${response.code} url=$url model=$headerModel $errBody")
                    null to formatError(response.code, errBody, headerModel, ref != null)
                } else {
                    val bytes = response.body?.bytes()?.takeIf { it.isNotEmpty() }
                    if (bytes == null) null to "Fish Audio 返回空音频" else bytes to null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to "Fish Audio 网络异常：${e.message ?: "unknown"}"
        }
    }

    private fun formatError(code: Int, errBody: String, model: String, hasRef: Boolean): String {
        val low = errBody.lowercase()
        return when {
            code == 401 || "auth" in low || "unauthorized" in low ->
                buildString {
                    append("Fish Audio 鉴权失败 (HTTP 401)。")
                    append("请确认：① API Key 来自 https://fish.audio/app/api-keys ；")
                    append("② 语音 Base 为 https://api.fish.audio/v1（勿用对话聚合网关）；")
                    append("③ 模型 Header 为 s2-pro（当前 $model）。")
                    if (errBody.isNotBlank()) append(" 响应：").append(errBody.take(120))
                }
            code == 402 || "payment" in low || "quota" in low ->
                "Fish Audio 余额不足或配额用尽 (HTTP 402)。请在 fish.audio 控制台充值。"
            code == 422 ->
                "Fish Audio 参数错误 (HTTP 422)${if (errBody.isNotEmpty()) "：$errBody" else ""}。" +
                    if (!hasRef) " 可在「TTS 音色」填写 reference_id（音色模型 ID）。" else ""
            else ->
                "Fish Audio HTTP $code${if (errBody.isNotEmpty()) "：$errBody" else ""}"
        }
    }
}
