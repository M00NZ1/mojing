package com.mojing.app.data.remote

import com.mojing.app.domain.config.OpenAiCompatibleRouting
import java.net.URI
import java.util.Locale

/**
 * 根据用户手填的 OpenAI 兼容根地址，生成若干候选根（含 /v1 与不带 /v1、以及常见厂商官方根），
 * 供本机直连探测依次尝试。
 */
object OpenAiCompatibleBaseCandidates {

    private val PRESET_BY_HOST_PART: List<Pair<String, List<String>>> = listOf(
        "siliconflow" to listOf("https://api.siliconflow.cn/v1"),
        "deepseek.com" to listOf("https://api.deepseek.com"),
        "openai.com" to listOf("https://api.openai.com/v1"),
        "moonshot" to listOf("https://api.moonshot.cn/v1"),
        "bigmodel.cn" to listOf("https://open.bigmodel.cn/api/paas/v4"),
        "dashscope" to listOf("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        "hunyuan" to listOf("https://api.hunyuan.cloud.tencent.com/v1"),
        "baidubce.com" to listOf("https://qianfan.baidubce.com/v2"),
        "volces.com" to listOf("https://ark.cn-beijing.volces.com/api/v3"),
        "dmxapi.cn" to listOf("https://www.dmxapi.cn/v1"),
        "anthropic.com" to listOf("https://api.anthropic.com"),
        "generativelanguage.googleapis.com" to listOf("https://generativelanguage.googleapis.com/v1beta/openai"),
    )

    fun collect(userInput: String, normalize: (String) -> String): List<String> {
        val trimmed = userInput.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        fun addRaw(raw: String) {
            val n = normalize(raw).trim().trimEnd('/')
            if (n.isNotEmpty()) out.add(n)
        }
        addRaw(trimmed)
        if (OpenAiCompatibleRouting.isVolcArkHost(trimmed) ||
            OpenAiCompatibleRouting.usesVersionedRoot(trimmed) &&
            !OpenAiCompatibleRouting.normalizeBase(trimmed).lowercase().endsWith("/v1")
        ) {
            OpenAiCompatibleRouting.collectProbeBases(trimmed, normalize).forEach { out.add(it) }
        } else {
            val once = normalize(trimmed).trim().trimEnd('/')
            if (once.isNotEmpty()) {
                if (once.endsWith("/v1", ignoreCase = true)) {
                    out.add(once.dropLast(3).trimEnd('/'))
                } else {
                    out.add("$once/v1")
                }
            }
        }
        val host = hostKey(trimmed)
        for ((frag, bases) in PRESET_BY_HOST_PART) {
            if (frag in host) bases.forEach { addRaw(it) }
        }
        return out.toList()
    }

    private fun hostKey(url: String): String =
        runCatching { URI(url).host?.lowercase(Locale.US) ?: "" }.getOrDefault("")
}
