package com.jiuguan.ailink.data.gateway

import kotlinx.coroutines.flow.SharedFlow

/** 接入层统一的上行消息模型。 */
data class IncomingMessage(
    val peerKey: String,
    val title: String,
    val isGroup: Boolean,
    val senderId: Long,
    val senderName: String,
    val text: String,
    val atMe: Boolean,
    val platformMsgId: String,
    val timestamp: Long
)

/** 接入层事件。 */
sealed interface GatewayEvent {
    data class Incoming(val message: IncomingMessage) : GatewayEvent

    data class Status(
        val online: Boolean,
        val connecting: Boolean = false,
        val selfId: Long = 0L,
        val latencyMs: Long = -1L,
        val error: String = ""
    ) : GatewayEvent
}

/**
 * 消息网关抽象。新增接入方式（例如官方机器人、企业微信回调）时
 * 只需实现该接口并在 [GatewayFactory] 中注册。
 */
interface MessageGateway {

    val events: SharedFlow<GatewayEvent>

    /** 启动并维持连接（内部负责重连）。 */
    fun start()

    /** 停止连接并释放资源。 */
    fun stop()

    /** 发送文本消息，返回是否成功提交到接入端。 */
    suspend fun sendText(peerKey: String, isGroup: Boolean, text: String): Boolean
}
