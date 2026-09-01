package com.mojing.app.remote

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.remote.BackendEncyclopediaApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackendEncyclopediaApiTest {

    @Test
    fun previewCancellationCancelsUnderlyingCall() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"urls":["https://example.com/cover.png"]}""")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        server.start()
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.countDown()
                }
            })
            .build()
        val storage = mockk<SecureStorage> {
            every { publicBaseUrl } returns server.url("/").toString()
        }
        val api = BackendEncyclopediaApi(client, storage)

        try {
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    api.previewEntryCoverImage("测试百科", "world", "测试简介")
                }
                assertNotNull("server did not receive request", server.takeRequest(2, TimeUnit.SECONDS))
                delay(100)
                withTimeout(1_000L) { job.cancelAndJoin() }
                assertTrue("underlying preview call was not cancelled", cancelled.await(1, TimeUnit.SECONDS))
            }
        } finally {
            server.shutdown()
        }
    }
}
