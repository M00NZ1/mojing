package com.mojing.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingStringsTest {
    @Test
    fun localFailuresDoNotLeakInternalDetailsOrSuggestPermissions() {
        val message = UserFacingStrings.localSaveFailed("消息")

        assertEquals("消息未能保存到本机，请重试。", message)
        assertFalse(message.contains("网络"))
        assertFalse(message.contains("权限"))
    }

    @Test
    fun remoteFailuresKeepActionableConnectionHint() {
        val message = UserFacingStrings.remoteRequestFailed(IllegalStateException("Unable to resolve host"))

        assertTrue(message.contains("无法解析服务器地址"))
        assertTrue(message.contains("检查网络"))
    }
    @Test
    fun streamHttpFailuresUseSameHintsAsTypedFailures() {
        for (status in listOf(400, 401, 402, 403, 404, 422, 429, 500, 503)) {
            val stream = UserFacingStrings.streamErrorDetail("LlmHttpException | LLM HTTP $status | io")
            val typed = UserFacingStrings.remoteRequestFailed(com.mojing.app.data.remote.LlmHttpException(status))
            assertEquals("请求失败：$stream", typed)
            assertTrue(stream.contains("HTTP $status"))
            assertFalse(stream.contains("LlmHttpException"))
            assertFalse(stream.contains("| io"))
        }
    }

    @Test
    fun thinkingRoutePreservesAuthenticationAndNetworkCauses() {
        for (error in listOf("LLM HTTP 401", "LLM HTTP 429", "SocketTimeoutException")) {
            assertEquals("思考/Max：${UserFacingStrings.streamErrorDetail(error)}",
                UserFacingStrings.streamErrorThinkMaxRoute(error))
            assertFalse(UserFacingStrings.streamErrorThinkMaxRoute(error).contains("不支持该能力"))
        }
    }

    @Test
    fun httpStatusRequiresCompleteCodeAndKeepsActionableLocalMessages() {
        assertEquals("HTTP 4012", UserFacingStrings.streamErrorDetail("HTTP 4012"))
        assertEquals("所选平台不存在，请重新选择模型", UserFacingStrings.streamErrorDetail("所选平台不存在，请重新选择模型"))
        assertTrue(UserFacingStrings.streamErrorDetail("LLM HTTP 401").contains("角色及会话"))
    }
}
