package com.jiuguan.ailink.core

import com.jiuguan.ailink.model.PairingInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 配对信息的中转站：外部深链（ailink://pair）在 Activity 里解析后投递，
 * 由导航层负责跳转、账号编辑页负责消费。
 */
object PairingBus {

    private val _pending = MutableStateFlow<PairingInfo?>(null)

    private val _incoming = MutableSharedFlow<PairingInfo>(extraBufferCapacity = 4)
    val incoming: SharedFlow<PairingInfo> = _incoming.asSharedFlow()

    fun offer(info: PairingInfo) {
        _pending.value = info
        _incoming.tryEmit(info)
    }

    /** 只读，不清空：供导航层判断是否需要跳到编辑页。 */
    fun peek(): PairingInfo? = _pending.value

    /** 取出并清空，保证同一份配对信息只用一次。 */
    fun consume(): PairingInfo? {
        val current = _pending.value
        if (current != null) _pending.value = null
        return current
    }
}
