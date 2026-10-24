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

    private val _qqPasswordSet = MutableStateFlow(
        sp.getString(KEY_QQ_PASSWORD_MD5, "").orEmpty().isNotBlank()
    )

    /** 是否已保存 QQ 密码（只暴露布尔值，密码本身不出 core 层）。 */
    val qqPasswordSet: StateFlow<Boolean> = _qqPasswordSet.asStateFlow()

    /**
     * 保存 QQ 密码用于"快速登录凭据失效时自动回退"。
     *
     * 明文只在此处停留一瞬：立刻算 MD5，再用 Keystore 加密后落盘。
     * 空串等价于清除。
     */
    fun setQqPassword(plain: String) {
        val trimmed = plain.trim()
        if (trimmed.isEmpty()) {
            clearQqPassword()
            return
        }
        val encrypted = SecretStore.encrypt(md5Hex(trimmed))
        if (encrypted.isNullOrBlank()) {
            // 加密不可用（极少数机型的 Keystore 异常）：宁可不存，也不退化成明文
            clearQqPassword()
            return
        }
        sp.edit().putString(KEY_QQ_PASSWORD_MD5, encrypted).apply()
        _qqPasswordSet.value = true
    }

    fun clearQqPassword() {
        sp.edit().remove(KEY_QQ_PASSWORD_MD5).apply()
        _qqPasswordSet.value = false
    }

    /** 取出来注入容器的 `NAPCAT_QUICK_PASSWORD_MD5`；未配置或解不开返回空串。 */
    fun qqPasswordMd5(): String {
        val encrypted = sp.getString(KEY_QQ_PASSWORD_MD5, "").orEmpty()
        if (encrypted.isBlank()) return ""
        val md5 = SecretStore.decrypt(encrypted).orEmpty()
        return if (md5.length == 32) md5 else ""
    }

    private fun md5Hex(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val _antiDetect = MutableStateFlow(sp.getBoolean(KEY_ANTI_DETECT, false))

    /**
     * 反检测加固是否启用（NapCat `o3HookMode = 0` + `bypass` 六项全开）。
     *
     * 默认关闭：这是"用兼容性换稳定性"的取舍，出问题时必须能一键退回默认值。
     */
    val antiDetect: StateFlow<Boolean> = _antiDetect.asStateFlow()

    fun setAntiDetect(enabled: Boolean) {
        sp.edit().putBoolean(KEY_ANTI_DETECT, enabled).apply()
        _antiDetect.value = enabled
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

    /**
     * 识图能力检测结果缓存，按配置 id 存一行「状态|地址|模型」。
     * 放这里而不是数据库：属于可丢弃的运行期推断结果，配置改动即失效，不值得占据表结构。
     */
    fun visionCache(profileId: String): String =
        sp.getString(KEY_VISION_CACHE + profileId, "").orEmpty()

    fun setVisionCache(profileId: String, value: String) {
        if (value.isBlank()) {
            sp.edit().remove(KEY_VISION_CACHE + profileId).apply()
        } else {
            sp.edit().putString(KEY_VISION_CACHE + profileId, value).apply()
        }
    }

    private companion object {
        const val KEY_ACTIVE_ACCOUNT = "active_account_id"
        const val KEY_QQ_UIN = "qq_uin"

        /** QQ 密码 MD5 的密文（Keystore AES-GCM），绝不存明文。 */
        const val KEY_QQ_PASSWORD_MD5 = "qq_password_md5_enc"

        /** 反检测加固开关（写入 NapCat 的 o3HookMode / bypass）。 */
        const val KEY_ANTI_DETECT = "anti_detect"
        const val KEY_ENGINE_RUNNING = "engine_running"
        const val KEY_NOTIFY_ASKED = "notification_asked"
        const val KEY_UPDATE_MIRROR = "update_mirror_base"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_VISION_CACHE = "vision_cache_"
    }
}
