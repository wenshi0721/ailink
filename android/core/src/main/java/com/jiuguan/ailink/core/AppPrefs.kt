package com.jiuguan.ailink.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轻量本地偏好设置。使用 SharedPreferences + StateFlow，
 * 避免为极少量的键值引入额外依赖。
 */
class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ailink_prefs", Context.MODE_PRIVATE)

    private val _activeAccountId = MutableStateFlow(sp.getString(KEY_ACTIVE_ACCOUNT, "").orEmpty())
    /** 当前查看/操作的账号 id；空串表示「全部账号」。 */
    val activeAccountId: StateFlow<String> = _activeAccountId.asStateFlow()

    private val _engineRunning = MutableStateFlow(sp.getBoolean(KEY_ENGINE_RUNNING, false))
    /** 接入服务总开关（是否保持账号在线并自动回复）。 */
    val engineRunning: StateFlow<Boolean> = _engineRunning.asStateFlow()

    private val _qqUin = MutableStateFlow(sp.getString(KEY_QQ_UIN, "").orEmpty())
    /** 内置 QQ 的号码；填写后启动时带 -q，实现免扫码快速登录（需此前登录过一次）。 */
    val qqUin: StateFlow<String> = _qqUin.asStateFlow()

    fun setQqUin(uin: String) {
        val clean = uin.trim()
        sp.edit().putString(KEY_QQ_UIN, clean).apply()
        _qqUin.value = clean
    }

    private val _permissionAsked = MutableStateFlow(sp.getBoolean(KEY_NOTIFY_ASKED, false))
    val notificationAsked: StateFlow<Boolean> = _permissionAsked.asStateFlow()

    fun setActiveAccount(id: String) {
        sp.edit().putString(KEY_ACTIVE_ACCOUNT, id).apply()
        _activeAccountId.value = id
    }

    fun setEngineRunning(running: Boolean) {
        sp.edit().putBoolean(KEY_ENGINE_RUNNING, running).apply()
        _engineRunning.value = running
    }

    fun markNotificationAsked() {
        sp.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
        _permissionAsked.value = true
    }

    private val _updateMirrorBase = MutableStateFlow(sp.getString(KEY_UPDATE_MIRROR, "").orEmpty())
    /** 应用更新下载的镜像前缀（如 `https://gh-proxy.com/`）；空串 = 直连 GitHub。 */
    val updateMirrorBase: StateFlow<String> = _updateMirrorBase.asStateFlow()

    fun setUpdateMirrorBase(base: String) {
        val clean = base.trim()
        sp.edit().putString(KEY_UPDATE_MIRROR, clean).apply()
        _updateMirrorBase.value = clean
    }

    /** 上次成功检查更新的时间戳（毫秒），用于启动静默检查的节流。 */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    private companion object {
        const val KEY_ACTIVE_ACCOUNT = "active_account_id"
        const val KEY_QQ_UIN = "qq_uin"
        const val KEY_ENGINE_RUNNING = "engine_running"
        const val KEY_NOTIFY_ASKED = "notification_asked"
        const val KEY_UPDATE_MIRROR = "update_mirror_base"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
