package com.mojing.app.ui.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle

data class ChatReadingStyle(val font: String = "system", val narratorItalic: Boolean = false) {
    val fontFamily: FontFamily
        get() = when (font) {
            "sans" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            "mono" -> FontFamily.Monospace
            else -> FontFamily.Default
        }
    val narratorFontStyle: FontStyle
        get() = if (narratorItalic) FontStyle.Italic else FontStyle.Normal
}

val LocalChatReadingStyle = compositionLocalOf { ChatReadingStyle() }
