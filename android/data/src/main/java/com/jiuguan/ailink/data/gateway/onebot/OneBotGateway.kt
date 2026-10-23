package com.jiuguan.ailink.data.gateway.onebot

import com.jiuguan.ailink.data.gateway.GatewayEvent
import com.jiuguan.ailink.data.gateway.IncomingMessage
import com.jiuguan.ailink.data.gateway.MessageGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OneBot v11 正向 WebSocket 接入。
 *
 * 适用于 NapCat / LLOneBot / Lagrange / go-cqhttp 等桥接端：
 * 在桥接端配置「正向 WebSocket 服务端」，把地址与令牌填入应用即可。
 */
class OneBotGateway(
    private val url: String,
    private val token: String,
    private val scope: CoroutineScope
) : MessageGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _events = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 不要设置 pingInterval：OkHttp 会发 WebSocket 协议层的 ping 帧，
        // NapCat 的 WS 服务端只认 JSON 帧，收到 ping 会直接断开（表现为每 20 秒 EOFException）。
        // 保活改用下面的 JSON 心跳（get_status，25 秒）。
        .retryOnConnectionFailure(true)
        .build()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile
    private var running = false
    private var socket: WebSocket? = null
    private var loopJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var selfId: Long = 0L

    override fun start() {
        if (running) return
        running = true
        loopJob = scope.launch { connectLoop() }
    }

    override fun stop() {
        running = false
        loopJob?.cancel()
        loopJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close(1000, "client stop")
        socket = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        _events.tryEmit(GatewayEvent.Status(online = false))
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (running) {
            _events.emit(GatewayEvent.Status(online = false, connecting = true))

            val closed = CompletableDeferred<String>()
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
                }
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    com.jiuguan.ailink.core.SessionLog.add(
                        "onebot",
                        "已连接 $url（selfId=$selfId）"
                    )
                    attempt = 0
                    _events.tryEmit(
                        GatewayEvent.Status(online = true, selfId = selfId, latencyMs = 0L)
                    )
                    startHeartbeat()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    com.jiuguan.ailink.core.SessionLog.add(
                        "onebot",
                        "连接失败：${t.message ?: t.javaClass.simpleName}（第 ${attempt + 1} 次尝试）"
                    )
                    if (!closed.isCompleted) {
                        closed.complete(t.message ?: "WebSocket 连接失败")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    com.jiuguan.ailink.core.SessionLog.add("onebot", "连接关闭（$code $reason）")
                    if (!closed.isCompleted) {
                        closed.complete("连接已关闭（$code）")
                    }
                }
            }

            val ws = client.newWebSocket(request, listener)
            socket = ws

            val reason = closed.await()

            heartbeatJob?.cancel()
            heartbeatJob = null
            pending.values.forEach { it.cancel() }
            pending.clear()
            socket = null
            ws.cancel()

            if (!running) break

            _events.emit(GatewayEvent.Status(online = false, selfId = selfId, error = reason))
            attempt += 1
            val backoff = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
            delay(backoff)
        }
        _events.emit(GatewayEvent.Status(online = false))
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running) {
                delay(25_000L)
                val ws = socket ?: break
                val echo = "hb-${System.nanoTime()}"
                val deferred = CompletableDeferred<String>()
                pending[echo] = deferred
                val started = System.nanoTime()
                val payload = buildJsonObject {
                    put("action", "get_status")
                    put("params", buildJsonObject { })
                    put("echo", echo)
                }
                if (!ws.send(payload.toString())) {
                    pending.remove(echo)
                    continue
                }
                val reply = withTimeoutOrNull(8_000L) { deferred.await() }
                pending.remove(echo)
                val latency = (System.nanoTime() - started) / 1_000_000L
                if (reply == null) {
                    _events.emit(GatewayEvent.Status(online = true, selfId = selfId, latencyMs = -1L, error = "心跳超时"))
                } else {
                    _events.emit(GatewayEvent.Status(online = true, selfId = selfId, latencyMs = latency))
                }
            }
        }
    }

    private fun handleFrame(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return

        val echo = root.jStr("echo")
        if (echo.isNotBlank()) {
            pending.remove(echo)?.complete(text)
            return
        }

        when (root.jStr("post_type")) {
            "message" -> handleMessage(root)
            "meta_event" -> handleMeta(root)
        }
    }

    private fun handleMeta(root: JsonObject) {
        val sid = root.jLong("self_id")
        if (sid > 0) selfId = sid
        if (root.jStr("meta_event_type") == "lifecycle") {
            _events.tryEmit(GatewayEvent.Status(online = true, selfId = selfId))
        }
    }

    private fun handleMessage(root: JsonObject) {
        val isGroup = root.jStr("message_type") == "group"
        val senderId = root.jLong("user_id")
        val sid = root.jLong("self_id").takeIf { it > 0 } ?: selfId
        if (senderId > 0 && senderId == sid) return

        val raw = root.jStr("raw_message")
        val parsed = parseMessage(root["message"], sid, raw)
        if (parsed.text.isBlank()) return

        val sender = root["sender"] as? JsonObject
        val senderName = sender
            ?.let { s -> s.jStr("card").ifBlank { s.jStr("nickname") } }
            .orEmpty()

        val peerKey: String
        val title: String
        if (isGroup) {
            val groupId = root.jLong("group_id")
            peerKey = "g:$groupId"
            title = if (senderName.isNotBlank()) "群聊 · $senderName" else "群聊 $groupId"
        } else {
            peerKey = "p:$senderId"
            title = senderName.ifBlank { "QQ $senderId" }
        }

        val timestamp = root.jLong("time").let { seconds ->
            if (seconds > 1_000_000_000L) seconds * 1000L else System.currentTimeMillis()
        }

        _events.tryEmit(
            GatewayEvent.Incoming(
                IncomingMessage(
                    peerKey = peerKey,
                    title = title,
                    isGroup = isGroup,
                    senderId = senderId,
                    senderName = senderName.ifBlank { "对方" },
                    text = parsed.text,
                    atMe = parsed.atMe,
                    platformMsgId = root.jStr("message_id"),
                    timestamp = timestamp,
                    images = parsed.images
                )
            )
        )
    }

    override suspend fun sendText(peerKey: String, isGroup: Boolean, text: String): Boolean {
        var ws = socket
        if (ws == null) {
            // 断线重连通常 2 秒内完成，这里等最多 5 秒再重试一次，避免消息卡在断线窗口
            com.jiuguan.ailink.core.SessionLog.add("onebot", "发送时连接已断，等待重连…")
            var waited = 0
            while (socket == null && waited < 20) {
                kotlinx.coroutines.delay(250)
                waited++
            }
            ws = socket
        }
        if (ws == null) {
            com.jiuguan.ailink.core.SessionLog.add("onebot", "发送失败：WebSocket 未连接（等待 5 秒仍未恢复）")
            return false
        }
        val echo = "send-${System.nanoTime()}"
        val target = peerKey.substringAfter(':', "").toLongOrNull()
        if (target == null) {
            com.jiuguan.ailink.core.SessionLog.add("onebot", "发送失败：无法从 $peerKey 解析目标号")
            return false
        }

        val payload = buildJsonObject {
            put("action", if (isGroup) "send_group_msg" else "send_private_msg")
            put("params", buildJsonObject {
                if (isGroup) put("group_id", target) else put("user_id", target)
                put("message", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("data", buildJsonObject { put("text", text) })
                    })
                })
            })
            put("echo", echo)
        }

        val deferred = CompletableDeferred<String>()
        pending[echo] = deferred
        if (!ws.send(payload.toString())) {
            pending.remove(echo)
            com.jiuguan.ailink.core.SessionLog.add("onebot", "发送失败：ws.send 返回 false（连接已关闭？）")
            return false
        }
        val reply = withTimeoutOrNull(10_000L) { deferred.await() }
        pending.remove(echo)
        if (reply == null) {
            com.jiuguan.ailink.core.SessionLog.add(
                "onebot",
                "发送超时：10 秒未收到回执（peer=$peerKey, group=$isGroup）"
            )
            return false
        }

        val root = runCatching { json.parseToJsonElement(reply).jsonObject }.getOrNull()
            ?: return false
        val status = root.jStr("status")
        val retcode = root.jLong("retcode")
        val ok = status == "ok" || retcode == 0L
        if (!ok) {
            com.jiuguan.ailink.core.SessionLog.add(
                "onebot",
                "发送被拒：status=$status retcode=$retcode ${reply.take(200)}"
            )
        }
        return ok
    }
}
