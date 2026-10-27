package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.core.SessionLog
import com.jiuguan.ailink.data.ai.provider.AiProvider
import com.jiuguan.ailink.data.ai.provider.AiProviderRegistry
import com.jiuguan.ailink.data.ai.provider.AiReply
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.VisionSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 一轮对话。[images] 是该轮附带的图片 data URL。
 *
 * 为什么按轮存：图片原先只能挂在"最后一条 user"上，追问就再也看不到图。
 * 现在每一轮各自带图，由 Provider 组装内容块数组。
 */
data class ChatTurn(
    val role: String,
    val content: String,
    val images: List<String> = emptyList()
)

/** 服务端明确返回非 2xx 时抛出，携带状态码与解析后的 message。 */
private class AiHttpException(val code: Int, val serverMessage: String) :
    IllegalStateException("HTTP $code $serverMessage")

/**
 * AI 调用入口。
 *
 * 职责边界：
 * - 校验配置、选择 Provider、统一超时与重试策略；
 * - 不拼接任何厂商地址、不读取任何硬编码密钥；
 * - 协议差异全部委托给 [AiProvider]。
 */
class AiClient {

    /**
     * OkHttp 客户端按「读超时」缓存：每份配置的超时不同，但相同超时复用连接池。
     * 不设置 pingInterval——它不是 OneBot 连接，但保持与 OneBot 侧一致的克制。
     */
    private val clients = ConcurrentHashMap<Int, OkHttpClient>()

