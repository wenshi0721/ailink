package com.jiuguan.ailink.model



/** 接入平台。 */
/** 接入平台。 */
enum class Platform(val label: String) {
    QQ("QQ"),
    WECHAT("微信");

    val isGreen: Boolean get() = this == WECHAT
}
