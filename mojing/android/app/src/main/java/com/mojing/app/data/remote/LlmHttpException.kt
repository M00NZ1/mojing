package com.mojing.app.data.remote

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Provider text never becomes a loggable exception message. */
class LlmHttpException(
    val status: Int,
    requestId: String? = null,
    errorCode: String? = null,
    retryAfterMs: Long? = null,
) : IOException("LLM HTTP $status") {
    val requestId = requestId?.takeIf { it.length <= 96 && it.matches(Regex("[A-Za-z0-9._:-]+")) }
    val errorCode = errorCode?.takeIf { it in SAFE_ERROR_CODES }
    val retryAfterMs = retryAfterMs?.coerceIn(0L, 30_000L)

    private companion object {
        val SAFE_ERROR_CODES = setOf(
            "invalid_request_error", "authentication_error", "permission_error", "not_found_error",
            "rate_limit_error", "api_error", "overloaded_error", "model_not_found",
            "invalid_api_key", "insufficient_quota", "invalid_parameter",
        )
    }
}

class LlmProtocolException(val reason: String) : Exception("Incomplete model response")

internal fun parseRetryAfterMs(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim() ?: return null
    text.toLongOrNull()?.let { return if (it < 0) null else it.coerceAtMost(30L) * 1000L }
    return runCatching {
        (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs)
            .coerceIn(0L, 30_000L)
    }.getOrNull()
}
