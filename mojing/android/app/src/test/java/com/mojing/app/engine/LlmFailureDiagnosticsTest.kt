package com.mojing.app.engine

import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.domain.engine.LlmFailureDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmFailureDiagnosticsTest {
    @Test
    fun summaryContainsOnlySafeFailureMetadata() {
        val result = LlmFailureDiagnostics.summary(
            LlmHttpException(429, "req-1", "rate_limit_error")
        )
        assertTrue(result.contains("status=429"))
        assertTrue(result.contains("rate_limit_error"))
        assertFalse(result.contains("api-key"))
        assertFalse(result.contains("story text"))
    }
}
