package com.mojing.app.util

/**
 * 用户可在「服务根地址」类字段中填写多行（或分号分隔）备选网关，按顺序尝试；
 * 成功后可将赢家行前置写回存储（见 [promoteLineToFront]）。
 */
object ApiRootLines {
    private val splitRegex = """[\r\n;]+""".toRegex()

    fun split(raw: String): List<String> =
        raw.split(splitRegex).map { it.trim() }.filter { it.isNotEmpty() }

    fun hasMultipleCandidates(raw: String): Boolean = split(raw).size > 1

    /**
     * 拆行后逐条 [normalize]，按出现顺序去重（忽略大小写），保证至少返回一条（整段 trim 后 normalize）。
     */
    fun splitToOrderedDistinct(
        raw: String,
        normalize: (String) -> String,
    ): List<String> {
        val parts = split(raw)
        val seq = if (parts.isNotEmpty()) parts else listOf(raw.trim()).filter { it.isNotEmpty() }
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (p in seq) {
            val n = normalize(p).trim()
            if (n.isEmpty()) continue
            val key = n.lowercase()
            if (seen.add(key)) out.add(n)
        }
        if (out.isEmpty()) {
            val n = normalize(raw).trim()
            if (n.isNotEmpty()) out.add(n)
        }
        return out
    }

    /**
     * 将「与 [winningNormalizedBase] 归一化后相同」的那一行移到最前，用换行拼接写回。
     * 若只有一行或未匹配则返回原串。
     */
    fun promoteLineToFront(
        rawMultiLine: String,
        winningNormalizedBase: String,
        lineNormalize: (String) -> String,
    ): String {
        val win = winningNormalizedBase.trim()
        if (win.isEmpty()) return rawMultiLine
        val lines = split(rawMultiLine)
        if (lines.size < 2) return rawMultiLine
        val idx = lines.indexOfFirst { lineNormalize(it).trim().equals(win, ignoreCase = true) }
        if (idx <= 0) return rawMultiLine
        val winner = lines[idx]
        val rest = lines.filterIndexed { i, _ -> i != idx }
        return (listOf(winner) + rest).joinToString("\n")
    }
}
