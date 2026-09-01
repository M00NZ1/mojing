package com.mojing.app.data.local.entity

/**
 * 侧栏「世界」页提交的会话级 API 覆盖，写入 [SessionWorldEntity] 对应字段。
 * 解析链：角色卡 → 本会话字段 → 设置里的主/生图/语音 Key。
 */
data class SessionWorldCredentialDraft(
    val sessionLlmApiKey: String = "",
    val sessionLlmBaseUrl: String = "",
    val sessionImageApiKey: String = "",
    val sessionImageBaseUrl: String = "",
    val sessionImageModel: String = "",
    val sessionVoiceApiKey: String = "",
    val sessionVoiceBaseUrl: String = "",
    val sessionVoiceModel: String = "",
    val sessionVoiceSpeechVoice: String = "",
    val sessionVoicePresetPrefixModel: String = "",
)
