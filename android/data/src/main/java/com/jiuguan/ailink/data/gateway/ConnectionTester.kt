package com.jiuguan.ailink.data.gateway

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 配置页的「测试连接」：只验证 WebSocket 能否握手成功，不发送任何业务指令。
 */
object ConnectionTester {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun testWebSocket(endpoint: String, token: String): Result<Long> = runCatching {
        withTimeoutOrNull(TIMEOUT_MS) {
            awaitOpen(endpoint.trim(), token.trim())
        } ?: error("连接超时（${TIMEOUT_MS / 1000} 秒），请检查地址、端口与网络")
    }

    private suspend fun awaitOpen(endpoint: String, token: String): Long =
        suspendCancellableCoroutine { continuation ->
            val settled = AtomicBoolean(false)
            val startedAt = System.nanoTime()
            val request = Request.Builder()
                .url(endpoint)
                .apply {
                    if (token.isNotBlank()) header("Authorization", "Bearer $token")
                }
                .build()

            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (settled.compareAndSet(false, true)) {
                        val cost = (System.nanoTime() - startedAt) / 1_000_000L
                        if (continuation.isActive) continuation.resume(cost)
                        webSocket.close(1000, "test finished")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (settled.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resumeWith(
                            Result.failure<Long>(IllegalStateException(t.message ?: "握手失败"))
                        )
                    }
                }
            })

            continuation.invokeOnCancellation { socket.cancel() }
        }

    private const val TIMEOUT_MS = 12_000L
}
