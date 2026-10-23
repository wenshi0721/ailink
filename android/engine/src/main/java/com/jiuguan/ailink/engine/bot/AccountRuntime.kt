package com.jiuguan.ailink.engine.bot

import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.data.local.MessageEntity
import com.jiuguan.ailink.data.local.MessageImage
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
        if (!stored) {
            // 补拉与实时推送可能同时带来同一条消息。这里显式留痕，
            // 出"重复回复"时一眼能看出是幂等生效还是漏网。
            com.jiuguan.ailink.core.SessionLog.add(
                "engine",
                "跳过重复消息 id=${message.platformMsgId} peer=${message.peerKey}"
            )
            return
        }
        update { it.copy(received = it.received + 1, lastActiveAt = System.currentTimeMillis()) }

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
                "图片=${message.images.size} 引用=${message.replyToId.ifBlank { "无" }} " +
                "文本=${message.text.take(20)}"
        )
        val profile = aiRepository.resolve(account.aiProfileId)

        // 引用回复：先把被引用的那条从本地库里捞出来（文本 + 图片）
        val quoted = runCatching {
            messageRepository.findByPlatformMsgId(convId, message.replyToId)
        }.getOrNull()
        if (quoted != null) {
            com.jiuguan.ailink.core.SessionLog.add(
                "engine",
                "引用消息 ${message.replyToId} 命中（图 ${quoted.images.size} 张）：${quoted.content.take(30)}"
            )
        }

        val reply = when {
            profile == null || profile.isLocalOnly ->
                localReply(convId, message.text)

            else -> {
                val prepared = prepareImages(profile, message)
                // 缓存文件名回填到消息上，供后续轮次回看与引用重载。
                // 按 url 合并而不是直接替换：usable 只取了前 3 张，直接replace 会丢掉其余图片的引用。
                if (prepared.isNotEmpty()) {
                    val cacheByUrl = prepared.associate { it.image.url to it.image.cache }
                    runCatching {
                        messageRepository.attachImageCache(
                            convId,
                            message.platformMsgId,
                            message.images.map { image ->
                                MessageImage(
                                    url = image.url,
                                    file = image.file,
                                    cache = cacheByUrl[image.url].orEmpty()
                                )
                            }
                        )
                    }
                }
                val currentUrls = prepared.mapNotNull { it.dataUrl }
                val quotedUrls = quotedImageUrls(quoted, MAX_CONTEXT_IMAGES - currentUrls.size)
                val images = (quotedUrls + currentUrls).take(MAX_CONTEXT_IMAGES)

                // 预算要同时扣掉引用图，否则 引用 2 张 + 当前 2 张 + 历史 2 张会突破上限
                val turns = buildTurns(
                    profile, convId, message, quoted,
                    currentImageCount = images.size
                )
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

    /** 一张已下载或命中缓存的图片：dataUrl 给模型，image 用于把缓存名回填数据库。 */
    private data class PreparedImage(val dataUrl: String?, val image: MessageImage)

    /**
     * 只有「开关打开 + 判定支持识图」时才下载图片；
     * 检测与下载各有总时长上限，超时就先发纯文本，不阻塞对话。
     *
     * 这里每一个提前返回都落一条日志。原因：识图链路上有四个互相独立、且都表现为
     * 「机器人像没看见图」的失败点（消息里没解析出图片 / 缺可下载 url / 能力判定不通 /
     * 下载转码失败），过去只有最后一种会打日志，前三种完全静默，导致无法定位。
     */
    private suspend fun prepareImages(
        profile: AiProfileEntity,
        message: IncomingMessage
    ): List<PreparedImage> {
        val all = message.images
        val usable = all.filter { it.isUsable }.take(MAX_IMAGES_PER_MESSAGE)
        if (usable.isEmpty()) {
            if (all.isNotEmpty()) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "vision",
                    "消息含 ${all.size} 张图但都缺可下载 url（桥接端需 enableLocalFile2Url 或提供 http 地址），按纯文本处理"
                )
            }
            return emptyList()
        }

        // 诊断：只暴露 url 的形态（协议+主机+路径），不带 query 里的 rkey。
        // 一条图片消息就能区分「桥接端给的是 CDN 地址 / 本地路径 / 空的 url」。
        val sample = usable.first()
        com.jiuguan.ailink.core.SessionLog.add(
            "vision",
            "附图来源 ${usable.size} 张，首张 url=${sample.url.substringBefore('?').take(64)} " +
                "file=${sample.file.takeLast(24)}"
        )

        val support = withTimeoutOrNull(VISION_DETECT_TIMEOUT_MS) {
            visionProbe.detect(profile)
        }
        if (support == null) {
            com.jiuguan.ailink.core.SessionLog.add(
                "vision",
                "识图检测超时（${VISION_DETECT_TIMEOUT_MS / 1000}s 未返回），本轮按纯文本处理"
            )
            return emptyList()
        }
        if (support != VisionSupport.SUPPORTED) {
            com.jiuguan.ailink.core.SessionLog.add(
                "vision",
                "识图不可用（${support.name}，配置「${profile.name}」模型 ${profile.model.ifBlank { "未填" }}），" +
                    "本轮按纯文本处理；可在配置页点「测试调用」强制重探"
            )
            return emptyList()
        }

        val prepared = withTimeoutOrNull(IMAGE_PREPARE_TIMEOUT_MS) {
            coroutineScope {
                usable.map { image -> async { imagePrep.prepare(image.url, image.file) } }.awaitAll()
            }
        }
        if (prepared == null) {
            com.jiuguan.ailink.core.SessionLog.add(
                "vision",
                "图片下载转码超时（${IMAGE_PREPARE_TIMEOUT_MS / 1000}s），本轮按纯文本处理"
            )
            return emptyList()
        }

        val result = usable.mapIndexed { index, image ->
            val hit = prepared.getOrNull(index)
            PreparedImage(
                dataUrl = hit?.dataUrl,
                image = MessageImage(url = image.url, file = image.file, cache = hit?.cache.orEmpty())
            )
        }
        com.jiuguan.ailink.core.SessionLog.add(
            "vision",
            "本轮附图 ${result.count { it.dataUrl != null }}/${usable.size} 张（配置「${profile.name}」）"
        )
        return result
    }

    /**
     * 引用消息的图片：优先本地缓存，其次用原地址下载，地址过期再用 file 换新地址。
     *
     * 这一步是"引用一张图再追问"能成立的关键——引用发生时原消息往往已经过了十几分钟，
     * 图床 rkey 可能失效，只有缓存或重新签发才拿得到。
     */
    private suspend fun quotedImageUrls(quoted: MessageEntity?, budget: Int): List<String> {
        if (quoted == null || budget <= 0) return emptyList()
        val urls = mutableListOf<String>()
        for (image in quoted.images) {
            if (urls.size >= budget) break

            val cached = imagePrep.fromCache(image.cache)
            if (cached != null) {
                urls += cached
                continue
            }

            var dataUrl = if (image.url.startsWith("http")) {
                imagePrep.prepare(image.url, image.file)?.dataUrl
            } else {
                null
            }

            if (dataUrl == null && image.file.isNotBlank()) {
                val fresh = runCatching { gateway.refreshImageUrl(image.file, image.url) }.getOrNull()
                if (!fresh.isNullOrBlank()) {
                    dataUrl = imagePrep.prepare(fresh, image.file)?.dataUrl
                    com.jiuguan.ailink.core.SessionLog.add("vision", "引用图地址已刷新并重新下载")
                }
            }

            if (dataUrl != null) urls += dataUrl
        }
        if (urls.isNotEmpty()) {
            com.jiuguan.ailink.core.SessionLog.add(
                "vision",
                "引用附图 ${urls.size}/${quoted.images.size} 张"
            )
        }
        return urls
    }

    /**
     * 组装对话：system + 历史（可带图）+ 当前这条（可带引用文本）。
     *
     * 图片预算：整轮请求最多 [MAX_CONTEXT_IMAGES] 张，当前轮优先，
     * 剩下的额度按"从新到旧"分给最近 [IMAGE_CONTEXT_TURNS] 轮历史。
     */
    private suspend fun buildTurns(
        profile: AiProfileEntity,
        convId: String,
        message: IncomingMessage,
        quoted: MessageEntity?,
        currentImageCount: Int
    ): List<ChatTurn> {
        val raw = messageRepository.recentForContext(convId, CONTEXT_SIZE)
            .filter { !it.isSystem && it.content.isNotBlank() }
        // 当前这条已经入库，先摘掉，避免同一句话在上下文里出现两次
        val history = dropCurrent(raw, message)

        var budget = (MAX_CONTEXT_IMAGES - currentImageCount).coerceAtLeast(0)
        var turnsWithImages = 0
        val historyImages = HashMap<Int, List<String>>()
        if (budget > 0) {
            for (index in history.indices.reversed()) {
                if (budget <= 0 || turnsWithImages >= IMAGE_CONTEXT_TURNS) break
                val entity = history[index]
                if (entity.images.isEmpty()) continue
                val urls = mutableListOf<String>()
                for (image in entity.images) {
                    if (budget <= 0) break
                    // 历史轮次只读本地缓存：重新下载既慢又可能已被图床回收
                    val dataUrl = imagePrep.fromCache(image.cache) ?: continue
                    urls += dataUrl
                    budget -= 1
                }
                if (urls.isNotEmpty()) {
                    historyImages[index] = urls
                    turnsWithImages += 1
                }
            }
        }
        if (historyImages.isNotEmpty()) {
            com.jiuguan.ailink.core.SessionLog.add(
                "vision",
                "历史附图 ${historyImages.values.sumOf { it.size }} 张，覆盖 $turnsWithImages 轮"
            )
        }

        val dialog = history.mapIndexed { index, entity ->
            ChatTurn(
                role = if (entity.isOutgoing) "assistant" else "user",
                content = entity.content,
                images = historyImages[index].orEmpty()
            )
        } + ChatTurn("user", userText(message, quoted))

        return listOf(ChatTurn("system", systemPrompt(profile))) + dialog
    }

    /** 当前这条消息的文本；带引用时把被引用内容作为前缀注入，模型才知道"在说什么"。 */
    private fun userText(message: IncomingMessage, quoted: MessageEntity?): String {
        if (quoted == null) return message.text
        val who = quoted.senderName.ifBlank { "对方" }
        val body = quoted.content.replace('\n', ' ').take(120)
        return "（引用「$who」：$body）\n${message.text}"
    }

    /**
     * 从历史里摘掉"当前这条"。
     *
     * 优先按平台消息 id 匹配；桥接端没给 id 时退回"时间戳 + 文本"的弱匹配，
     * 两边都对不上就原样保留（宁可重复一次，也不要误删上下文）。
     */
    private fun dropCurrent(history: List<MessageEntity>, message: IncomingMessage): List<MessageEntity> {
        if (history.isEmpty()) return history
        val last = history.last()
        val sameById = message.platformMsgId.isNotBlank() && last.platformMsgId == message.platformMsgId
        val sameByContent = !last.isOutgoing &&
            last.createdAt == message.timestamp &&
            last.content == message.text
        return if (sameById || sameByContent) history.dropLast(1) else history
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

        /** 整轮请求的图片总张数上限（当前轮 + 引用图 + 历史图），防止 token 失控。 */
        const val MAX_CONTEXT_IMAGES = 4

        /** 上下文里最多回看几轮带图的历史对话。 */
        const val IMAGE_CONTEXT_TURNS = 2

        const val VISION_DETECT_TIMEOUT_MS = 15_000L
        const val IMAGE_PREPARE_TIMEOUT_MS = 12_000L
    }
}
