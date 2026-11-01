package com.jiuguan.ailink.engine.bot

import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.data.local.ConversationEntity
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
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.model.TriggerScope
import com.jiuguan.ailink.model.VisionSupport
import com.jiuguan.ailink.core.reply.BehaviorConfig
import com.jiuguan.ailink.core.reply.OutputLedger
import com.jiuguan.ailink.core.reply.ProactiveCandidate
import com.jiuguan.ailink.core.reply.ProactiveDecision
import com.jiuguan.ailink.core.reply.ProactiveKind
import com.jiuguan.ailink.core.reply.ProactiveOutcome
import com.jiuguan.ailink.core.reply.ProactivePolicy
import com.jiuguan.ailink.core.reply.ReplyPacing
import com.jiuguan.ailink.core.reply.SegmentSplitter
import com.jiuguan.ailink.core.reply.SessionGovernor
import com.jiuguan.ailink.engine.service.QqBridgeService
import com.jiuguan.ailink.engine.service.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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
    /** 账号级行为配置（多段 / 主动 / 限流）。默认全关，等价于改造前的单条回复。 */
    private val behavior: BehaviorConfig = BehaviorConfig.DEFAULT,
    private val onStateChanged: (RuntimeState) -> Unit
) {

    private val _state = MutableStateFlow(
        RuntimeState(accountId = account.id, status = ConnStatus.CONNECTING)
    )
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var listenJob: Job? = null

    /**
     * 按会话加锁，而不是全局一把锁。
     *
     * 改造前是一个 `replyMutex` 串行化所有会话；加入多段发送后，一次回复的持锁时间
     * 从「一次 AI 调用」变成「一次 AI 调用 + 若干条消息的段间隔」，全局锁会让 A 会话的
     * 多段发送把 B 会话的回复一起卡住。按会话分锁后，不同人之间互不影响，
     * 同一个人仍然严格串行（这是必须的：并发回复同一会话会打乱上下文顺序）。
     */
    private val convMutexes = ConcurrentHashMap<String, Mutex>()

    /** 每个会话的「单位时间输出」账本，见 [OutputLedger]。 */
    private val outputLedgers = ConcurrentHashMap<String, OutputLedger>()

    /**
     * 会话状态与限流。配置在构造时从 [behavior] 取，账号重启（改配置后保存）即重新生效。
     */
    private val governor = SessionGovernor(behavior.throttle)

    /**
     * 本地模拟回复的"上一句"，用来避免同一会话连续两句一模一样。
     *
     * 改成并发 Map 是按会话分锁的直接后果：以前全局锁保证同一时刻只有一个人在读写，
     * 现在不同会话会并行走到这里，普通 HashMap 会有并发写风险。
     */
    private val recentReplies = ConcurrentHashMap<String, String>()

    /** 主动发起的调度循环；仅在配置打开时存在。 */
    private var proactiveJob: Job? = null

    /** 今天已经触发过的定时打招呼槽位（同一天同一个时间点只发一次）。 */
    private val firedSlots = mutableSetOf<String>()

    private var slotDayKey = ""

    /** 「未发起」日志的节流时间戳，避免每分钟写一条。 */
    private var lastSkipLogAt = 0L

    /** 订阅前台服务的 QQ 离线事件，用于纠正"WS 在线但账号已掉"的假状态。 */
    private var qqOfflineJob: Job? = null

    private fun mutexOf(convId: String): Mutex = convMutexes.getOrPut(convId) { Mutex() }

    fun start() {
        if (listenJob != null) return
        // 收到的每条消息用独立协程处理，不在收集循环里同步跑。两个理由，都是必须的：
        // 1. 一次回复现在要「等冷却 + 调 AI + 按间隔发 N 条」，可能持续十几秒。同步处理会把
        //    这期间到达的消息全堵在门口，而网关缓冲只有 128 条，溢出就丢最旧的。
        // 2. 冷却合并的前提是「等冷却时仍能收到新消息」；堵住收集循环就等不到新消息，
        //    合并逻辑会形同虚设。
        listenJob = scope.launch {
            gateway.events.collect { event ->
                when (event) {
                    is GatewayEvent.Incoming -> launch { handleEvent(event) }
                    // 状态事件很轻，直接处理即可，顺带保持状态更新的先后顺序
                    is GatewayEvent.Status -> handleEvent(event)
                }
            }
        }
        gateway.start()
        startProactive()
        watchQqOffline()
    }

    /**
     * 把"QQ 账号真的掉线了"同步到账号状态。
     *
     * OneBot 的 WS 连接和 QQ 账号登录状态是两回事：账号被踢下线后 NapCat 的端口照样监听、
     * 心跳照样有回执，网关因此一直报「在线」。前台服务那边能直接读到内核推送的离线事件，
     * 这里订阅它，才能让界面不再说谎。
     */
    private fun watchQqOffline() {
        if (account.platformType != Platform.QQ) return
        qqOfflineJob = scope.launch {
            QqBridgeService.offline.collect { offline ->
                if (offline) {
                    update { current ->
                        if (current.status == ConnStatus.OFFLINE) {
                            current
                        } else {
                            current.copy(
                                status = ConnStatus.ERROR,
                                latencyMs = -1L,
                                lastError = "QQ 已离线，等待自动恢复"
                            )
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        proactiveJob?.cancel()
        proactiveJob = null
        qqOfflineJob?.cancel()
        qqOfflineJob = null
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
        // 先登记序号（在拿锁之前）：冷却合并要靠它判断"等我的时候有没有更新的话"。
        val seq = governor.noteIncoming(convId)

        mutexOf(convId).withLock {
            if (!messageRepository.isAutoReplyEnabled(convId)) return@withLock

            // ① 账号退避：连续发送失败后停手。消息已经入库，不会漏，只是暂时不张口。
            val muted = governor.mutedRemainingMs()
            if (muted > 0L) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "throttle",
                    "账号退避中（剩余 ${muted / 1000}s），本条仅入库 peer=${message.peerKey}"
                )
                return@withLock
            }

            // ② 会话冷却：刚回过话，等一下再说，避免一问一答被切成碎句
            val cooldown = governor.cooldownRemainingMs(convId)
            if (cooldown > 0L) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "throttle",
                    "会话冷却中（剩余 ${cooldown}ms），本条先排队 peer=${message.peerKey}"
                )
                delay(cooldown)
            }

            // ③ 合并判定：等待期间又有人说话，就把这次机会让给更新的那条。
            //    它读上下文时从库里拉最近 N 条，本来就看得到本条，不会漏内容。
            val claim = governor.claimTurn(convId, seq)
            if (!claim.granted) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "throttle",
                    "已合并到更新的一条，本条不再单独回复 peer=${message.peerKey}"
                )
                return@withLock
            }

            if (claim.merged > 0) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "throttle",
                    "合并 ${claim.merged} 条待回复消息，统一回复 peer=${message.peerKey}"
                )
            }
            if (claim.starved) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "throttle",
                    "对方持续在说话，超过 ${SessionGovernor.MERGE_DEADLINE_MS / 1000}s 强制回复一次 " +
                        "peer=${message.peerKey}"
                )
            }

            var sentAt = 0L
            try {
                val wait = account.replyDelayMs.coerceIn(0L, 5_000L)
                if (wait > 0) delay(wait)
                sentAt = deliverReply(convId, message)
            } finally {
                // 无论成功、失败还是异常，都要解锁会话状态，否则这个会话会永远卡在"回复中"
                governor.finishTurn(convId, sentAt)
            }
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

    /**
     * 生成并发送一次回复。
     *
     * @return 最后一条成功发出的时间戳；0 表示这一轮一条都没发出去（用于计算冷却起点）。
     */
    private suspend fun deliverReply(convId: String, message: IncomingMessage): Long {
        // 记下「为什么回这一条」：私聊直接回、群里被 @、还是群内全量触发。
        // 排查「明明开了自动回复却不回」时，第一眼看的就是这个字段。
        val trigger = when {
            !message.isGroup -> "私聊"
            account.scope == TriggerScope.ALL -> "群内全量"
            else -> "群@"
        }
        com.jiuguan.ailink.core.SessionLog.add(
            "engine",
            "处理消息 peer=${message.peerKey} group=${message.isGroup} 触发=$trigger " +
                "图片=${message.images.size} 引用=${message.replyToId.ifBlank { "无" }} " +
                "状态=${governor.phaseOf(convId).label} " +
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
            profile == null -> {
                com.jiuguan.ailink.core.SessionLog.add(
                    "ai",
                    "没有可用的 AI 配置（未绑定 / 已停用 / 旧配置待确认），本次使用内置模拟回复"
                )
                localReply(convId, message.text)
            }

            profile.isLocalOnly ->
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

        val segments = planSegments(convId, reply)
        if (segments.isEmpty()) return 0L
        return deliverSegments(
            convId = convId,
            peerKey = message.peerKey,
            title = message.title,
            isGroup = message.isGroup,
            segments = segments,
            origin = MessageOrigin.AUTO
        )
    }

    /**
     * 决定这一次发几条、每条是什么。
     *
     * 关闭分段时只返回一条，且截断方式与改造前的 `trim().take(600)` **逐字一致**
     * （不做 Markdown 清洗、不做句末对齐），保证「开关全关 = 旧行为」。
     */
    private fun planSegments(convId: String, reply: String): List<String> {
        val config = behavior.multi
        // 关闭分段时 splitWith 内部走短路：trim + take(totalMaxChars)，等价于改造前的 trim().take(600)
        val outcome = SegmentSplitter.splitWith(reply, config)
        val segments = outcome.segments
        // 关闭分段时保持改造前的静默，不新增任何日志
        if (!config.splitting) return segments

        if (segments.isEmpty()) {
            com.jiuguan.ailink.core.SessionLog.add(
                "multi", "模型输出清洗后为空，本轮不发送 peer=$convId"
            )
            return segments
        }
        com.jiuguan.ailink.core.SessionLog.add(
            "multi",
            "分段 ${segments.size} 条 来源=${outcome.source.label} " +
                "各段字数=${segments.joinToString("/") { it.length.toString() }} peer=$convId"
        )

        if (!config.budgetActive) return segments

        val windowMs = config.budgetWindowSec * 1_000L
        val remaining = ledgerOf(convId).remaining(System.currentTimeMillis(), windowMs, config.budgetChars)
        if (remaining == OutputLedger.UNLIMITED) return segments
        if (remaining <= 0) {
            com.jiuguan.ailink.core.SessionLog.add(
                "multi",
                "输出总量已用尽（上限 ${config.budgetChars} 字/${config.budgetWindowSec}s），本轮不发送 peer=$convId"
            )
            return emptyList()
        }
        val capped = SegmentSplitter.capToLimit(segments, remaining)
        if (capped.size < segments.size) {
            com.jiuguan.ailink.core.SessionLog.add(
                "multi",
                "输出总量受限：剩余预算 $remaining 字，${segments.size} 条裁为 ${capped.size} 条 peer=$convId"
            )
        }
        return capped
    }

    /**
     * 逐条发送。某条失败立即中止剩余分段——半句话脱离前文没有意义，
     * 而且连续失败往往意味着连接或风控异常，继续发只会放大伤害。
     *
     * 每条发出前过两道闸：账号小时配额、内容指纹去重。
     *
     * @param origin [MessageOrigin.AUTO]（被动回复）或 [MessageOrigin.PROACTIVE]（主动发起）。
     * @return 最后一条成功发出的时间戳；0 表示一条都没发出去。
     */
    private suspend fun deliverSegments(
        convId: String,
        peerKey: String,
        title: String,
        isGroup: Boolean,
        segments: List<String>,
        origin: String
    ): Long {
        val startedAt = System.currentTimeMillis()
        val label = if (origin == MessageOrigin.PROACTIVE) "主动发起" else "回复"
        var sent = 0
        var lastSentAt = 0L
        var skipped = 0

        for ((index, segment) in segments.withIndex()) {
            // 配额：必须查库。只记内存的话，进程重启就把上限清零了。
            if (!governor.withinHourlyQuota(
                    messageRepository.countSentInWindow(account.id, HOUR_MS)
                )
            ) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "throttle",
                    "账号小时配额已满（上限 ${governor.hourlyQuota} 条/小时），" +
                        "本轮已发 $sent/${segments.size} 条后停止 peer=$peerKey"
                )
                break
            }

            // 去重：同一会话短时间内不重复同一句话（模型偶尔会复读）
            if (governor.isDuplicate(convId, segment)) {
                skipped++
                com.jiuguan.ailink.core.SessionLog.add(
                    "throttle",
                    "内容与最近发过的重复，跳过第 ${index + 1}/${segments.size} 条 peer=$peerKey"
                )
                continue
            }

            var gap = 0L
            if (index > 0) {
                gap = ReplyPacing.gapMs(segments[index - 1], behavior.multi)
                delay(gap)
            }
            val ok = gateway.sendText(peerKey, isGroup, segment)
            val sentAt = System.currentTimeMillis()
            messageRepository.recordOutgoing(
                account = account,
                peerKey = peerKey,
                title = title,
                isGroup = isGroup,
                text = segment,
                success = ok,
                origin = origin,
                createdAt = sentAt
            )
            // 连续失败退避：这是对 sendText 自身错误处理的补充，不是替换
            governor.recordSendResult(ok)
            if (!ok) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "engine",
                    "$label 失败，中止剩余分段 peer=$peerKey group=$isGroup " +
                        "已发 $sent/${segments.size} 条 本条长度=${segment.length} " +
                        "退避状态=${governor.mutedRemainingMs() / 1000}s"
                )
                break
            }
            sent++
            lastSentAt = sentAt
            governor.rememberSent(convId, segment)
            ledgerOf(convId).record(
                sentAt, segment.length, behavior.multi.budgetWindowSec * 1_000L
            )
            com.jiuguan.ailink.core.SessionLog.add(
                "engine",
                "$label 成功 peer=$peerKey group=$isGroup " +
                    "第 ${index + 1}/${segments.size} 条 长度=${segment.length}" +
                    if (gap > 0) " 间隔=${gap}ms" else ""
            )
        }

        if (sent > 0) {
            com.jiuguan.ailink.core.SessionLog.add(
                "engine",
                "$label 完成 peer=$peerKey 共 $sent 条" +
                    (if (skipped > 0) " 跳过重复 $skipped 条" else "") +
                    // 只留首段摘要：完整内容在聊天页与库里，日志留摘要够复盘即可
                    " 内容=${segments.firstOrNull()?.take(LOG_CONTENT_PREVIEW).orEmpty()}" +
                    " 耗时 ${System.currentTimeMillis() - startedAt}ms"
            )
            update { it.copy(replied = it.replied + 1, lastActiveAt = System.currentTimeMillis()) }
        }
        return lastSentAt
    }

    private fun ledgerOf(convId: String): OutputLedger =
        outputLedgers.getOrPut(convId) { OutputLedger() }

    // ---------------------------------------------------------------- 主动发起

    /**
     * 启动主动发起循环。
     *
     * 为什么不用 WorkManager / AlarmManager：
     * - WorkManager 的最小周期是 15 分钟，做「静默 90 分钟后续一句」粒度太粗；
     * - AlarmManager 在国产 ROM 上常年被冻结，频繁唤醒还费电。
     * 用前台服务里的协程循环，生命周期天然跟着服务走——服务停了本来也不该主动发消息。
     */
    private fun startProactive() {
        val config = behavior.proactive
        if (!config.enabled || !account.autoReply) return
        if (proactiveJob != null) return

        proactiveJob = scope.launch {
            // 启动后先等一轮：把刚上线的补拉消息处理完，别一上来就主动开口
            delay(PROACTIVE_STARTUP_DELAY_MS)
            while (isActive) {
                runCatching { proactiveTick() }.onFailure { error ->
                    com.jiuguan.ailink.core.SessionLog.add(
                        "proactive",
                        "调度异常：${error.message ?: error.javaClass.simpleName}"
                    )
                }
                // 加抖动：整点整分准时开口，痕迹太明显
                delay(PROACTIVE_TICK_MS + Random.nextLong(0L, PROACTIVE_JITTER_MS))
            }
        }
        com.jiuguan.ailink.core.SessionLog.add(
            "proactive",
            "已启用 静默=${config.idleMinutes}分钟 定时=[${config.scheduleTimes.ifBlank { "无" }}] " +
                "配额=${config.maxPerHour}/小时 ${config.maxPerDay}/天 " +
                "免打扰=${config.quietStartHour}-${config.quietEndHour}点 " +
                (if (config.allowInGroup) "含群聊" else "仅私聊")
        )
    }

    /** 一轮调度：先算候选，再交给 [ProactivePolicy] 判定，最多发起一次。 */
    private suspend fun proactiveTick() {
        val config = behavior.proactive
        if (!config.enabled || !account.autoReply) return

        val muted = governor.mutedRemainingMs()
        if (muted > 0L) {
            com.jiuguan.ailink.core.SessionLog.add(
                "proactive", "跳过：账号退避中（剩余 ${muted / 1000}s）"
            )
            return
        }

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val minuteOfDay = hourOfDay * 60 + calendar.get(Calendar.MINUTE)
        val dayKey = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        if (dayKey != slotDayKey) {
            slotDayKey = dayKey
            firedSlots.clear()
        }

        // 会话级自动回复关掉的会话，主动也不碰——这是用户明确的"别管这个聊天"
        val conversations = messageRepository.conversationsOf(account.id).filter { it.autoReply }
        if (conversations.isEmpty()) return

        val lastProactive = messageRepository.lastProactiveAtByConversation(account.id)
        val twoWay = messageRepository.twoWayConversations(account.id)
        val candidates = conversations.map { conversation ->
            ProactiveCandidate(
                convId = conversation.id,
                isGroup = conversation.isGroup,
                lastMessageAt = conversation.lastAt,
                lastProactiveAt = lastProactive[conversation.id] ?: 0L,
                twoWay = conversation.id in twoWay
            )
        }

        val decision = ProactivePolicy.decide(
            now = now,
            hourOfDay = hourOfDay,
            minuteOfDay = minuteOfDay,
            dayKey = dayKey,
            config = config,
            candidates = candidates,
            usedThisHour = messageRepository.countSentByOrigin(
                account.id, MessageOrigin.PROACTIVE, HOUR_MS
            ),
            usedToday = messageRepository.countSentByOrigin(
                account.id, MessageOrigin.PROACTIVE, DAY_MS
            ),
            firedSlots = firedSlots
        )

        when (decision) {
            is ProactiveOutcome.Skip -> {
                // 十分钟最多记一次：既能事后回答「为什么一整天没主动说话」，又不至于每分钟刷屏
                if (now - lastSkipLogAt >= SKIP_LOG_INTERVAL_MS) {
                    lastSkipLogAt = now
                    com.jiuguan.ailink.core.SessionLog.add(
                        "proactive", "未发起：${decision.reason}"
                    )
                }
            }

            is ProactiveOutcome.Fire -> {
                if (decision.decision.slotKey.isNotEmpty()) firedSlots += decision.decision.slotKey
                val target = conversations.firstOrNull { it.id == decision.decision.convId }
                    ?: return
                fireProactive(target, decision.decision)
            }
        }
    }

    /** 真正发一次。生成失败、内容为空或过短都放弃，不发半成品。 */
    private suspend fun fireProactive(
        conversation: ConversationEntity,
        decision: ProactiveDecision
    ) {
        val convId = conversation.id
        val config = behavior.proactive

        mutexOf(convId).withLock {
            // 排队期间对方可能已经说话了。这时再主动开口，就会撞在人家的话后面。
            val latest = messageRepository.findConversation(convId)
            val silenceMs = System.currentTimeMillis() - (latest?.lastAt ?: 0L)
            val minSilence = if (decision.kind == ProactiveKind.SCHEDULED) {
                SCHEDULED_MIN_SILENCE_MS
            } else {
                config.idleMinutes * 60_000L
            }
            if (latest != null && silenceMs < minSilence) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "proactive",
                    "跳过：对方 ${silenceMs / 1000}s 前刚说过话 peer=${conversation.peerKey}"
                )
                return@withLock
            }

            val text = composeProactive(conversation, decision)
            if (text.isNullOrBlank()) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "proactive", "放弃：生成内容为空 peer=${conversation.peerKey}"
                )
                return@withLock
            }
            if (text.length < MIN_PROACTIVE_CHARS) {
                com.jiuguan.ailink.core.SessionLog.add(
                    "proactive",
                    "放弃：生成内容过短（${text.length} 字）peer=${conversation.peerKey} 内容=$text"
                )
                return@withLock
            }

            val segments = planSegments(convId, text)
            if (segments.isEmpty()) return@withLock

            val sentAt = deliverSegments(
                convId = convId,
                peerKey = conversation.peerKey,
                title = conversation.title,
                isGroup = conversation.isGroup,
                segments = segments,
                origin = MessageOrigin.PROACTIVE
            )
            // 主动说完也进冷却：紧接着对方回话时不该马上又触发一条
            governor.finishTurn(convId, sentAt)

            com.jiuguan.ailink.core.SessionLog.add(
                "proactive",
                "触发 原因=${decision.reason} 类型=${decision.kind.label} " +
                    "group=${conversation.isGroup} peer=${conversation.peerKey} " +
                    "结果=${if (sentAt > 0L) "成功 ${segments.size} 段" else "失败"} " +
                    "内容=${text.take(30)}"
            )
        }
    }

    /**
     * 生成主动消息的内容。
     *
     * 用与被动回复不同的提示词：被动是"回应对方"，主动是"自己起话头"，两者的分寸不一样。
     * 上下文只带最近几条——主动开口不需要把整段历史塞进去，也更容易跑偏。
     */
    private suspend fun composeProactive(
        conversation: ConversationEntity,
        decision: ProactiveDecision
    ): String? {
        val profile = aiRepository.resolve(account.aiProfileId)
        if (profile == null || profile.isLocalOnly) {
            // 没有可用的外部模型时用内置文案：让这条链路在演示账号上也能验收
            com.jiuguan.ailink.core.SessionLog.add(
                "proactive", "无可用外部 AI，改用内置兜底文案"
            )
            return LocalResponder.proactive(decision.kind == ProactiveKind.SCHEDULED)
        }

        val history = messageRepository.recentForContext(conversation.id, PROACTIVE_CONTEXT_SIZE)
            .filter { !it.isSystem && it.content.isNotBlank() }
            .map { entity ->
                ChatTurn(
                    role = if (entity.isOutgoing) "assistant" else "user",
                    content = entity.content
                )
            }

        val turns = buildList {
            add(ChatTurn("system", proactivePrompt(profile, decision)))
            addAll(history)
            add(ChatTurn("user", PROACTIVE_NUDGE))
        }
        return aiClient.complete(profile, turns).fold(
            onSuccess = { it.trim() },
            onFailure = { error ->
                com.jiuguan.ailink.core.SessionLog.add(
                    "proactive",
                    "AI 调用失败：${error.message ?: error.javaClass.simpleName}"
                )
                null
            }
        )
    }

    private fun proactivePrompt(
        profile: AiProfileEntity,
        decision: ProactiveDecision
    ): String = buildString {
        append(systemPrompt(profile))
        append("\n\n")
        when (decision.kind) {
            ProactiveKind.SCHEDULED -> {
                val custom = behavior.proactive.schedulePrompt.trim()
                if (custom.isNotEmpty()) {
                    append(custom)
                } else {
                    append("到了平时聊天的时段，主动开口说一句。")
                }
            }

            ProactiveKind.IDLE ->
                append("对方已经有一阵子没说话了，接着上面的话题自然地续一句，像是忽然想起一件事。")
        }
        append("\n要求：不要解释你为什么突然开口，不要用「在吗」这类空话开头，不要重复你之前说过的内容。")
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
        if (behavior.multi.splitting) {
            // 打开分段后不再强制「1-3 句」：长一点的表达更有人情味，
            // 长度交给内容本身和「单位时间输出总量」去约束，而不是提示词硬压。
            append(
                "你正在通过「${account.platformType.label}」与对方聊天。" +
                    "要求：口语化、长度随内容自然变化（有时一句，有时可以多说几句）、" +
                    "不使用 Markdown 语法、不要提及自己是 AI 或机器人。"
            )
            append(
                "如需分成多条消息发送，用单独一行的 ### 把每条隔开（最多 ${behavior.multi.maxSegments} 条）；" +
                    "多数情况一条就够，不必强行拆分。"
            )
        } else {
            append(
                "你正在通过「${account.platformType.label}」与对方聊天。" +
                    "要求：口语化、简短（1-3 句）、不使用 Markdown 语法、不要提及自己是 AI 或机器人。"
            )
        }
    }

    /**
     * 原子地更新运行态。
     *
     * 消息现在由多个协程并发处理（不同会话互不串行），`读-改-写` 必须走 CAS，
     * 否则 `received` / `replied` 计数会互相覆盖丢数。
     */
    private fun update(transform: (RuntimeState) -> RuntimeState) {
        _state.update(transform)
        onStateChanged(_state.value)
    }

    private companion object {
        const val STALE_WINDOW_MS = 10 * 60_000L
        const val CONTEXT_SIZE = 8

        /** 账号小时配额统计窗口。 */
        const val HOUR_MS = 60 * 60_000L

        /** 主动发起的每日配额统计窗口。 */
        const val DAY_MS = 24 * 60 * 60_000L

        /** 主动发起的轮询周期与抖动；加抖动是为了不让多个账号在同一秒开口。 */
        const val PROACTIVE_TICK_MS = 60_000L
        const val PROACTIVE_JITTER_MS = 15_000L

        /** 启动后先让被动回复跑一轮，再开始考虑主动开口。 */
        const val PROACTIVE_STARTUP_DELAY_MS = 90_000L

        /** 主动发起时的上下文轮数：一开口就要翻完整段历史，容易跑题。 */
        const val PROACTIVE_CONTEXT_SIZE = 4

        /** 短于这个长度的生成结果直接丢弃（"嗯"这种开场没有意义）。 */
        const val MIN_PROACTIVE_CHARS = 4

        /** 日志里保留的内容摘要字数。够复盘，又不至于把日志撑成聊天记录副本。 */
        const val LOG_CONTENT_PREVIEW = 30

        /** 定时打招呼前对方至少要安静这么久，避免和正在进行的话题撞车。 */
        const val SCHEDULED_MIN_SILENCE_MS = 5 * 60_000L

        /** 「未发起」日志的最小间隔：主动发起是"没动静"最难排查的能力，但不能每分钟刷一条。 */
        const val SKIP_LOG_INTERVAL_MS = 10 * 60_000L

        /** 主动发起时追加在末尾的用户轮：明确告诉模型「现在该你开口了」。 */
        const val PROACTIVE_NUDGE = "（现在轮到你主动给对方发消息，接着说一句即可。）"

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
