package com.mojing.app.ui.common

/**
 * 与 [com.mojing.app.ui.chat.ChatViewModel] 中「占位根」判定一致：
 * 空串或与公共 DeepSeek 默认根相同视为占位，应 fallback 到公共线路或下一层配置。
 */
object ApiBasePlaceholder {
    fun isPlaceholderApiBase(url: String): Boolean {
        val t = url.trim()
        return t.isEmpty() || t.equals("https://api.deepseek.com", ignoreCase = true)
    }
}
