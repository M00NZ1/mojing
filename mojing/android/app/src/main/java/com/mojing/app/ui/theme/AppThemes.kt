package com.mojing.app.ui.theme

/** 应用内可选主题 id（与 [SecureStorage.themeMode] 持久化一致）。 */
object AppThemes {
    val ORDER: List<String> = listOf(
        "dark",
        "light",
        "midnight",
        "rose",
        "ocean",
        "mint",
        "blush",
        "sky",
    )

    fun isKnown(id: String): Boolean = id in ORDER

    fun normalize(id: String): String = if (isKnown(id)) id else "dark"

    fun label(id: String): String = when (normalize(id)) {
        "dark" -> "深色"
        "light" -> "浅色"
        "midnight" -> "午夜"
        "rose" -> "玫瑰"
        "ocean" -> "深海"
        "mint" -> "薄荷"
        "blush" -> "柔粉"
        "sky" -> "晴空"
        else -> "深色"
    }
}
