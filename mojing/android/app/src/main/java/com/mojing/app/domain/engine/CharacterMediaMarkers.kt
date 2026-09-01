package com.mojing.app.domain.engine

/**
 * 角色回复中的「自动配图 / 自动语音」扩展标签（仅当会话世界打开对应开关时由客户端执行）。
 * 与展示用 `<SPEECH>` 分离：自动 TTS 使用 `<GEN_SPEECH>`，避免误触发。
 *
 * 格式（须大写、成对）：`<GEN_IMAGE>绘图提示一句</GEN_IMAGE>`、`<GEN_SPEECH>要合成的短句</GEN_SPEECH>`。
 * 解析后标签会从展示正文中剔除；执行侧：配图取首个 GEN_IMAGE，语音取前两个 GEN_SPEECH（与自动任务实现一致）。
 */
object CharacterMediaMarkers {

    private val genImageRegex = Regex("<GEN_IMAGE>(.*?)</GEN_IMAGE>", RegexOption.DOT_MATCHES_ALL)
    private val genSpeechRegex = Regex("<GEN_SPEECH>(.*?)</GEN_SPEECH>", RegexOption.DOT_MATCHES_ALL)

    data class Parsed(
        val displayText: String,
        val imagePrompts: List<String>,
        val speechTexts: List<String>,
    )

    fun parse(raw: String): Parsed {
        val images = genImageRegex.findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val speeches = genSpeechRegex.findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        var display = raw
        display = genImageRegex.replace(display, "")
        display = genSpeechRegex.replace(display, "")
        display = display.trim()
        return Parsed(display, images, speeches)
    }
}
