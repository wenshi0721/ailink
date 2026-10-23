package com.jiuguan.ailink.engine.bot

import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.data.local.MessageOrigin
import com.jiuguan.ailink.data.gateway.GatewayEvent
import com.jiuguan.ailink.data.gateway.IncomingMessage
import com.jiuguan.ailink.data.gateway.MessageGateway
import com.jiuguan.ailink.data.ai.AiClient
import com.jiuguan.ailink.data.ai.ChatTurn
import com.jiuguan.ailink.data.ai.ImagePrep
import com.jiuguan.ailink.data.ai.LocalResponder
import com.jiuguan.ailink.data.ai.VisionProbe
import com.jiuguan.ailink.data.gateway.convIdOf
import com.jiuguan.ailink.data.repo.AiRepository
import com.jiuguan.ailink.data.repo.MessageRepository
import com.jiuguan.ailink.model.ConnStatus
import com.jiuguan.ailink.model.TriggerScope
import com.jiuguan.ailink.model.VisionSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.jiuguan.ailink.engine.service.RuntimeState

/**
 * 单个账号的运行时：维持网关连接、落库消息、按策略调用 AI 并回复。
 */
class AccountRuntime(
    val account: AccountEntity,
    private val gateway: MessageGateway,
    private val scope: CoroutineScope,
    private val messageRepository: MessageRepository,
    private val aiRepository: AiRepository,
    private val aiClient: AiClient,
    private val visionProbe: VisionProbe,
    private val imagePrep: ImagePrep,
    private val onStateChanged: (RuntimeState) -> Unit
) {

    private val _state = MutableStateFlow(
        RuntimeState(accountId = account.id, status = ConnStatus.CONNECTING)
    )
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var listenJob: Job? = null
    private val replyMutex = Mutex()
    private val recentReplies = mutableMapOf<String, String>()

    fun start() {
        if (listenJob != null) return
        listenJob = scope.launch {
            gateway.events.collect { event -> handleEvent(event) }
        }
        gateway.start()
    }

    fun stop() {
        gateway.stop()
        listenJob?.cancel()
        listenJob = null
        update { it.copy(status = ConnStatus.OFFLINE, latencyMs = -1L) }
    }

    suspend fun send(peerKey: String, isGroup: Boolean, text: String): Boolean =
        gateway.sendText(peerKey, isGroup, text)

    private suspend fun handleEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Incoming -> onIncoming(event.message)

            is GatewayEvent.Status -> update { current ->
                val status = when {
                    event.online -> ConnStatus.ONLINE
                    event.connecting -> ConnStatus.CONNECTING
                    event.error.isNotBlank() -> ConnStatus.ERROR
                    else -> ConnStatus.OFFLINE
                }
                current.copy(
                    status = status,
                    selfId = if (event.selfId > 0) event.selfId else current.selfId,
                    connectedAt = if (event.online && current.connectedAt == 0L) {
                        System.currentTimeMillis()
                    } else if (!event.online) {
                        0L
                    } else {
                        current.connectedAt
                    },
                    latencyMs = if (event.latencyMs >= 0) event.latencyMs else current.latencyMs,
                    lastError = event.error
                )
            }
        }
    }

    private suspend fun onIncoming(message: IncomingMessage) {
        if (message.text.isBlank()) return

        val stored = messageRepository.recordIncoming(account, message)
        if (stored) {
            update { it.copy(received = it.received + 1, lastActiveAt = System.currentTimeMillis()) }
        }
        if (!stored) return

        // 启动瞬间可能收到历史消息，避免批量补答。
        if (System.currentTimeMillis() - message.timestamp > STALE_WINDOW_MS) return
        if (!shouldReply(message)) return

        val convId = convIdOf(account.id, message.peerKey)
        replyMutex.withLock {
            if (!messageRepository.isAutoReplyEnabled(convId)) return@withLock
            val wait = account.replyDelayMs.coerceIn(0L, 5_000L)
            if (wait > 0) delay(wait)
            deliverReply(convId, message)
        }
    }

    /** 自动回复判定：范围、关键词、自身消息都在这里收敛。 */
    private fun shouldReply(message: IncomingMessage): Boolean {
        if (!account.autoReply) return false
        if (message.senderId > 0 && message.senderId == _state.value.selfId) return false
        if (message.text.length > 1_500) return false

        if (message.isGroup) {
            when (account.scope) {
                TriggerScope.PRIVATE_ONLY -> return false
                TriggerScope.PRIVATE_AND_AT -> if (!message.atMe) return false
                TriggerScope.ALL -> Unit
            }
        }

        val keywords = account.ignoreKeywordList
        if (keywords.any { message.text.contains(it, ignoreCase = true) }) return false

        return true
    }

    private suspend fun deliverReply(convId: String, message: IncomingMessage) {
        com.jiuguan.ailink.core.SessionLog.add(
            "engine",
            "处理消息 peer=${message.peerKey} group=${message.isGroup} " +
                "图片=${message.images.size} 文本=${message.text.take(20)}"
        )
        val profile = aiRepository.resolve(account.aiProfileId)

        val reply = when {
            profile == null || profile.isLocalOnly ->
                localReply(convId, message.text)

            else -> {
                val images = prepareImages(profile, message)
                val turns = buildTurns(profile, convId, message.text)
                var text: String? = null
                aiClient.complete(profile, turns, imageDataUrls = images).fold(
                    onSuccess = { text = it },
                    onFailure = { error ->
                        com.jiuguan.ailink.core.SessionLog.add(
                            "ai",
                            "AI 调用失败：${error.message ?: error.javaClass.simpleName}"
                        )
                        messageRepository.recordSystem(
                            convId = convId,
                            accountId = account.id,
                            text = "AI 回复失败：${error.message ?: "未知错误"}"
                        )
                    }
                )
                text.orEmpty()
            }
        }

        val finalReply = reply.trim().take(600)
        if (finalReply.isBlank()) return

        val ok = gateway.sendText(message.peerKey, message.isGroup, finalReply)
        com.jiuguan.ailink.core.SessionLog.add(
            "engine",
            "回复 ${if (ok) "成功" else "失败"} peer=${message.peerKey} group=${message.isGroup} 长度=${finalReply.length}"
        )
        messageRepository.recordOutgoing(
            account = account,
            peerKey = message.peerKey,
            title = message.title,
            isGroup = message.isGroup,
            text = finalReply,
            success = ok,
            origin = MessageOrigin.AUTO
        )
        if (ok) {
            update { it.copy(replied = it.replied + 1, lastActiveAt = System.currentTimeMillis()) }
        }
    }

    /** 本地回复：避免同一会话连续两句一模一样。 */
    private fun localReply(convId: String, incoming: String): String {
        var reply = LocalResponder.reply(account.persona, incoming)
        var attempts = 0
        while (reply == recentReplies[convId] && attempts < 4) {
            reply = LocalResponder.reply(account.persona, incoming)
            attempts++
        }
        recentReplies[convId] = reply
        return reply
    }

    /**
     * 只有「开关打开 + 判定支持识图」时才下载图片；
     * 检测与下载各有总时长上限，超时就先发纯文本，不阻塞对话。
     */
    private suspend fun prepareImages(
        profile: AiProfileEntity,
        message: IncomingMessage
    ): List<String> {
        val usable = message.images.filter { it.isUsable }.take(MAX_IMAGES_PER_MESSAGE)
        if (usable.isEmpty()) return emptyList()

        val support = withTimeoutOrNull(VISION_DETECT_TIMEOUT_MS) {
            visionProbe.detect(profile)
        } ?: VisionSupport.UNKNOWN
        if (support != VisionSupport.SUPPORTED) return emptyList()

        val prepared = withTimeoutOrNull(IMAGE_PREPARE_TIMEOUT_MS) {
            coroutineScope {
                usable.map { image -> async { imagePrep.toDataUrl(image.url) } }.awaitAll()
            }
        }
        val dataUrls = prepared?.filterNotNull().orEmpty()
        com.jiuguan.ailink.core.SessionLog.add(
            "vision",
            "本轮附图 ${dataUrls.size}/${usable.size} 张（配置「${profile.name}」）"
        )
        return dataUrls
    }

    private suspend fun buildTurns(
        profile: AiProfileEntity,
        convId: String,
        incomingText: String
    ): List<ChatTurn> {
        val history = messageRepository.recentForContext(convId, CONTEXT_SIZE)
            .filter { !it.isSystem && it.content.isNotBlank() }
            .map {
                ChatTurn(
                    role = if (it.isOutgoing) "assistant" else "user",
                    content = it.content
                )
            }

        val dialog = if (history.isEmpty() || history.last().role != "user") {
            history + ChatTurn("user", incomingText)
        } else {
            history
        }

        return listOf(ChatTurn("system", systemPrompt(profile))) + dialog
    }

    private fun systemPrompt(profile: AiProfileEntity): String = buildString {
        val base = profile.systemPrompt.trim()
        if (base.isNotEmpty()) append(base)
        if (account.persona.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("账号人设：").append(account.persona.trim())
        }
        if (isNotEmpty()) append("\n\n")
        append(
            "你正在通过「${account.platformType.label}」与对方聊天。" +
                "要求：口语化、简短（1-3 句）、不使用 Markdown 语法、不要提及自己是 AI 或机器人。"
        )
    }

    private fun update(transform: (RuntimeState) -> RuntimeState) {
        val next = transform(_state.value)
        _state.value = next
        onStateChanged(next)
    }

    private companion object {
        const val STALE_WINDOW_MS = 10 * 60_000L
        const val CONTEXT_SIZE = 8

        /** 单条消息最多附 3 张图：再多对聊天场景收益很小，却会显著拉长响应时间。 */
        const val MAX_IMAGES_PER_MESSAGE = 3
        const val VISION_DETECT_TIMEOUT_MS = 15_000L
        const val IMAGE_PREPARE_TIMEOUT_MS = 12_000L
    }
}
