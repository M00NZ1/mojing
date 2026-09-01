package com.mojing.app.remote

import com.mojing.app.data.remote.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancellableOkHttpTest {

    @Test
    fun coroutineCancellationCancelsUnderlyingCall() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("delayed response")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        server.start()
        val callCancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    callCancelled.countDown()
                }
            })
            .build()

        try {
            runBlocking {
                val request = Request.Builder().url(server.url("/slow")).build()
                val job = launch(Dispatchers.Default) {
                    client.executeCancellable(request) { response -> response.body?.bytes() }
                }
                assertNotNull("server did not receive request", server.takeRequest(2, TimeUnit.SECONDS))
                delay(100)
                withTimeout(1_000L) {
                    job.cancelAndJoin()
                }
                assertTrue("underlying OkHttp call was not cancelled", callCancelled.await(1, TimeUnit.SECONDS))
            }
        } finally {
            server.shutdown()
        }
    }
}
