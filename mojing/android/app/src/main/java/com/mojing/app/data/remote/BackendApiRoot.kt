package com.mojing.app.data.remote

import com.mojing.app.data.SecureStorage
import com.mojing.app.util.ApiRootLines
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 与 [BackendWorldsApi] 一致：可访问墨境 FastAPI 时拼出 `/api/...` 前缀。
 *
 * 若用户把「对话 / 配图」用的 OpenAI 兼容公网网关填在同一栏（常见以 `/v1` 结尾），
 * 则**不能**再拼 `/api`，否则会变成 `…/v1/api/...` 导致探测等接口 404。
 */
internal fun resolveBackendApiRoot(secureStorage: SecureStorage): String {
    val firstLine = ApiRootLines.split(secureStorage.publicBaseUrl).firstOrNull()?.trim()?.trimEnd('/')
        ?: secureStorage.publicBaseUrl.trim().trimEnd('/')
    var u = firstLine
    if (u.isEmpty()) return ""
    val lower = u.lowercase(Locale.US)
    // 典型上游网关根（非墨境后端）
    if (lower.endsWith("/v1") ||
        lower.contains("/compatible-mode/") ||
        lower.contains("/paas/v") ||
        lower.contains("/v1beta/")
    ) {
        return ""
    }
    if (u.endsWith("/api")) return u
    if (!lower.contains("/api")) u = "$u/api"
    return u
}

/**
 * 后端 JSON 里若只返回 `/characters/...` 等相对路径，OkHttp 无法直接请求；用设置里的关联根地址拼成绝对 URL。
 */
internal fun resolveMediaUrlAgainstPublicBase(publicBaseUrl: String, urlOrPath: String): String {
    val v = urlOrPath.trim()
    if (v.isEmpty()) return v
    if (v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)) return v
    val baseLine = ApiRootLines.split(publicBaseUrl).firstOrNull()?.trim()?.trimEnd('/')
        ?: publicBaseUrl.trim().trimEnd('/')
    val base = baseLine
    if (base.isEmpty()) return v
    val http = base.toHttpUrlOrNull() ?: return v
    return http.resolve(v)?.toString() ?: v
}
