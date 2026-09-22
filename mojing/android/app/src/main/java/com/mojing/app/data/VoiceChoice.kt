package com.mojing.app.data

import android.content.Context

data class VoiceChoice(val engineId: String = "system", val voiceId: String = "") {
    fun label(): String = when (engineId) {
        "inherit" -> "跟随对话设置"
        "azure" -> "微软语音 · ${voiceId.ifBlank { "晓晓" }}"
        "system" -> "系统语音 · ${voiceId.ifBlank { "默认音色" }}"
        else -> "${engineId.removePrefix("android:")} · ${voiceId.ifBlank { "默认音色" }}"
    }
}

class VoicePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("voice_choices_v1", Context.MODE_PRIVATE)
    private val secure by lazy { SecureStorage().also { it.init(appContext) } }
    fun global(): VoiceChoice = read("global") ?: VoiceChoice()
    fun saveGlobal(choice: VoiceChoice) = write("global", choice)
    fun session(id: Long): VoiceChoice = read("session_$id") ?: global()
    fun saveSession(id: Long, choice: VoiceChoice) = write("session_$id", choice)
    val azureRegion: String get() = secure.azureSpeechRegion
    val azureKey: String get() = secure.azureSpeechKey
    fun saveAzure(region: String, key: String) = secure.saveAzureSpeech(region, key)
    private fun read(prefix: String): VoiceChoice? = prefs.getString("${prefix}_engine", null)?.let {
        VoiceChoice(it, prefs.getString("${prefix}_voice", "").orEmpty())
    }
    private fun write(prefix: String, choice: VoiceChoice) {
        check(prefs.edit().putString("${prefix}_engine", choice.engineId)
            .putString("${prefix}_voice", choice.voiceId).commit()) { "语音选择未保存，请重试" }
    }
}

fun resolveVoiceChoice(characterProvider: String?, characterVoice: String?, conversation: VoiceChoice): VoiceChoice =
    if (characterProvider == "azure" || characterProvider == "system" || characterProvider?.startsWith("android:") == true)
        VoiceChoice(characterProvider!!, characterVoice.orEmpty().takeUnless { it == "system" }.orEmpty())
    else conversation
