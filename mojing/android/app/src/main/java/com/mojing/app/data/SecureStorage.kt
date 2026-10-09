package com.mojing.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SecureStorage {
    private var prefs: SharedPreferences? = null

    var generationQueuePaused: Boolean
        get() = prefs?.getBoolean("generation_queue_paused", false) ?: false
        set(value) { check(prefs?.edit()?.putBoolean("generation_queue_paused", value)?.commit() == true) { "暂停状态未保存" } }

    fun modelPlatforms(): List<ModelPlatform> {
        val raw = prefs?.getString("model_platforms_v1", null)
        if (raw != null) return ModelPlatformCodec.decode(raw)
        // Read-only legacy projection; the original fields stay intact until a successful save.
        return listOf(ModelPlatform("legacy", "原有平台", publicBaseUrl, publicApiKey,
            listOf(publicModel).filter(String::isNotBlank), publicModel))
    }

    fun activeModelPlatformId(): String = prefs?.getString("active_model_platform", "legacy") ?: "legacy"

    @Synchronized
    fun saveModelPlatform(platform: ModelPlatform, makeDefault: Boolean = true) {
        val storage = checkNotNull(prefs)
        val all = modelPlatforms().toMutableList()
        val index = all.indexOfFirst { it.id == platform.id }
        if (index < 0) all.add(platform) else all[index] = platform
        val touchedKeys = buildList {
            add("model_platforms_v1")
            if (makeDefault) {
                add("active_model_platform")
                add("public_api_key")
                add("public_base_url")
                add("public_model")
            }
        }
        val oldValues = touchedKeys.associateWith { storage.getString(it, null) }
        val editor = storage.edit().putString("model_platforms_v1", ModelPlatformCodec.encode(all))
        if (makeDefault) editor.putString("active_model_platform", platform.id)
            .putString("public_api_key", platform.apiKey).putString("public_base_url", platform.baseUrl)
            .putString("public_model", platform.selectedModel)
        try {
            if (editor.commit()) return
        } catch (_: Exception) {
            // Some SharedPreferences implementations can mutate their in-memory map before
            // reporting an exception; the same restoration path below still applies.
        }
        restorePlatformValues(storage, oldValues, "平台保存失败")
    }

    @Synchronized
    fun deleteModelPlatform(platformId: String): List<ModelPlatform> {
        val storage = checkNotNull(prefs)
        val all = modelPlatforms()
        val target = all.firstOrNull { it.id == platformId }
            ?: throw IllegalArgumentException("平台不存在")
        val wasDefault = activeModelPlatformId() == target.id
        val remaining = all.filterNot { it.id == target.id }
        val touchedKeys = buildList {
            add("model_platforms_v1")
            if (wasDefault) {
                add("active_model_platform")
                add("public_api_key")
                add("public_base_url")
                add("public_model")
            }
        }
        val oldValues = touchedKeys.associateWith { storage.getString(it, null) }
        val editor = storage.edit().putString(
            "model_platforms_v1", ModelPlatformCodec.encode(remaining),
        )
        if (wasDefault) {
            editor.putString("active_model_platform", "")
                .putString("public_api_key", "")
                .putString("public_base_url", "")
                .putString("public_model", "")
        }
        try {
            if (editor.commit()) return remaining
        } catch (_: Exception) {
            // Some SharedPreferences implementations can mutate their in-memory map before
            // reporting an exception; the same restoration path below still applies.
        }

        // SharedPreferences.commit(false) may still update the in-memory map. Restore only
        // the keys touched above before surfacing the failure for an in-place retry.
        restorePlatformValues(storage, oldValues, "平台删除失败")
    }

    private fun restorePlatformValues(
        storage: SharedPreferences,
        oldValues: Map<String, String?>,
        failureMessage: String,
    ): Nothing {
        try {
            val restore = storage.edit()
            oldValues.forEach { (key, value) ->
                if (value == null) restore.remove(key) else restore.putString(key, value)
            }
            check(restore.commit()) { "$failureMessage，且原配置恢复失败，请重启后核对" }
        } catch (restoreFailure: Exception) {
            throw IllegalStateException("$failureMessage，且原配置恢复失败，请重启后核对", restoreFailure)
        }
        throw IllegalStateException("$failureMessage，请重试")
    }

    fun sessionModelSelection(sessionId: Long): Pair<String, String>? {
        val id = prefs?.getString("chat_platform_$sessionId", null) ?: return null
        return id to prefs?.getString("chat_model_$sessionId", "").orEmpty()
    }

    fun selectSessionModel(sessionId: Long, platformId: String, model: String) {
        val platform = modelPlatforms().first { it.id == platformId }
        require(model in platform.models && platform.apiKey.isNotBlank()) { "请先完善平台 Key 与模型" }
        check(checkNotNull(prefs).edit().putString("chat_platform_$sessionId", platformId)
            .putString("chat_model_$sessionId", model).commit()) { "模型选择保存失败" }
    }

    fun clearSessionModelSelection(sessionId: Long) {
        check(checkNotNull(prefs).edit().remove("chat_platform_$sessionId")
            .remove("chat_model_$sessionId").commit()) { "模型选择保存失败" }
    }

    fun init(applicationContext: Context) {
        if (prefs != null) return
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        prefs = EncryptedSharedPreferences.create(
            "mojing_secure_prefs",
            masterKeyAlias,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** 本机加密存储的默认对话 API Key（设备主密钥）；角色卡与会话「世界」里可覆盖。 */
    var publicApiKey: String
        get() = prefs?.getString("public_api_key", "") ?: ""
        set(value) { prefs?.edit()?.putString("public_api_key", value)?.apply() }

    var publicBaseUrl: String
        get() = prefs?.getString("public_base_url", "https://api.deepseek.com") ?: "https://api.deepseek.com"
        set(value) { prefs?.edit()?.putString("public_base_url", value)?.apply() }

    var publicModel: String
        get() = prefs?.getString("public_model", "deepseek-chat") ?: "deepseek-chat"
        set(value) { prefs?.edit()?.putString("public_model", value)?.apply() }

    var themeMode: String
        get() = prefs?.getString("theme_mode", "dark") ?: "dark"
        set(value) { prefs?.edit()?.putString("theme_mode", value)?.apply() }

    /** 应用内字体缩放（叠在系统字体缩放之上），约 0.85～1.35，默认 1.0。 */
    var uiFontScale: Float
        get() = prefs?.getFloat("ui_font_scale", 1f) ?: 1f
        set(value) { prefs?.edit()?.putFloat("ui_font_scale", value.coerceIn(0.8f, 1.45f))?.apply() }

    // User Profile
    fun saveUserProfile(name: String, description: String, color: String) {
        check(checkNotNull(prefs).edit().putString("user_name", name)
            .putString("user_description", description).putString("user_avatar_color", color).commit()) {
            "资料保存失败"
        }
    }

    var userName: String
        get() = prefs?.getString("user_name", "玩家") ?: "玩家"
        set(value) { prefs?.edit()?.putString("user_name", value)?.apply() }

    var userDescription: String
        get() = prefs?.getString("user_description", "") ?: ""
        set(value) { prefs?.edit()?.putString("user_description", value)?.apply() }

    var userAvatarColor: String
        get() = prefs?.getString("user_avatar_color", "#53C7A8") ?: "#53C7A8"
        set(value) { prefs?.edit()?.putString("user_avatar_color", value)?.apply() }

    /** 本地个人头像文件绝对路径（filesDir 下由选图写入），空表示使用颜色占位 */
    var userAvatarImagePath: String
        get() = prefs?.getString("user_avatar_image_path", "") ?: ""
        set(value) { prefs?.edit()?.putString("user_avatar_image_path", value)?.apply() }

    // Defaults
    var defaultTemperature: String
        get() = prefs?.getString("default_temperature", "0.9") ?: "0.9"
        set(value) { prefs?.edit()?.putString("default_temperature", value)?.apply() }

    var defaultMaxTokens: String
        get() = prefs?.getString("default_max_tokens", "1200") ?: "1200"
        set(value) { prefs?.edit()?.putString("default_max_tokens", value)?.apply() }

    var defaultTopP: String
        get() = prefs?.getString("default_top_p", "1.0") ?: "1.0"
        set(value) { prefs?.edit()?.putString("default_top_p", value)?.apply() }

    /** 与 Web `LocalConfig.default_world_template_id` 对齐：无模板选中时新建会话使用的模板 id */
    var defaultWorldTemplateId: String
        get() = prefs?.getString("default_world_template_id", "custom") ?: "custom"
        set(value) { prefs?.edit()?.putString("default_world_template_id", value)?.apply() }

    /** 新建会话默认旁白开关 */
    var defaultNarratorEnabled: Boolean
        get() = prefs?.getBoolean("default_narrator_enabled", false) ?: false
        set(value) { prefs?.edit()?.putBoolean("default_narrator_enabled", value)?.apply() }

    /** 新建会话默认选项生成 */
    var defaultChoiceGenerationEnabled: Boolean
        get() = prefs?.getBoolean("default_choice_generation_enabled", true) ?: true
        set(value) { prefs?.edit()?.putBoolean("default_choice_generation_enabled", value)?.apply() }

    /** 新建会话默认防越权 */
    var defaultAntiCheatEnabled: Boolean
        get() = prefs?.getBoolean("default_anti_cheat_enabled", true) ?: true
        set(value) { prefs?.edit()?.putBoolean("default_anti_cheat_enabled", value)?.apply() }

    /** 与 Web `memory_compact_threshold`：当前故事线累计新增该数量的剧情消息后尝试触发摘要。 */
    var memoryCompactThreshold: Int
        get() = prefs?.getInt("memory_compact_threshold", 120)?.coerceIn(10, 2000) ?: 120
        set(value) { prefs?.edit()?.putInt("memory_compact_threshold", value.coerceIn(10, 2000))?.apply() }

    /** 是否启用通用高密度剧情记忆（UCM）。关闭后不注入、不更新。 */
    var universalContextMemoryEnabled: Boolean
        get() = prefs?.getBoolean("universal_context_memory_enabled", true) ?: true
        set(value) { prefs?.edit()?.putBoolean("universal_context_memory_enabled", value)?.apply() }

    /** 与 Web `max_upload_mb`：单张聊天附件图片上限 */
    var maxUploadMb: Int
        get() = prefs?.getInt("max_upload_mb", 20) ?: 20
        set(value) { prefs?.edit()?.putInt("max_upload_mb", value.coerceIn(1, 200))?.apply() }

    /**
     * 与 Web `max_auto_speakers`：每轮自动发言人数上限（与后端 LocalConfig 对齐；聊天页由 `SpeakerScheduler` / `ChatViewModel` 读取）。
     */
    var maxAutoSpeakers: Int
        get() = prefs?.getInt("max_auto_speakers", 2) ?: 2
        set(value) { prefs?.edit()?.putInt("max_auto_speakers", value.coerceIn(1, 8))?.apply() }

    /** `auto`：按发言率自动选人；`manual`：仅用户点名的角色回复 */
    var speakerTurnMode: String
        get() = prefs?.getString("speaker_turn_mode", "auto") ?: "auto"
        set(value) {
            val v = if (value == "manual") "manual" else "auto"
            prefs?.edit()?.putString("speaker_turn_mode", v)?.apply()
        }

    // Image API
    var imageApiKey: String
        get() = prefs?.getString("image_api_key", "") ?: ""
        set(value) { prefs?.edit()?.putString("image_api_key", value)?.apply() }

    var imageBaseUrl: String
        get() = prefs?.getString("image_base_url", "") ?: ""
        set(value) { prefs?.edit()?.putString("image_base_url", value)?.apply() }

    var imageModel: String
        get() = prefs?.getString("image_model", "dall-e-3") ?: "dall-e-3"
        set(value) { prefs?.edit()?.putString("image_model", value)?.apply() }

    // Voice API
    val azureSpeechRegion: String
        get() = prefs?.getString("azure_speech_region", "eastasia") ?: "eastasia"

    fun saveAzureSpeech(region: String, key: String) {
        require(region.trim().matches(Regex("[a-zA-Z0-9-]+"))) { "请填写有效的微软语音区域" }
        check(checkNotNull(prefs).edit().putString("azure_speech_region", region.trim())
            .putString("azure_speech_key", key.trim()).commit()) { "微软语音配置未保存" }
    }

    var azureSpeechKey: String
        get() = prefs?.getString("azure_speech_key", "").orEmpty()
        set(value) { check(checkNotNull(prefs).edit().putString("azure_speech_key", value).commit()) { "微软语音 Key 未保存" } }

    var voiceApiKey: String
        get() = prefs?.getString("voice_api_key", "") ?: ""
        set(value) { prefs?.edit()?.putString("voice_api_key", value)?.apply() }

    var voiceBaseUrl: String
        get() = prefs?.getString("voice_base_url", "") ?: ""
        set(value) { prefs?.edit()?.putString("voice_base_url", value)?.apply() }

    var voiceModel: String
        get() = prefs?.getString("voice_model", "system") ?: "system"
        set(value) { prefs?.edit()?.putString("voice_model", value)?.apply() }

    /** OpenAI：`alloy` 等；硅基流动常用预设：`anna`、`alex` 等（见官方 TTS 文档） */
    var voiceSpeechVoice: String
        get() = prefs?.getString("voice_speech_voice", "") ?: ""
        set(value) { prefs?.edit()?.putString("voice_speech_voice", value)?.apply() }

    /**
     * 硅基等：`voice` 参数常为 `模型id:预置音色`。默认用「语音模型」作前缀；
     * 若与合成所用 `model` 字段不一致，可在此填写用于拼前缀的模型 id（非必填）。
     */
    var voicePresetPrefixModel: String
        get() = prefs?.getString("voice_preset_prefix_model", "") ?: ""
        set(value) { prefs?.edit()?.putString("voice_preset_prefix_model", value)?.apply() }

    /** 与 Web「设置 → 公共 API」一致：允许在对话侧栏开启本会话思考/Max */
    var allowSessionThinkMax: Boolean
        get() = prefs?.getBoolean("allow_session_think_max", false) ?: false
        set(value) { prefs?.edit()?.putBoolean("allow_session_think_max", value)?.apply() }

    /** 思考/Max 时可选模型 id；留空则与主对话模型相同。应用不会根据开关自动改写模型名。 */
    var thinkMaxModel: String
        get() = prefs?.getString("think_max_model", "") ?: ""
        set(value) { prefs?.edit()?.putString("think_max_model", value)?.apply() }

    /**
     * 人设排队补全 / 模板世界书队列注入百科节选时的来源库 Room `id`；0 表示关闭。
     * 在「设置 → 默认设置」中填写，与百科列表中的库 id 一致。
     */
    var defaultEncyclopediaIdForAi: Long
        get() = prefs?.getLong("default_encyclopedia_id_for_ai", 0L) ?: 0L
        set(value) { prefs?.edit()?.putLong("default_encyclopedia_id_for_ai", value)?.apply() }
}
