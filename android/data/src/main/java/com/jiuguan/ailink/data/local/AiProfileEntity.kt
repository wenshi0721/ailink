package com.jiuguan.ailink.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 服务配置，兼容 OpenAI Chat Completions 协议
 * （OpenAI / DeepSeek / 通义 / Moonshot / Ollama / LM Studio / One-API 等）。
 */
@Entity(tableName = "ai_profiles")
data class AiProfileEntity(
    @PrimaryKey val id: String,
    val name: String = "",
    /** 例如 https://api.deepseek.com/v1 */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.8f,
    val maxTokens: Int = 512,
    /** 全局系统提示词，与账号人设叠加。 */
    val systemPrompt: String = "",
    /**
     * 识图开关：true = 自动检测后按结论启用（默认），false = 强制只发文本。
     * 检测结论不落库，见 AppPrefs 的 vision 缓存。
     */
    val visionEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 未配置接口地址时使用内置的本地模拟回复。 */
    val isLocalOnly: Boolean get() = baseUrl.isBlank()
}
