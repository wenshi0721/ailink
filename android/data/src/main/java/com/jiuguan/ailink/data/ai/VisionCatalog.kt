package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.model.VisionSupport

/**
 * 模型名的静态快判表。
 *
 * 目的只是**省掉一次探测请求**：命中「确定多模态」或「确定纯文本」时直接给结论，
 * 其余一律返回 null 交给 [VisionProbe] 实测。因此这里只收高置信度的条目，
 * 宁可漏判（多探一次），也不要误判（把能识图的模型锁成纯文本）。
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
        "kimi-vl",
        "moonshot-v1-vision",
        "llava",
        "minicpm-v",
        "internvl",
        "multimodal",
        "-omni",
        "vision"
    )

    /** 确定只接受文本输入的常用模型，避免对它们发出注定失败的探测请求。 */
    private val TEXT_ONLY_PATTERNS = listOf(
        "deepseek-chat",
        "deepseek-reasoner",
        "moonshot-v1-8k",
        "moonshot-v1-32k",
        "moonshot-v1-128k",
        "hunyuan-lite",
        "ernie-3.5"
    )

    /** o 系列的推理模型从 o3 起原生多模态，用前缀匹配避免误伤其它含 "o3" 的名字。 */
    private val VISION_PREFIXES = listOf("o3", "o4")

    /** 返回 null 表示名单判断不了，需要实测。 */
    fun quickJudge(model: String): VisionSupport? {
        val id = model.trim().lowercase()
        if (id.isBlank()) return null
        if (TEXT_ONLY_PATTERNS.any { id.contains(it) }) return VisionSupport.UNSUPPORTED
        if (VISION_PREFIXES.any { id.startsWith(it) }) return VisionSupport.SUPPORTED
        if (VISION_PATTERNS.any { id.contains(it) }) return VisionSupport.SUPPORTED
        return null
    }
}
