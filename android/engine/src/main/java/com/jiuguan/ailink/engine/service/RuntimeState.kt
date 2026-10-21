package com.jiuguan.ailink.engine.service

import com.jiuguan.ailink.model.ConnStatus

/** 单个账号的运行态，供界面展示。 */
data class RuntimeState(
    val accountId: String = "",
    val status: ConnStatus = ConnStatus.OFFLINE,
    val selfId: Long = 0L,
    val connectedAt: Long = 0L,
    val latencyMs: Long = -1L,
    val received: Int = 0,
    val replied: Int = 0,
    val lastActiveAt: Long = 0L,
    val lastError: String = ""
) {
    val isOnline: Boolean get() = status == ConnStatus.ONLINE
}
