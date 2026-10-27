package com.jiuguan.ailink.data.ai

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 一次「测试连接」的结构化结论，成功与失败都能直接展示给用户。 */
data class AiTestResult(
    val ok: Boolean,
    /** 一句话结论，用于状态标签与列表摘要。 */
    val summary: String,
    /** 失败原因与修改建议；成功时可为空。 */
    val detail: String = "",
    /** 模型返回的关键内容（截断），成功时展示。 */
    val reply: String = "",
    val latencyMs: Long = 0L,
    val httpCode: Int = 0
) {
    companion object {
        fun failure(summary: String, detail: String = "") =
            AiTestResult(ok = false, summary = summary, detail = detail)
    }
}

/**
 * 网络错误的统一翻译。
 *
 * 目标不是复述异常，而是回答三个问题：哪里错、为什么错、怎么改。
 * 所有文案均为「原因 + 操作建议」，避免只弹一个 HTTP 状态码。
 */
object AiErrors {

    fun fromHttp(code: Int, serverMessage: String, baseUrl: String): AiTestResult {
        val suffix = if (serverMessage.isBlank()) "" else "服务端返回：$serverMessage"
        val (summary, advice) = when (code) {
            400 -> "请求被拒绝（HTTP 400）" to
                "常见原因：模型名不存在、参数不被支持、请求体格式不符。请核对「模型名称」与服务商文档。"
            401 -> "密钥无效或无权限（HTTP 401）" to
                "请确认 API Key 完整、未过期，且与当前 Base URL 属于同一服务商；部分网关要求切换认证方式。"
            403 -> "访问被拒绝（HTTP 403）" to
                "密钥可能没有该模型权限，或账户被限制。请到服务商控制台检查权限与实名/额度状态。"
            404 -> "接口路径不存在（HTTP 404）" to
                "请检查 Base URL 与「接口路径」：多数兼容服务需要以 /v1 结尾；自建网关请显式填写完整路径。"
            408 -> "请求超时（HTTP 408）" to "服务端等待超时，稍后重试或调大「请求超时」。"
            413 -> "请求体过大（HTTP 413）" to "图片或上下文超出服务端限制，可关闭识图或减少单次最大 Token。"
            429 -> "请求过于频繁或额度不足（HTTP 429）" to
                "请稍后重试；若持续出现，检查账户余额、并发限制与限流策略。"
            in 500..599 -> "服务端错误（HTTP $code）" to
                "问题在服务商一侧，稍后重试；若持续失败可切换其他 API 配置。"
            else -> "调用失败（HTTP $code）" to "请对照服务商文档检查地址、密钥与模型名。"
        }
        return AiTestResult.failure(
            summary = summary,
            detail = listOf(advice, suffix, "当前地址：${AiEndpoint.resolve(baseUrl)}")
                .filter { it.isNotBlank() }
                .joinToString("\n")
        )
    }

    fun fromThrowable(error: Throwable, baseUrl: String): AiTestResult {
        val host = baseUrl.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val (summary, advice) = when (error) {
            is UnknownHostException -> "域名解析失败：$host" to
                "检查地址拼写与网络连接；如果地址是内网服务，确认手机与服务器在同一网络。"
            is ConnectException -> "无法连接到服务器" to
                "检查端口是否正确、服务是否已启动、防火墙是否放行；手机访问电脑请使用局域网 IP。"
            is SocketTimeoutException -> "请求超时" to
                "网络较慢或模型响应过久：可调大「请求超时」，或换用响应更快的模型。"
            is SSLException -> "HTTPS 证书校验失败" to
                "确认地址使用 https 且证书有效；自签名证书的服务建议改用 http 或受信任的网关。"
            else -> "调用失败：${error.message ?: error.javaClass.simpleName}" to
                "请检查网络、地址与密钥；详细原因已写入会话日志。"
        }
        return AiTestResult.failure(
            summary = summary,
            detail = if (baseUrl.isBlank()) advice else "$advice\n当前地址：${AiEndpoint.resolve(baseUrl)}"
        )
    }
}
