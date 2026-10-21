package com.jiuguan.ailink.data.gateway.demo

import com.jiuguan.ailink.data.gateway.GatewayEvent
import com.jiuguan.ailink.data.gateway.IncomingMessage
import com.jiuguan.ailink.data.gateway.MessageGateway
import com.jiuguan.ailink.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 本地演示接入：不连接任何外部服务，按固定脚本周期性产生消息，
 * 用于在没有 QQ/微信 桥接端的情况下验证收发、自动回复与消息管理。
 */
class DemoGateway(
    private val platform: Platform,
    private val scope: CoroutineScope
) : MessageGateway {

    private val _events = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private var job: Job? = null

    @Volatile
    private var running = false

    private val selfId = Random.nextLong(200_000_000L, 999_999_999L)

    private val contacts = listOf(
        DemoContact("p:20001", "林小满", false),
        DemoContact("p:20002", "陈默", false),
        DemoContact("g:90001", "产品评审群", true),
        DemoContact("p:20003", "阿岚", false)
    )

    private val lines = mapOf(
        "p:20001" to listOf(
            "在吗？",
            "下午的评审你还参加吗",
            "我这边把方案改了一版，等下发你",
            "你觉得先做小范围灰度可以吗",
            "灰度我先拉 5% 的量试试",
            "对了，昨天那个数据你看到了吗",
            "我晚点把结论同步到文档里"
        ),
        "p:20002" to listOf(
            "刚下班，今天累死了",
            "晚上吃什么？",
            "那家新开的面馆要不要去试试",
            "今天开会开到六点半，人都木了",
            "周末要不要出去走走",
            "我先去冲个澡，等下聊"
        ),
        "g:90001" to listOf(
            "@你 新版首页的稿子什么时候能给一下",
            "这个需求要不要排进本周迭代",
            "@你 帮忙确认下接口字段",
            "我来整理一下时间点",
            "测试环境刚更新了，大家帮忙回归一下",
            "@你 这个交互按之前的方案走可以吗",
            "好的，那我先按这个推进"
        ),
        "p:20003" to listOf(
            "谢谢你上次帮忙～",
            "改天请你喝咖啡",
            "周末有空一起去看展吗",
            "那个展好像到月底就结束了",
            "我先去买票，回头把时间发你",
            "刚看到你发的图，挺好看的"
        )
    )

    override fun start() {
        if (running) return
        running = true
        job = scope.launch {
            _events.emit(GatewayEvent.Status(online = false, connecting = true))
            delay(700L)
            _events.emit(GatewayEvent.Status(online = true, selfId = selfId, latencyMs = 18L))

            var cursor = 0
            val lastIndex = mutableMapOf<String, Int>()
            while (isActive && running) {
                delay(Random.nextLong(9_000L, 18_000L))
                val contact = contacts[cursor % contacts.size]
                val pool = lines[contact.peerKey].orEmpty()
                if (pool.isEmpty()) continue
                // 避免同一联系人连续两次收到同一句话
                var index = Random.nextInt(pool.size)
                if (pool.size > 1 && index == lastIndex[contact.peerKey]) {
                    index = (index + 1) % pool.size
                }
                lastIndex[contact.peerKey] = index
                val text = pool[index]
                cursor += 1

                _events.emit(
                    GatewayEvent.Incoming(
                        IncomingMessage(
                            peerKey = contact.peerKey,
                            title = contact.name,
                            isGroup = contact.isGroup,
                            senderId = contact.peerKey.substringAfter(':').toLongOrNull() ?: 0L,
                            senderName = if (contact.isGroup) "群成员" else contact.name,
                            text = text,
                            atMe = contact.isGroup,
                            platformMsgId = "demo-${System.currentTimeMillis()}",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                )
            }
        }
    }

    override fun stop() {
        running = false
        job?.cancel()
        job = null
        _events.tryEmit(GatewayEvent.Status(online = false))
    }

    override suspend fun sendText(peerKey: String, isGroup: Boolean, text: String): Boolean {
        if (!running) return false
        delay(280L)
        return true
    }

    private data class DemoContact(
        val peerKey: String,
        val name: String,
        val isGroup: Boolean
    )

    val platformLabel: String get() = platform.label
}
