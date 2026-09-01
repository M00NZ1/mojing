package com.mojing.app.engine

import com.mojing.app.domain.engine.AnthropicAdapter
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AnthropicAdapterTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val adapter = AnthropicAdapter(client)

    @Test
    fun messagesUrlWithBaseOnly() {
        val url = invokeBuildMessagesUrl("https://api.anthropic.com")
        assertEquals("https://api.anthropic.com/v1/messages", url)
    }

    @Test
    fun messagesUrlWithV1MessagesAlready() {
        val url = invokeBuildMessagesUrl("https://api.anthropic.com/v1/messages")
        assertEquals("https://api.anthropic.com/v1/messages", url)
    }

    @Test
    fun messagesUrlWithTrailingSlash() {
        val url = invokeBuildMessagesUrl("https://api.anthropic.com/")
        assertEquals("https://api.anthropic.com/v1/messages", url)
    }

    @Test
    fun apiVersionConstant() {
        assertEquals("2023-06-01", AnthropicAdapter.API_VERSION)
    }

    @Test
    fun adapterConstructsWithClient() {
        val a = AnthropicAdapter(client)
        assertTrue(a is AnthropicAdapter)
    }

    private fun invokeBuildMessagesUrl(baseUrl: String): String {
        val method = AnthropicAdapter::class.java.getDeclaredMethod(
            "buildMessagesUrl", String::class.java
        )
        method.isAccessible = true
        return method.invoke(adapter, baseUrl) as String
    }
}
