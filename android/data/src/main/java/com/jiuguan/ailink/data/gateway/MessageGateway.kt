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
    val timestamp: Long,
    /** 本条消息附带的图片（顺序与消息段一致）；是否真的送进模型由识图能力决定。 */
    val images: List<IncomingImage> = emptyList(),
    /**
     * 被引用消息的平台消息 id（OneBot `reply` 段的 `id`）。
     * 非空表示这条消息是"引用/回复"别人某条消息，用来还原被引用的文本与图片。
     */
    val replyToId: String = ""
)

/**
 * 一条消息里的图片引用（对应 OneBot image 段）。
 *
 * 只有 [url] 可下载；[file] 是桥接端内部文件名，仅用于日志排查。
 * 表情包与聊天图片都会走到这里，格式可能是 GIF/WebP/PNG/JPEG，统一在 `ImagePrep` 里转码。
 */
data class IncomingImage(
    val url: String = "",
    val file: String = "",
    val summary: String = ""
) {
    val isUsable: Boolean get() = url.isNotBlank()
}

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

    /**
     * 图片地址过期后，用桥接端的内部文件名换取一个新的可下载地址。
     *
     * QQ 图床链接带 rkey 鉴权会失效，引用回复或回看旧图时需要重新换一次。
     * 返回 null 表示该桥接端不支持（调用方降级为纯文本）。
     */
    suspend fun refreshImageUrl(file: String, url: String): String? = null
}
