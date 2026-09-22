package com.mojing.app.domain.config

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.ui.common.ApiBasePlaceholder
import com.mojing.app.util.ApiRootLines

/** 配图成功后是否将赢家根前置写回全局存储（见 `ChatViewModel.applyImageBasePromotion`）。 */
enum class ImageBasePromote {
    None,
    StorageImage,
    StoragePublic,
}

/**
 * 聊天内 AI 生图解析结果（Key / 多行原始 Base / 模型 / 置顶策略）。
 * 不混入对话 LLM 的 Key。
 */
data class ImageGenResolved(
    val apiKey: String,
    val baseUrlRaw: String,
    val model: String,
    val promote: ImageBasePromote,
)

/** HTTP TTS 线路解析结果（与 [com.mojing.app.media.HttpTts.speakHttpTts] 参数对齐）。 */
data class VoiceTtsParams(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val speechVoice: String,
    val presetPrefix: String,
)

/**
 * 会话 / 角色 / 设置多层 API 解析的统一入口。
 *
 * 聊天内配图与朗读解析已集中于此；[com.mojing.app.ui.chat.ChatViewModel] 负责副作用（写回存储、日志、仓库调用）。
 */
object ApiKeyResolver {

    fun isPlaceholderApiBase(url: String): Boolean =
        ApiBasePlaceholder.isPlaceholderApiBase(url)

    /** 多行地址中只要有一行非占位即视为已配置。 */
    fun isPlaceholderApiBaseField(raw: String): Boolean {
        val parts = ApiRootLines.split(raw)
        if (parts.isEmpty()) return isPlaceholderApiBase(raw.trim())
        return parts.all { isPlaceholderApiBase(it) }
    }

    /**
     * 聊天内 AI 生图 Key/Base/Model（与百科等一致）：
     * 角色开启配图且填了生图字段 → 本会话配图覆盖 → 全局配图 → 公共 Key/根地址。
     */
    fun resolveImageGenPrimaryResolved(
        char: CharacterEntity?,
        world: SessionWorldEntity?,
        storage: SecureStorage,
    ): ImageGenResolved {
        val w = world
        val ig = char?.takeIf { it.imageGenEnabled }

        val apiKey = when {
            ig != null && ig.imageGenApiKey.isNotBlank() -> ig.imageGenApiKey.trim()
            w?.sessionImageApiKey?.isNotBlank() == true -> w.sessionImageApiKey.trim()
            storage.imageApiKey.isNotBlank() -> storage.imageApiKey.trim()
            else -> storage.publicApiKey.trim()
        }

        val model = when {
            ig != null && ig.imageGenModel.isNotBlank() -> ig.imageGenModel.trim()
            w?.sessionImageModel?.isNotBlank() == true -> w.sessionImageModel.trim()
            storage.imageModel.isNotBlank() -> storage.imageModel.trim()
            else -> "dall-e-3"
        }.ifBlank { "dall-e-3" }

        return when {
            ig != null && ig.imageGenBaseUrl.isNotBlank() && !isPlaceholderApiBaseField(ig.imageGenBaseUrl) ->
                ImageGenResolved(apiKey, ig.imageGenBaseUrl.trim(), model, ImageBasePromote.None)
            w?.sessionImageBaseUrl?.isNotBlank() == true && !isPlaceholderApiBaseField(w.sessionImageBaseUrl) ->
                ImageGenResolved(apiKey, w.sessionImageBaseUrl.trim(), model, ImageBasePromote.None)
            !isPlaceholderApiBaseField(storage.imageBaseUrl) ->
                ImageGenResolved(apiKey, storage.imageBaseUrl.trim(), model, ImageBasePromote.StorageImage)
            else -> ImageGenResolved(
                apiKey,
                storage.publicBaseUrl.trim(),
                model,
                if (!isPlaceholderApiBaseField(storage.publicBaseUrl.trim())) ImageBasePromote.StoragePublic
                else ImageBasePromote.None,
            )
        }
    }

