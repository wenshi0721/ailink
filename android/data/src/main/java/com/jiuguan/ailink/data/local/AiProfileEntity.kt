package com.jiuguan.ailink.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jiuguan.ailink.model.AiAuthStyle
import com.jiuguan.ailink.model.AiProtocol
import com.jiuguan.ailink.model.authStyleOf
import com.jiuguan.ailink.model.protocolOf

/**
 * AI 服务配置，兼容 OpenAI Chat Completions 协议
 * （OpenAI / DeepSeek / 通义 / Moonshot / Ollama / LM Studio / One-API 等）。
 *
 * 所有网络参数都落库并可在界面编辑，业务层不得再内置地址、密钥或超时。
 * [apiKey] 在写入时由 AiRepository 用 Keystore 加密（前缀 enc1:），
 * 读出时解密；旧版本遗留的明文会按原样读取，下次保存自动转成密文。
 */
@Entity(tableName = "ai_profiles")
data class AiProfileEntity(
    @PrimaryKey val id: String,
    val name: String = "",
    /** 例如 https://api.deepseek.com/v1 */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 协议类型；OpenAI 兼容之外的新协议通过 Provider 扩展。 */
    val protocol: String = AiProtocol.OPENAI_COMPAT.name,
    /** 认证方式，决定密钥放在 Authorization 还是 x-api-key。 */
    val authStyle: String = AiAuthStyle.BEARER.name,
    /**
     * 聊天补全路径。留空表示按协议自动补全（以 /chat/completions 结尾的地址原样使用），
     * 填写后以用户值为准，适合路径不规律的网关。
     */
    val chatPath: String = "",
    /** 自定义请求头，每行一条 `Name: Value`；与认证头冲突时以用户填写为准。 */
    val extraHeaders: String = "",
    /** 单次请求读超时（秒）。 */
    val timeoutSec: Int = 60,
    /** 网络错误 / 429 / 5xx 的最大重试次数。 */
    val maxRetries: Int = 1,
    val temperature: Float = 0.8f,
    val maxTokens: Int = 512,
    /** 全局系统提示词，与账号人设叠加。 */
    val systemPrompt: String = "",
    /**
     * 识图开关：true = 自动检测后按结论启用（默认），false = 强制只发文本。
     * 检测结论不落库，见 AppPrefs 的 vision 缓存。
     */
    val visionEnabled: Boolean = true,
    /** 停用后不会被任何账号使用；运行中的账号会回退到内置模拟回复。 */
    val enabled: Boolean = true,
    /** 全局默认配置：账号未显式绑定、或绑定的配置被删除时使用。 */
    val isDefault: Boolean = false,
    /**
     * 旧版本迁移而来、尚未经用户确认的配置。
     *
     * 迁移时这类配置被置为停用：旧地址、旧密钥不能悄悄继续生效。
     * 用户在界面上确认（改成启用并保存）后清除此标记。
     */
    val needsReview: Boolean = false,
    val lastTestAt: Long = 0L,
    val lastTestOk: Boolean = false,
    val lastTestMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 未配置接口地址时使用内置的本地模拟回复。 */
    val isLocalOnly: Boolean get() = baseUrl.isBlank()

    val protocolType: AiProtocol get() = protocolOf(protocol)

    val auth: AiAuthStyle get() = authStyleOf(authStyle)

    /** 识图探测与调用都应跳过的配置（停用 / 待确认 / 纯本地）。 */
    val usable: Boolean get() = enabled && !needsReview
}
