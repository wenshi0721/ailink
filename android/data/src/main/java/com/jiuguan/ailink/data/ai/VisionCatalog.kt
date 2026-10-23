package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.model.VisionSupport

/**
 * 模型名的静态快判表。
 *
 * 只做一件事：认出**确定支持**多模态的模型，省掉一次探测请求。
 * 故意不再维护「确定是纯文本」的名单——厂商会把同名别名升级成多模态
 * （DeepSeek 的 deepseek-chat/deepseek-flash 就属于这种演进），
 * 一旦写死就会把能识图的服务误判为不支持，而误判的代价远大于多一次 8 token 的探测。
 * 因此：名单没命中 → 一律交给 [VisionProbe] 实测。
 */
internal object VisionCatalog {

    /** 确定接受图片输入的家族/别名。 */
    private val VISION_PATTERNS = listOf(
        "gpt-4o",
        "gpt-4-turbo",
        "gpt-4.1",
        "gpt-5",
        "chatgpt-4o",
        "claude-3",
        "claude-4",
        "claude-sonnet",
        "claude-opus",
        "claude-haiku",
        "gemini",
        "qwen-vl",
        "qwen2-vl",
        "qwen2.5-vl",
        "qwen3-vl",
        "qvq",
        "glm-4v",
        "glm-4.5v",
        "glm-5v",
        "deepseek-vl",
        "deepseek-flash",
        "deepseek-v4-flash",
        "kimi-vl",
        "moonshot-v1-vision",
        "llava",
        "minicpm-v",
        "internvl",
        "multimodal",
        "-omni",
        "vision"
    )

    /** o 系列的推理模型从 o3 起原生多模态，用前缀匹配避免误伤其它含 "o3" 的名字。 */
    private val VISION_PREFIXES = listOf("o3", "o4")

    /** 返回 null 表示名单判断不了，需要实测。 */
    fun quickJudge(model: String): VisionSupport? {
        val id = model.trim().lowercase()
        if (id.isBlank()) return null
        if (VISION_PREFIXES.any { id.startsWith(it) }) return VisionSupport.SUPPORTED
        if (VISION_PATTERNS.any { id.contains(it) }) return VisionSupport.SUPPORTED
        return null
    }
}
