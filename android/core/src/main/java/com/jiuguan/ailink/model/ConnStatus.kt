package com.jiuguan.ailink.model



/** 连接状态。 */
/** 连接状态。 */
enum class ConnStatus(val label: String) {
    OFFLINE("已停止"),
    CONNECTING("连接中"),
    ONLINE("在线"),
    ERROR("异常")
}
