package com.mojing.app.media

/**
 * Chooses a usable Chinese voice without depending on Android framework types.
 * Offline voices are preferred so synthesis does not fail when the engine's
 * network voice is unavailable. The caller still owns the selected engine.
 */
data class TtsVoiceInfo(
    val name: String,
    val languageTag: String,
    val requiresNetwork: Boolean,
    val quality: Int = 0,
    val latency: Int = 0,
    val installed: Boolean = true,
)

object TtsVoicePolicy {
    /** A broken engine may return one name for several locales; keep catalog and playback in the same order. */
    fun duplicateNameLanguageRank(languageTag: String): Int =
        if (languageTag.substringBefore('-').equals("zh", ignoreCase = true)) 0 else 1

    fun isRequestedVoiceActive(
        requestedName: String,
        requestedLanguageTag: String,
        activeName: String,
        activeLanguageTag: String,
    ): Boolean = requestedName == activeName &&
        requestedLanguageTag.substringBefore('-').equals(activeLanguageTag.substringBefore('-'), ignoreCase = true)

    fun chooseChineseVoice(voices: Collection<TtsVoiceInfo>): TtsVoiceInfo? =
        voices
            .asSequence()
            .filter { it.installed && !it.requiresNetwork }
            .filter { it.languageTag.substringBefore('-').equals("zh", ignoreCase = true) }
            .sortedWith(
                compareBy<TtsVoiceInfo> { it.requiresNetwork }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name },
            )
            .firstOrNull()
}

object TtsErrorPolicy {
    fun message(errorCode: Int): String = when (errorCode) {
        -3 -> "语音引擎合成失败。请在系统文字转语音设置中下载中文语音数据，或更换朗读引擎后重试。"
        -4 -> "语音引擎服务不可用，请检查系统文字转语音设置后重试。"
        -5 -> "语音引擎无法输出声音，请检查系统音量、音频输出和朗读引擎。"
        -6 -> "语音引擎网络不可用，请检查网络或改用已安装的离线中文语音。"
        -7 -> "语音引擎网络请求超时，请检查网络或改用已安装的离线中文语音。"
        -8 -> "语音引擎拒绝了本次朗读内容，请缩短文本后重试。"
        -9 -> "中文语音数据尚未安装完成，请在系统文字转语音设置中安装后重试。"
        else -> "系统朗读失败（错误码 $errorCode），请检查系统文字转语音设置后重试。"
    }
}