    private fun clientFor(readTimeoutSec: Int): OkHttpClient {
        val timeout = readTimeoutSec.coerceIn(5, 300)
        return clients.getOrPut(timeout) {
            OkHttpClient.Builder()
                .connectTimeout(minOf(15, timeout).toLong(), TimeUnit.SECONDS)
                .writeTimeout(minOf(30, timeout).toLong(), TimeUnit.SECONDS)
                .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    /** 解析配置对应的 Provider；协议未注册时返回可展示的错误。 */
    private fun providerFor(profile: AiProfileEntity): AiProvider =
        AiProviderRegistry.of(profile.protocolType)
            ?: error("暂不支持「${profile.protocolType.label}」协议，请改用 OpenAI 兼容配置")

    /**
     * 配置校验。返回 null 表示可发起请求；否则为面向用户的具体原因。
     *
     * 所有校验集中在调用层，避免 UI 与运行时各写一套规则。
     */
    fun validate(profile: AiProfileEntity): String? {
        AiEndpoint.validateBaseUrl(profile.baseUrl)?.let { return it }
        AiEndpoint.validateChatPath(profile.chatPath)?.let { return it }
        if (profile.protocolType == com.jiuguan.ailink.model.AiProtocol.CUSTOM &&
            profile.chatPath.isBlank()
        ) {
            return "已选择「自定义路径」，请填写接口路径（例如 /chat/completions）"
        }
        if (profile.model.isBlank()) return "请填写模型名称，例如 deepseek-flash / gpt-4o-mini / qwen-plus"
        val parsed = AiHeaders.parse(profile.extraHeaders)
        if (!parsed.valid) return "自定义请求头有误：${parsed.errors.first()}"
        if (profile.auth.requiresKey && profile.apiKey.isBlank()) {
            return "未填写 API Key。若该服务无需鉴权，请把「认证方式」改为「不发送密钥」"
        }
        if (AiProviderRegistry.of(profile.protocolType) == null) {
            return "协议「${profile.protocolType.label}」尚未实现，请选择 OpenAI 兼容"
        }
        return null
    }

    suspend fun complete(
        profile: AiProfileEntity,
        turns: List<ChatTurn>,
        maxTokens: Int = profile.maxTokens,
        /**
         * 附图（data URL）。只会追加到最后一条 user 消息上：
         * 历史轮次保持纯文本，避免每轮重复上传 base64 导致 token 与耗时膨胀。
         */
        imageDataUrls: List<String> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(profile.baseUrl.isNotBlank()) { "未配置 AI 接口地址" }
            require(turns.isNotEmpty()) { "对话内容为空" }
            validate(profile)?.let { error(it) }

            var budget = maxTokens.coerceIn(32, MAX_TOKENS_CAP)
            var retried = false
            var text = ""
            while (text.isEmpty()) {
                val reply = requestWithRetry(profile, turns, imageDataUrls, budget)
                if (reply.text.isNotBlank()) {
                    text = reply.text.trim()
                    break
                }

                // 空内容最常见的原因不是模型坏了，而是「额度被推理过程吃光」：
                // 思考型模型先写 reasoning_content，余额才给 content。
                SessionLog.add(
                    "ai",
                    "回复为空：finish_reason=${reply.finishReason.ifBlank { "无" }} " +
                        "completion_tokens=${reply.completionTokens} " +
                        "含推理内容=${reply.hasReasoning} max_tokens=$budget"
                )
                val truncated = reply.finishReason == "length"
                if (!truncated || retried || budget >= MAX_TOKENS_CAP) {
                    error(
                        "模型返回内容为空（finish_reason=${reply.finishReason.ifBlank { "无" }}，max_tokens=$budget）" +
                            if (reply.hasReasoning || truncated) {
                                "；额度被推理内容占满，请调大配置页的「单次最大 Token」"
                            } else {
                                ""
                            }
                    )
                }
                retried = true
                budget = (budget * 4).coerceAtMost(MAX_TOKENS_CAP)
                SessionLog.add("ai", "按 max_tokens=$budget 重试一次")
            }
            text
        }
    }

    /** 测试连接：返回结构化结论，成功时带上关键返回信息。 */
    suspend fun test(profile: AiProfileEntity): AiTestResult = withContext(Dispatchers.IO) {
        if (profile.isLocalOnly) {
            return@withContext AiTestResult(
                ok = false,
                summary = "当前为内置模拟回复",
                detail = "接口地址为空时不调用外部服务，无需测试。填写 Base URL 与模型后可测试。"
            )
        }
        validate(profile)?.let { reason ->
            return@withContext AiTestResult.failure("配置不完整", reason)
        }

        val startedAt = System.nanoTime()
        runCatching {
            requestWithRetry(
                profile = profile,
                turns = listOf(ChatTurn("user", "ping，请只回复 ok")),
                imageDataUrls = emptyList(),
                maxTokens = 32
            )
        }.fold(
            onSuccess = { reply ->
                val cost = (System.nanoTime() - startedAt) / 1_000_000L
                AiTestResult(
                    ok = true,
                    summary = "连接成功（${cost} ms）",
                    detail = buildString {
                        appendLine("地址：${AiEndpoint.resolve(profile.baseUrl, profile.chatPath)}")
                        appendLine("模型：${profile.model}")
                        appendLine("认证：${profile.auth.label}")
                    }.trim(),
                    reply = reply.text.trim().take(80),
                    latencyMs = cost,
                    httpCode = 200
                )
            },
            onFailure = { error ->
                SessionLog.add("ai", "测试失败：${error.message ?: error.javaClass.simpleName}")
                when (error) {
                    is AiHttpException -> AiErrors.fromHttp(error.code, error.serverMessage, profile.baseUrl)
                    else -> AiErrors.fromThrowable(error, profile.baseUrl)
                }
            }
        )
    }

    /**
     * 用一张内置的 32×32 纯色小图探测接口是否接受多模态内容块。
     *
     * 判定策略偏保守：
     * - 2xx 且能解析出回复 → SUPPORTED；
     * - 400/415/422 且响应明确指向图片/内容格式 → UNSUPPORTED；
     * - 其余（鉴权、限流、模型不存在、网络错误）→ UNKNOWN，交由调用方按「不支持」降级，
     *   且不写缓存，下次再探。
     */
    suspend fun probeVision(profile: AiProfileEntity): VisionSupport = withContext(Dispatchers.IO) {
        if (profile.baseUrl.isBlank() || !profile.enabled) return@withContext VisionSupport.UNKNOWN
        val provider = AiProviderRegistry.of(profile.protocolType) ?: return@withContext VisionSupport.UNKNOWN
        val payload = provider.buildVisionProbePayload(profile)
        val request = provider.buildRequest(profile, payload)

        try {
            // 探测只关心状态码，不需要等模型说完；但必须小于调用方的 15 秒总预算。
            clientFor(minOf(profile.timeoutSec, 8)).newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> VisionSupport.SUPPORTED
                    response.code in VISION_REJECT_CODES && looksLikeImageRejection(body) ->
                        VisionSupport.UNSUPPORTED
                    else -> VisionSupport.UNKNOWN
                }
            }
        } catch (e: Exception) {
            SessionLog.add("vision", "识图探测失败：${e.message ?: e.javaClass.simpleName}")
            VisionSupport.UNKNOWN
        }
    }

    /** 一次请求 + 按配置重试。 */
    private suspend fun requestWithRetry(
        profile: AiProfileEntity,
        turns: List<ChatTurn>,
        imageDataUrls: List<String>,
        maxTokens: Int
    ): AiReply {
        val provider = providerFor(profile)
        val payload = provider.buildChatPayload(profile, turns, imageDataUrls, maxTokens)
        val request = provider.buildRequest(profile, payload)
        val maxAttempts = profile.maxRetries.coerceIn(0, MAX_RETRIES) + 1
        var attempt = 1
        while (true) {
            try {
                clientFor(profile.timeoutSec).newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw AiHttpException(response.code, provider.parseServerError(body))
                    }
                    return provider.parseReply(body)
                }
            } catch (e: Exception) {
                val retryable = e is IOException ||
                    (e is AiHttpException && (e.code == 408 || e.code == 429 || e.code in 500..599))
                if (!retryable || attempt >= maxAttempts) throw e
                SessionLog.add("ai", "第 $attempt 次请求失败（${e.message}），${RETRY_BACKOFF_MS * attempt} ms 后重试")
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    /** 错误体里出现这些词，说明是「不认识图片内容块」而不是额度/鉴权问题。 */
    private fun looksLikeImageRejection(body: String): Boolean {
        val lower = body.lowercase()
        return IMAGE_REJECTION_HINTS.any { lower.contains(it) }
    }

    private companion object {
        /** max_tokens 上限：重试时最多放大到这个值。 */
        const val MAX_TOKENS_CAP = 4096

        /** 重试次数上限，避免配置失误时无限放大请求量。 */
        const val MAX_RETRIES = 3

        const val RETRY_BACKOFF_MS = 300L

        /** 明确表示「请求体格式不被接受」的状态码；鉴权/限流/5xx 不在其中。 */
        val VISION_REJECT_CODES = setOf(400, 415, 422)

        /** 错误体关键词：出现即认定是图片内容块不被支持，而非额度或密钥问题。 */
        val IMAGE_REJECTION_HINTS = listOf(
            "image_url",
            "image",
            "vision",
            "multimodal",
            "must be a string",
            "不支持图片"
        )
    }
}
