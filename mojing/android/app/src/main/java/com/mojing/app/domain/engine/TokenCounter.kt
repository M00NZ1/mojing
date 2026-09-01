package com.mojing.app.domain.engine

object TokenCounter {
    /** 会话 UI 估算：超长正文只扫描前缀再按比例放大，避免 O(全文) 卡顿（见 ChatViewModel 会话 token 条）。 */
    private const val UI_ESTIMATE_CHAR_CAP = 65_536

    // 简单估算：1个中文字符约等于1-2个token，1个英文字词约等于1个token
    // 这里简单地以长度估算，中文按2倍计算，英文按单词计算
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0
        for (char in text) {
            if (char.code > 127) {
                tokens += 2 // 中文/全角字符估算为 2 tokens
            } else {
                tokens += 1 // 简单起见，按字符算
            }
        }
        // 为了安全起见，通常还会加上一些缓冲
        return (tokens * 0.8).toInt()
    }

    fun estimateScaledPrefix(text: String, prefixChars: Int = UI_ESTIMATE_CHAR_CAP): Int {
        if (text.isEmpty()) return 0
        if (text.length <= prefixChars) return estimate(text)
        val prefix = text.substring(0, prefixChars)
        val est = estimate(prefix).coerceAtLeast(1)
        return ((est.toLong() * text.length) / prefixChars).toInt()
    }
}
