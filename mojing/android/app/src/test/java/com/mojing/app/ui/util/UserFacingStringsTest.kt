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
}
