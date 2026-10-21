package com.jiuguan.ailink.model



/** 消息接入方式。 */
/** 消息接入方式。 */
enum class BridgeType(val label: String, val shortLabel: String) {
    /** 本地演示：不依赖任何外部服务，用于验证界面与自动回复流程。 */
    DEMO("本地演示", "演示"),

    /** OneBot v11 标准（NapCat / LLOneBot / Lagrange 等桥接端）。 */
    ONEBOT_V11("OneBot v11 桥接", "OneBot")
}
