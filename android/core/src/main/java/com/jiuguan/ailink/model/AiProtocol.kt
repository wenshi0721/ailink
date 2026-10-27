package com.jiuguan.ailink.model

/**
 * AI 接口协议类型。
 *
 * 应用只实现 OpenAI Chat Completions 兼容协议，但把「协议」抽成独立维度：
 * 新增厂商协议时只需在 data 层增加一个 AiProvider 实现，UI 与配置存储无需改动。
 *
 * [CUSTOM] 与 [OPENAI_COMPAT] 共用同一套请求体，区别只在路径由用户显式填写，
 * 用于自建网关、反向代理或路径不规律的兼容服务。
 */
enum class AiProtocol(
    val label: String,
    /** 留空表示必须由用户显式填写接口路径。 */
    val defaultPath: String,
    val hint: String
) {
    OPENAI_COMPAT(
        label = "OpenAI 兼容",
        defaultPath = "/chat/completions",
        hint = "DeepSeek / 通义 / Kimi / 智谱 / 豆包 / Ollama 等均适用；地址填到 /v1 即可"
    ),
    CUSTOM(
        label = "自定义路径",
        defaultPath = "",
        hint = "仍使用 OpenAI 请求体，但接口路径与认证方式完全由你填写，适合自建网关"
    );

    val implemented: Boolean get() = true
}

/** 认证方式：决定 API Key 放在哪个请求头。 */
enum class AiAuthStyle(val label: String, val hint: String) {
    BEARER("Authorization: Bearer", "最常见，绝大多数兼容服务使用"),
    X_API_KEY("x-api-key", "部分网关或 Anthropic 风格转发要求"),
    NONE("不发送密钥", "本地 Ollama 等无需鉴权的服务");

    val requiresKey: Boolean get() = this != NONE
}

fun protocolOf(value: String?): AiProtocol =
    AiProtocol.entries.firstOrNull { it.name == value } ?: AiProtocol.OPENAI_COMPAT

fun authStyleOf(value: String?): AiAuthStyle =
    AiAuthStyle.entries.firstOrNull { it.name == value } ?: AiAuthStyle.BEARER
