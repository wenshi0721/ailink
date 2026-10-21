package com.jiuguan.ailink.data.gateway.onebot

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

/**
 * 兼容两种消息格式：消息段数组与 CQ 码字符串，返回（纯文本, 是否 @ 了自己）。
 *
 * @param message v11 的 `message` 字段：数组 = 消息段，字符串 = CQ 码；其他形态忽略
 * @param sid 自己的 QQ 号，用于判断 at 对象是不是自己
 * @param raw `raw_message` 兜底文本，仅在 [message] 解析不出内容时使用
 */
internal fun flattenMessage(message: JsonElement?, sid: Long, raw: String): Pair<String, Boolean> {
    var atMe = false
    val builder = StringBuilder()

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
                    "face" -> builder.append("[表情]")
                    "image" -> builder.append("[图片]")
                    "record" -> builder.append("[语音]")
                    "video" -> builder.append("[视频]")
                    "file" -> builder.append("[文件]")
                }
            }
        }

        is JsonPrimitive -> {
            val cq = runCatching { message.content }.getOrDefault("")
            atMe = cq.contains("[CQ:at,qq=$sid]")
            builder.append(cq.replace(CQ_CODE, " "))
        }

        else -> Unit
    }

    if (builder.isBlank() && raw.isNotBlank()) {
        atMe = raw.contains("[CQ:at,qq=$sid]")
        builder.append(raw.replace(CQ_CODE, " "))
    }

    return builder.toString().trim() to atMe
}
