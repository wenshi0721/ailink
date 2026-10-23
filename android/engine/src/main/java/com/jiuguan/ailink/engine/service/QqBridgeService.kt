package com.jiuguan.ailink.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jiuguan.ailink.engine.R
import com.jiuguan.ailink.core.AppPrefs
import com.jiuguan.ailink.engine.container.LocalRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * QQ 运行进程的托管服务。
 *
 * 为什么必须是前台服务：扫码登录需要用户切到「手机 QQ」→ 扫一扫 → 从相册选图。
 * 这期间我们的 App 在后台，如果 QQ 进程挂在页面协程里，切走就被回收，
 * 二维码随即失效——单手机登录根本走不通。
 *
 * 另外这里负责持续监听 qrcode.png：QQ 每次刷新二维码会覆盖该文件，
 * 通过 mtime + 大小变化判定更新，把最新图片推给界面。
 */
class QqBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "qq_runtime"
        private const val NOTIFICATION_ID = 3001

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        /** 最新二维码图片（PNG 字节）。 */
        private val _qr = MutableStateFlow<ByteArray?>(null)
        val qr: StateFlow<ByteArray?> = _qr.asStateFlow()

        /** 二维码刷新次数，用于提示旧图作废。 */
        private val _qrVersion = MutableStateFlow(0)
        val qrVersion: StateFlow<Int> = _qrVersion.asStateFlow()

        private val _qrUpdatedAt = MutableStateFlow(0L)
        val qrUpdatedAt: StateFlow<Long> = _qrUpdatedAt.asStateFlow()

        private val _decodeUrl = MutableStateFlow<String?>(null)
        val decodeUrl: StateFlow<String?> = _decodeUrl.asStateFlow()

        private val _loggedIn = MutableStateFlow(false)
        val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

        /** 快速登录失败（多为 QQ 号填错，或该号从未在本机成功登录过）。 */
        private val _quickLoginFailed = MutableStateFlow(false)
        val quickLoginFailed: StateFlow<Boolean> = _quickLoginFailed.asStateFlow()

        private val _offline = MutableStateFlow(false)
        val offline: StateFlow<Boolean> = _offline.asStateFlow()

        /** 快速登录的登录态失效，必须重新扫码。 */
        private val _loginExpired = MutableStateFlow(false)
        val loginExpired: StateFlow<Boolean> = _loginExpired.asStateFlow()

        fun start(context: Context) {
            if (_running.value) return
            val intent = Intent(context, QqBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, QqBridgeService::class.java).setAction("stop")
            )
        }

        fun clear() {
            _logs.value = emptyList()
            _qr.value = null
            _qrVersion.value = 0
            _qrUpdatedAt.value = 0L
            _decodeUrl.value = null
            _loggedIn.value = false
            _offline.value = false
            _quickLoginFailed.value = false
            _loginExpired.value = false
        }
    }

    private val keepAlive by lazy { KeepAlive(this, "qq") }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var relaunchCount = 0
    private var lastRelaunchAt = 0L
    /** NapCat 已声明自己在重启 worker（`账号已离线，正在重启 Worker`）。 */
    @Volatile
    private var napcatSelfHealing = false
    private var logJob: Job? = null
    private var qrJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            shutdown()
            return START_NOT_STICKY
        }
        if (_running.value) return START_NOT_STICKY

        _running.value = true
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 QQ…"))
        // 息屏保活：否则 CPU 休眠会让容器里的 QQ 停摆
        keepAlive.acquire()

        val runtime = LocalRuntime(applicationContext)
        runtime.resetLaunchLog()
        val uin = AppPrefs(applicationContext).qqUin.value
        appendLog("[app] 启动 QQ，传入号码：${uin.ifBlank { "（未设置，将走二维码）" }}")

        scope.launch {
            val result = runCatching { runtime.launchQq(uin) }
            result.onFailure { error ->
                appendLog("[app] 启动失败：${error.message ?: "未知错误"}")
                _running.value = false
                stopSelf()
            }
            result.onSuccess { started ->
                process = started
                appendLog("[app] QQ 进程已启动，等待二维码…")

                logJob = scope.launch {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) {
                            appendLog(line)
                            runtime.appendLaunchLog(line)
                            parseLine(line)
                        }
                    }
                    appendLog("[app] QQ 进程已退出")
                    _running.value = false
                    stopSelf()
                }

                qrJob = scope.launch { watchQrCode(runtime) }
                scope.launch { watchOffline() }
            }
        }
        return START_NOT_STICKY
    }

    /** 持续监听二维码文件：QQ 刷新二维码时会覆盖它。 */
    private suspend fun watchQrCode(runtime: LocalRuntime) {
        val file = File(
            runtime.runtimeDir,
            "rootfs/opt/QQ/resources/app/app_launcher/napcat/cache/qrcode.png"
        )
        // 首轮只登记基线，不推送：qrcode.png 可能是上一轮 QQ 留下的过期图。
        // 实测日志里 AiLink 在 11:52:22 就推了「第 1 次」二维码，而本次 QQ 直到
        // 11:53:37 才第一次出图——用户扫到的那张必然已失效。
        var lastStamp = if (file.exists()) file.lastModified() else -1L
        var lastSize = if (file.exists()) file.length() else -1L
        while (scope.isActive) {
            delay(2000)
            if (!file.exists()) continue
            val stamp = file.lastModified()
            val size = file.length()
            if (stamp == lastStamp && size == lastSize) continue
            lastStamp = stamp
            lastSize = size
            if (_loggedIn.value) continue
            runCatching { runtime.readQrCode() }.getOrNull()?.let { bytes ->
                _qr.value = bytes
                _qrVersion.value += 1
                _qrUpdatedAt.value = System.currentTimeMillis()
                appendLog("[app] 二维码已更新（第 ${_qrVersion.value} 次）")
                updateNotification("请用手机 QQ 扫码登录")
            }
        }
    }

    /**
     * 掉线自愈：息屏休眠常导致 QQ 与服务器的连接断开且不会自己重连。
     * 连续离线超过阈值就重启容器里的 QQ；每小时最多 3 次，避免无限循环。
     *
     * 阈值不能低于 NapCat 自己的自愈窗口：NapCat 收到 SelfOffline 后先等
     * selfOfflineDelayMs = 60000 毫秒，期间若自己恢复则静默回到 ready，
     * 否则才重启 worker。若我们在同一时刻销毁进程树，等于把它唯一的自愈机会抹掉。
     */
    private suspend fun watchOffline() {
        var offlineSince = 0L
        while (scope.isActive) {
            delay(15_000)
            if (!_offline.value) {
                offlineSince = 0L
                continue
            }
            val now = System.currentTimeMillis()
            if (offlineSince == 0L) {
                offlineSince = now
                continue
            }
            // NapCat 已经在重启 worker 时，把容器重启顺延到它后面
            val window = if (napcatSelfHealing) 180_000L else 90_000L
            if (now - offlineSince < window) continue

            if (now - lastRelaunchAt < 3_600_000 && relaunchCount >= 3) {
                appendLog("[app] 已连续重启 3 次，暂缓自动恢复，请手动点「启动 QQ」")
                offlineSince = now
                continue
            }
            if (now - lastRelaunchAt > 3_600_000) relaunchCount = 0

            relaunchCount += 1
            lastRelaunchAt = now
            offlineSince = now
            appendLog("[app] 离线超过 ${window / 1000} 秒，第 $relaunchCount 次重启 QQ 容器")
            updateNotification("正在重启 QQ…")
            relaunch()
        }
    }

    /** 重启容器内的 QQ 进程（复用当前配置，含 -q 快速登录）。 */
    private fun relaunch() {
        logJob?.cancel()
        qrJob?.cancel()
        runCatching { process?.destroy() }
        process = null
        _qr.value = null
        _offline.value = false

        val runtime = LocalRuntime(applicationContext)
        runtime.resetLaunchLog()
        // 新进程等于重新登录：不清掉 _loggedIn，watchQrCode 会因为「已登录」而
        // 拒绝推送新二维码，用户就再也扫不到码了
        _loggedIn.value = false
        napcatSelfHealing = false
        val uin = AppPrefs(applicationContext).qqUin.value
        runCatching { runtime.launchQq(uin) }
            .onSuccess { started ->
                process = started
                logJob = scope.launch {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) {
                            appendLog(line)
                            runtime.appendLaunchLog(line)
                            parseLine(line)
                        }
                    }
                    appendLog("[app] QQ 进程已退出")
                    _running.value = false
                    stopSelf()
                }
                qrJob = scope.launch { watchQrCode(runtime) }
            }
            .onFailure { appendLog("[app] 重启失败：${it.message}") }
    }

    /**
     * 解析 QQ/NapCat 的 stdout。
     *
     * 判据必须与本机 NapCat 4.18.38（assets/runtime/NapCat.Shell.zip 内 napcat.mjs）的
     * 实际输出对齐：
     *  - `onSelfStatusChanged` 只在 status === 20 时打印「账号状态变更为离线」；
     *    status 非 20 时仅把 selfInfo.online 置 true，**不打印任何日志**。
     *  - 被服务端踢下线走 `onKickedOffLine`，打印 `[KickedOffLine] [标题] 描述`。
     *  - 登录成功的可观测副作用是「本账号数据/缓存目录：」与「协议适配器初始化完成」。
     *
     * 因此「恢复在线」在日志文本层根本读不到，只能拿上面两条里的后一条当复位点。
     */
    private fun parseLine(line: String) {
        if (line.contains("二维码解码URL")) {
            val url = line.substringAfter("二维码解码URL:").trim()
            if (url.startsWith("http")) _decodeUrl.value = url
        }
        // 登录完成 / worker 重建完成：都会重新走一遍 InitNapCat → initAdapters
        if (line.contains("协议适配器初始化完成") || line.contains("本账号数据/缓存目录")) {
            val wasLoggedIn = _loggedIn.value
            _loggedIn.value = true
            napcatSelfHealing = false
            // 这两行每次登录会各命中一次，只在首次迁移时落一条日志，
            // 否则日志里看不到"登录已就绪"这个关键锚点（NapCat 自己不打印在线）
            if (!wasLoggedIn) appendLog("[app] 登录成功")
            if (_offline.value) {
                _offline.value = false
                appendLog("[app] QQ 已恢复在线")
                updateNotification("QQ 已登录")
            }
        }
        // 未登录阶段的 status=20 推送不代表"掉线"——此时没有会话可掉，
        // 重启容器只会让用户刚扫的二维码作废。
        if (line.contains("账号状态变更为离线") && _loggedIn.value) {
            _offline.value = true
            appendLog("[app] 检测到 QQ 掉线，90 秒内未恢复将自动重启容器")
            updateNotification("QQ 掉线，正在等待恢复")
        }
        // NapCat 自己已经在处置，别在同一时刻杀进程
        if (line.contains("账号已离线，正在重启 Worker")) {
            napcatSelfHealing = true
            appendLog("[app] NapCat 正在自行重启 Worker，容器重启顺延")
        }
        if (line.contains("登录态已失效")) {
            _quickLoginFailed.value = true
            _loginExpired.value = true
            appendLog("[app] 登录态已失效：需要重新扫一次码；扫码成功后快速登录才会再生效")
            updateNotification("登录态失效，请重新扫码")
        }
        if (line.contains("快速登录失败")) {
            _quickLoginFailed.value = true
            appendLog("[app] 快速登录失败：QQ 号可能有误，或该号尚未在本机成功登录过；已回退为二维码")
        }
        if (line.contains("未找到该 QQ 历史登录记录")) {
            _quickLoginFailed.value = true
        }
        // 旧版兜底：本机 NapCat 4.18.38 的登录流程并不打印「登录成功」，
        // 保留是为了兼容其它桥接端/版本；已判过登录的不再重复落日志。
        if (!_loggedIn.value &&
            (line.contains("登录成功") || line.contains("账号信息") || line.contains("online=true"))
        ) {
            _loggedIn.value = true
            appendLog("[app] 登录成功")
            updateNotification("QQ 已登录")
        }
    }

    private fun appendLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(500)
        if (line.startsWith("[app]")) {
            com.jiuguan.ailink.core.SessionLog.add("qq", line.removePrefix("[app] ").trim())
        }
    }

    private fun shutdown() {
        keepAlive.release()
        logJob?.cancel()
        qrJob?.cancel()
        process?.destroy()
        process = null
        _running.value = false
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        keepAlive.release()
        logJob?.cancel()
        qrJob?.cancel()
        process?.destroy()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QQ 运行状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "手机内置 QQ 的运行与登录状态" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("手机内置 QQ")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
