package com.jiuguan.ailink.model

/**
 * 当前 AI 配置对图片输入的支持情况。
 *
 * 判定来源见 `data/ai/VisionProbe`：先按模型名快判，未命中再用一张内置小图探测，
 * 结果按「配置 + 地址 + 模型」缓存，配置改动后自动失效。
 */
enum class VisionSupport {
    /** 未检测，或检测因鉴权/额度/网络问题得不出结论。此时按不支持处理，保持原有纯文本行为。 */
    UNKNOWN,

    /** 接口确认接受多模态内容块，可以附带图片。 */
    SUPPORTED,

    /** 接口确认不接受图片输入，图片消息只以占位符参与对话。 */
    UNSUPPORTED
}