    /** 仅公共线路（用于专用失败后的重试与「是否已是公共」判断）。 */
    fun resolveImageGenStrictPublicResolved(storage: SecureStorage): ImageGenResolved {
        val key = storage.publicApiKey.trim()
        val pubRaw = storage.publicBaseUrl.trim()
        val imgRaw = storage.imageBaseUrl.trim()
        val model = storage.imageModel.trim().ifBlank { "dall-e-3" }
        return when {
            !isPlaceholderApiBaseField(pubRaw) -> ImageGenResolved(key, pubRaw, model, ImageBasePromote.StoragePublic)
            !isPlaceholderApiBaseField(imgRaw) -> ImageGenResolved(key, imgRaw, model, ImageBasePromote.StorageImage)
            else -> ImageGenResolved(key, pubRaw.ifBlank { imgRaw }, model, ImageBasePromote.None)
        }
    }

    /**
     * 朗读 HTTP 线路：角色 → 会话 → 全局 → 公共；Base 不混入占位 DeepSeek 默认根。
     */
    fun resolveTtsParams(
        char: CharacterEntity?,
        world: SessionWorldEntity?,
        storage: SecureStorage,
    ): VoiceTtsParams {
        val rawVoice = char?.voiceApiBaseUrl?.trim().orEmpty()
            .takeIf { !isPlaceholderApiBase(it) }
            ?: world?.sessionVoiceBaseUrl?.trim().orEmpty().takeIf { !isPlaceholderApiBase(it) }
            ?: ""
        val voiceBase = when {
            rawVoice.isNotBlank() -> rawVoice
            else -> storage.voiceBaseUrl.trim().takeIf { !isPlaceholderApiBase(it) }
                ?: storage.publicBaseUrl.trim()
        }
        val key = char?.voiceApiKey?.trim().orEmpty()
            .ifBlank { world?.sessionVoiceApiKey?.trim().orEmpty() }
            .ifBlank { storage.voiceApiKey.trim() }
            .ifBlank { storage.publicApiKey.trim() }
        var model = char?.voiceModel?.trim().orEmpty()
            .ifBlank { world?.sessionVoiceModel?.trim().orEmpty() }
            .ifBlank { storage.voiceModel.trim() }
            .ifEmpty { "system" }
        if (FishAudioRouting.shouldUseFishDirectApi(voiceBase, model) &&
            (model == "system" || model.equals("tts-1", ignoreCase = true))
        ) {
            model = FishAudioRouting.DEFAULT_MODEL
        }
        val speechV = world?.sessionVoiceSpeechVoice?.trim().orEmpty()
            .ifBlank { storage.voiceSpeechVoice.trim() }
        val preset = world?.sessionVoicePresetPrefixModel?.trim().orEmpty()
            .ifBlank { storage.voicePresetPrefixModel.trim() }
        return VoiceTtsParams(key, voiceBase, model, speechV, preset)
    }

    /** 无角色/世界上下文时：仅用全局 + 公共语音栏（不混入对话 LLM Key）。 */
    fun resolveTtsParamsPublicVoice(storage: SecureStorage): VoiceTtsParams {
        val key = storage.voiceApiKey.trim().ifBlank { storage.publicApiKey.trim() }
        val base = storage.voiceBaseUrl.trim().takeIf { !isPlaceholderApiBase(it) }
            ?: storage.publicBaseUrl.trim()
        var model = storage.voiceModel.trim().ifEmpty { "system" }
        if (FishAudioRouting.shouldUseFishDirectApi(base, model) &&
            (model == "system" || model.equals("tts-1", ignoreCase = true))
        ) {
            model = FishAudioRouting.DEFAULT_MODEL
        }
        val speechV = storage.voiceSpeechVoice.trim()
        val preset = storage.voicePresetPrefixModel.trim()
        return VoiceTtsParams(key, base, model, speechV, preset)
    }
}
