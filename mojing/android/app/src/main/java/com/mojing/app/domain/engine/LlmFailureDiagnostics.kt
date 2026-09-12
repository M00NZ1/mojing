package com.mojing.app.domain.engine

import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.LlmProtocolException

object LlmFailureDiagnostics {
    fun summary(error: Throwable): String = buildString {
        append("type=").append(error::class.simpleName ?: "Unknown")
        (error as? LlmHttpException)?.let {
            append(" status=").append(it.status)
            it.errorCode?.takeIf { code -> code in SAFE_CODES }?.let { code -> append(" code=").append(code) }
        }
        (error as? LlmProtocolException)?.reason?.takeIf { it in SAFE_REASONS }?.let {
            append(" reason=").append(it)
        }
    }

    private val SAFE_CODES = setOf("invalid_request_error", "authentication_error", "permission_error", "not_found_error", "rate_limit_error", "api_error", "overloaded_error")
    private val SAFE_REASONS = setOf("output_limit", "incomplete_output", "empty_response", "unsupported_stream", "invalid_stream", "provider_stream_error")
}
