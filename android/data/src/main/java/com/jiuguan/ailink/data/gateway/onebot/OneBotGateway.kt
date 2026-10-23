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

    /** 上次补拉时间：WebSocket 抖动频繁触发 onOpen 时，避免反复拉同一批历史。 */
    @Volatile
    private var lastCatchUpAt = 0L

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
                    // 连上就补拉：NapCat 不重放断线期间的事件（实测），
                    // 断线/重启窗口里到达的消息只能靠历史接口找回来。
                    // 传 webSocket 而不是读 socket 字段：onOpen 可能早于 `socket = ws` 赋值。
                    scope.launch { catchUpMissed(webSocket) }
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
                    images = parsed.images,
                    replyToId = parsed.replyToId
                )
            )
        )
    }

    /**
     * 断线补拉：把连接断开期间到达的消息从历史接口找回来。
     *
     * 为什么需要：NapCat 4.18.38 的正向 WS **不会**重放断线期间缓冲的事件
     * （实测 20:50:20 到达的两条消息，20:50:40 重连后一条都没有补推），
     * 所以只能在连上之后主动调用历史接口。
     *
     * 边界设计：
     *  - 只在最近 [CATCH_UP_MAX_AGE_SEC] 秒内有动静的会话上补，最多 [CATCH_UP_SESSIONS] 个；
     *  - 每个会话最多 [CATCH_UP_MESSAGES] 条，整体 25 秒预算，超时立即收手；
     *  - 消息是否"值得回复"交给上层：`AccountRuntime.STALE_WINDOW_MS` 已实现
     *    「超过 10 分钟只入库、不回复」，这里只负责把消息补齐并投递；
     *  - 重复由 `MessageRepository.recordIncoming` 的 platformMsgId 查重挡掉。
     */
    private suspend fun catchUpMissed(ws: WebSocket) {
        val now = System.currentTimeMillis()
        if (now - lastCatchUpAt < CATCH_UP_THROTTLE_MS) return
        lastCatchUpAt = now

        val sessions = runCatching {
            val reply = callAction(
                ws,
                "get_recent_contact",
                buildJsonObject { put("count", CATCH_UP_SESSIONS) }
            ) ?: return
            (reply["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        }.getOrElse {
            log("补拉跳过：get_recent_contact 调用失败（${it.message ?: "未知错误"}）")
            return
        }
        if (sessions.isEmpty()) return

        val deadline = System.currentTimeMillis() + CATCH_UP_BUDGET_MS
        var total = 0
        for (session in sessions) {
            if (System.currentTimeMillis() > deadline) {
                log("补拉中断：超出 ${CATCH_UP_BUDGET_MS / 1000} 秒预算")
                break
            }
            val peerUin = session.jStr("peerUin")
            if (peerUin.isBlank()) continue
            val msgTime = session.jStr("msgTime").toLongOrNull() ?: 0L
            if (msgTime > 0 && now / 1000 - msgTime > CATCH_UP_MAX_AGE_SEC) continue

            // chatType 2 = 群，其余按私聊处理；判定错了只会拉空、不会误发
            val isGroup = session.jLong("chatType") == 2L
            val action = if (isGroup) "get_group_msg_history" else "get_friend_msg_history"
            val params = buildJsonObject {
                if (isGroup) put("group_id", peerUin) else put("user_id", peerUin)
                put("count", CATCH_UP_MESSAGES)
                put("reverse_order", false)
            }
            val reply = runCatching { callAction(ws, action, params) }.getOrNull() ?: continue
            val messages = runCatching {
                reply["data"]?.jsonObject?.get("messages") as? JsonArray
            }.getOrNull() ?: continue

            var pushed = 0
            for (element in messages) {
                val raw = element as? JsonObject ?: continue
                val incoming = toIncoming(raw, isGroup, peerUin) ?: continue
                // 逐条也按时间窗过滤：一个会话里可能压着几十条旧消息，
                // 全投进去只会把入库/幂等路径刷满，对补回断线窗口毫无帮助。
                if (now - incoming.timestamp > CATCH_UP_MAX_AGE_SEC * 1000) continue
                _events.tryEmit(GatewayEvent.Incoming(incoming))
                pushed++
            }
            if (pushed > 0) {
                total += pushed
                log("补拉 ${if (isGroup) "群 $peerUin" else "私聊 $peerUin"}：$pushed 条")
            }
        }
        if (total > 0) log("补拉完成：共 $total 条待入库")
    }

    /**
     * 把历史消息对象转成 [IncomingMessage]。
     *
     * 与 [handleMessage] 的差别只在 peerKey 的来源：私聊历史里 `user_id` 是"发送者"，
     * 机器人自己发的那条 user_id 就是自己，因此私聊的 peer 必须用请求参数（[peerUin]），
     * 不能像实时推送那样从每条消息里推。
     */
    internal fun toIncoming(root: JsonObject, isGroup: Boolean, peerUin: String): IncomingMessage? {        val senderId = root.jLong("user_id")
        val sid = root.jLong("self_id").takeIf { it > 0 } ?: selfId
        if (senderId > 0 && senderId == sid) return null

        val parsed = parseMessage(root["message"], sid, root.jStr("raw_message"))
        if (parsed.text.isBlank()) return null

        val sender = root["sender"] as? JsonObject
        val senderName = sender
            ?.let { s -> s.jStr("card").ifBlank { s.jStr("nickname") } }
            .orEmpty()

        val peerKey: String
        val title: String
        if (isGroup) {
            val groupId = root.jLong("group_id").takeIf { it > 0 }
                ?: peerUin.toLongOrNull()
                ?: return null
            peerKey = "g:$groupId"
            title = if (senderName.isNotBlank()) "群聊 · $senderName" else "群聊 $groupId"
        } else {
            val userId = peerUin.toLongOrNull() ?: return null
            peerKey = "p:$userId"
            title = senderName.ifBlank { "QQ $userId" }
        }

        val timestamp = root.jLong("time").let { seconds ->
            if (seconds > 1_000_000_000L) seconds * 1000L else System.currentTimeMillis()
        }

        return IncomingMessage(
            peerKey = peerKey,
            title = title,
            isGroup = isGroup,
            senderId = senderId,
            senderName = senderName.ifBlank { "对方" },
            text = parsed.text,
            atMe = parsed.atMe,
            platformMsgId = root.jStr("message_id"),
            timestamp = timestamp,
            images = parsed.images,
            replyToId = parsed.replyToId
        )
    }

    /**
     * 发一个不带 echo 之外的语义的 action，返回响应根对象。
     *
     * 与 [sendText] 的 echo 逻辑刻意不复用：`sendText` 的失败文案被 HANDOVER 的
     * 关键字表引用，动它有回归风险；这里只服务补拉，失败一律当成"该桥接端不支持"。
     */
    private suspend fun callAction(
        ws: WebSocket,
        action: String,
        params: JsonObject,
        timeoutMs: Long = 8_000L
    ): JsonObject? {
        val echo = "act-${System.nanoTime()}"
        val frame = buildJsonObject {
            put("action", action)
            put("params", params)
            put("echo", echo)
        }
        val deferred = CompletableDeferred<String>()
        pending[echo] = deferred
        if (!ws.send(frame.toString())) {
            pending.remove(echo)
            return null
        }
        val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(echo)
        val text = reply ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun log(message: String) =
        com.jiuguan.ailink.core.SessionLog.add("onebot", message)

    /**
     * 用桥接端的内部文件名换一个新鲜的图片地址。
     *
     * QQ 图床链接带 `rkey`，过期后原 URL 会 403/410；`get_image` 能按 file 重新签发一个。
     * 只接受 http(s) 结果——返回的 `file` 是容器内路径，App 读不到。
     */
    override suspend fun refreshImageUrl(file: String, url: String): String? {
        if (file.isBlank()) return null
        val ws = socket ?: return null
        val reply = runCatching {
            callAction(ws, "get_image", buildJsonObject { put("file", file) })
        }.getOrNull() ?: return null
        val data = reply["data"]?.jsonObject ?: return null
        return data.jStr("url").takeIf { it.startsWith("http") }
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

    private companion object {
        /** 补拉节流：5 分钟内只补一次，防止 WS 抖动把同一批历史反复投递。 */
        const val CATCH_UP_THROTTLE_MS = 5 * 60_000L

        /** 最近会话里只看这些个，够覆盖断线窗口，又不至于把整本历史翻一遍。 */
        const val CATCH_UP_SESSIONS = 20

        /** 每个会话最多拉这么多条。 */
        const val CATCH_UP_MESSAGES = 20

        /** 只补最近 30 分钟有动静的会话；更早的没有及时回复的意义。 */
        const val CATCH_UP_MAX_AGE_SEC = 30 * 60L

        /** 一次补拉的总预算，超时立即收手，避免拖慢启动后的首轮回复。 */
        const val CATCH_UP_BUDGET_MS = 25_000L
    }
}
