package com.mojing.app.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 执行 OkHttp 请求并把协程取消绑定到 [Call.cancel]。
 * 响应在回调线程内完整读取并自动关闭；若结果生成后才发生取消，可用 [onCancellation] 回收临时资源。
 */
internal suspend fun <T> OkHttpClient.executeCancellable(
    request: Request,
    onCancellation: (T) -> Unit = {},
    readResponse: (Response) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            try {
                val value = response.use(readResponse)
                continuation.resume(value) { _, cancelledValue, _ -> onCancellation(cancelledValue) }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
        }
    })
}
