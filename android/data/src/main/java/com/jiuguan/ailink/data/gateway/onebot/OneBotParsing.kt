package com.jiuguan.ailink.data.gateway.onebot

import com.jiuguan.ailink.data.gateway.IncomingImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * OneBot v11 帧的纯函数解析逻辑。
 *
 * 从 [OneBotGateway] 里抽出来的唯一目的是**可单元测试**：网络栈（OkHttp / WebSocket）
 * 无法在 JVM 单测里跑，而消息解析是最容易出错、也最需要回归保护的部分。
 * 抽出来后行为完全不变，只是把 `private` 改成 `internal`。
 */

/** 读字符串字段，缺失或类型不符时回退空串。 */
internal fun JsonObject.jStr(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()

/** 读整数字段（OneBot 常用字符串形式传大整数），失败回退 0。 */
internal fun JsonObject.jLong(key: String): Long =
    runCatching { this[key]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull() ?: 0L

private val CQ_CODE = Regex("\\[CQ:[^]]*]")
private val CQ_IMAGE = Regex("\\[CQ:image,([^\\]]*)]")
private val CQ_REPLY = Regex("\\[CQ:reply,([^\\]]*)]")

/** 一条入站消息的解析结果：文本、是否 @ 自己、附带的图片、被引用的消息 id。 */
internal data class ParsedMessage(
    val text: String,
    val atMe: Boolean,
    val images: List<IncomingImage> = emptyList(),
    val replyToId: String = ""
)

/**
 * 兼容两种消息格式：消息段数组与 CQ 码字符串。
 *
 * @param message v11 的 `message` 字段：数组 = 消息段，字符串 = CQ 码；其他形态忽略
 * @param sid 自己的 QQ 号，用于判断 at 对象是不是自己
 * @param raw `raw_message` 兜底文本，仅在 [message] 解析不出内容时使用
 */
internal fun parseMessage(message: JsonElement?, sid: Long, raw: String): ParsedMessage {
    var atMe = false
    var replyToId = ""
    val builder = StringBuilder()
    val images = mutableListOf<IncomingImage>()

    when (message) {
        is JsonArray -> {
            for (element in message) {
                val segment = element as? JsonObject ?: continue
                val data = segment["data"] as? JsonObject
                when (segment.jStr("type")) {
                    "text" -> builder.append(data?.jStr("text").orEmpty())
                    "at" -> {
                        val qq = data?.jStr("qq").orEmpty()
                        if (qq.isNotBlank() && qq == sid.toString()) atMe = true
                        builder.append(' ')
                    }
                    // 引用/回复段：只取被引用消息的 id，正文与图片由上层按 id 回查
                    "reply" -> {
                        replyToId = data?.jStr("id").orEmpty()
                    }
                    "face" -> builder.append("[表情]")
                    "image" -> {
                        builder.append("[图片]")
                        val image = data?.let {
                            IncomingImage(
                                url = it.jStr("url"),
                                file = it.jStr("file"),
                                summary = it.jStr("summary")
                            )
                        }
                        if (image != null && (image.url.isNotBlank() || image.file.isNotBlank())) {
                            images += image
                        }
                    }
                    "record" -> builder.append("[语音]")
                    "video" -> builder.append("[视频]")
                    "file" -> builder.append("[文件]")
                }
            }
        }

        is JsonPrimitive -> {
            val cq = runCatching { message.content }.getOrDefault("")
            atMe = cq.contains("[CQ:at,qq=$sid]")
            images += parseCqImages(cq)
            replyToId = parseCqReplyId(cq)
            builder.append(cq.replace(CQ_CODE, " "))
        }

        else -> Unit
    }

    if (builder.isBlank() && raw.isNotBlank()) {
        atMe = atMe || raw.contains("[CQ:at,qq=$sid]")
        if (images.isEmpty()) images += parseCqImages(raw)
        if (replyToId.isBlank()) replyToId = parseCqReplyId(raw)
        builder.append(raw.replace(CQ_CODE, " "))
    }

    val text = builder.toString().trim().ifBlank {
        // 纯图片消息原本会被上层当成空文本丢弃，这里补一个占位符，让识图链路有机会接管。
        if (images.isNotEmpty()) "[图片]" else ""
    }
    return ParsedMessage(text = text, atMe = atMe, images = images, replyToId = replyToId)
}

/**
 * 兼容旧调用与既有单测：只关心文本与 at 标记的路径继续用这个签名。
 */
internal fun flattenMessage(message: JsonElement?, sid: Long, raw: String): Pair<String, Boolean> {
    val parsed = parseMessage(message, sid, raw)
    return parsed.text to parsed.atMe
}

/** 从 CQ 码里提取图片段，参数形如 `file=x.png,url=https://...`。 */
private fun parseCqImages(cq: String): List<IncomingImage> =
    CQ_IMAGE.findAll(cq).mapNotNull { match ->
        val params = match.groupValues[1]
            .split(',')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
            }
            .toMap()
        val image = IncomingImage(
            url = params["url"].orEmpty(),
            file = params["file"].orEmpty(),
            summary = params["summary"].orEmpty()
        )
        if (image.url.isBlank() && image.file.isBlank()) null else image
    }.toList()

/** 从 CQ 码里提取被引用消息 id，形如 `[CQ:reply,id=-1234567890]`。 */
private fun parseCqReplyId(cq: String): String {
    val match = CQ_REPLY.find(cq) ?: return ""
    return match.groupValues[1]
        .split(',')
        .firstOrNull { it.startsWith("id=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
}
