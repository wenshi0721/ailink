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

        /** 超过这个时长没有重启过，视为新的一轮故障，重启计数归零。 */
        private const val RESET_WINDOW_MS = 3_600_000L

        /** 启动后这么久没有任何 stdout 就判定为假启动（卡在 xvfb/proot）。 */
        private const val LAUNCH_SILENCE_TIMEOUT_MS = 60_000L

        /** 假启动最多自动重试几次，超过就交给人工。 */
        private const val MAX_SILENT_RETRIES = 2

        /** 这么短时间内再次被服务端踢下线，说明重登解决不了问题，转为待扫码。 */
        private const val KICK_RETRY_WINDOW_MS = 10 * 60_000L

        /** 降级为扫码模式的保持时长（借鉴 XiaoYou：6 小时后自动回到快速登录）。 */
        private const val CAPTCHA_QR_MODE_MS = 6 * 60 * 60_000L

        /** 两次启动之间的最小间隔，避免自愈/手动连点把刚出的二维码作废。 */
        private const val LAUNCH_COOLDOWN_MS = 15_000L

        /** 已登录但 3001 连续不可达这么久，判定协议层已死。 */
        private const val PROTOCOL_DEAD_MS = 90_000L

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

        /**
         * QQ 要求人工验证（短信/新设备）。
         *
         * 验证**必须**在 NapCat 的 WebUI 页面里完成，直接打开腾讯的 ti.qq.com 结果传不回来；
         * 所以这里带上可直接打开的 WebUi 地址。
         */
        private val _needVerify = MutableStateFlow(false)
        val needVerify: StateFlow<Boolean> = _needVerify.asStateFlow()

        private val _verifyUrl = MutableStateFlow("")
        val verifyUrl: StateFlow<String> = _verifyUrl.asStateFlow()

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

        /**
         * 重启容器内的 QQ（配置变更后用）。
         *
         * 与用户在界面上点「启动」的区别：这条路跳过启动冷却，因为用户刚刚明确改过配置，
         * 必须立即生效；但也不会在服务未运行时拉起进程（交给用户手动启动）。
         */
        fun restart(context: Context) {
            context.startService(
                Intent(context, QqBridgeService::class.java).setAction("restart")
            )
        }

        fun clear() {
            _logs.value = emptyList()
            _qr.value = null
            _qrVersion.value = 0
            _qrUpdatedAt.value = 0L
            _decodeUrl.value = null
            _loggedIn.value = false
            _needVerify.value = false
            _verifyUrl.value = ""
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

    /** NapCat 自愈失败、已经退回二维码界面 —— 此时再等下去没有意义，直接容器重启。 */
    @Volatile
    private var forceRelaunch = false

    /** 服务端主动踢下线（KickedOffLine）：重登只会更快作废凭据，先停下等人工扫码。 */
    @Volatile
    private var autoRestartSuppressed = false
    private var suppressedLogged = false

    /** 上次被踢时间：短时间内重复被踢说明重登无效，不再自动重登。 */
    @Volatile
    private var lastKickAt = 0L

    /**
     * QQ 要求人工验证的截止时间（毫秒时间戳）。
     *
     * 之前用的是布尔量，只在登录成功时清除——如果用户一直没能登录，就会永久停在扫码模式，
     * 想改回快速登录也没机会。改成时间戳后 6 小时自动失效（借鉴 XiaoYou 的做法）。
     */
    @Volatile
    private var captchaUntil = 0L

    private fun captchaActive(): Boolean = captchaUntil > System.currentTimeMillis()

    /** 上次启动时间：用于启动冷却，避免连点/自愈抖动把刚出的二维码作废。 */
    @Volatile
    private var lastLaunchAt = 0L

    /** 3001 端口不可达的起始时间，用于判定"协议层已死"。 */
    @Volatile
    private var protocolDeadSince = 0L

    /** 最后一行 QQ 输出的时间，用于识别"进程起来了但什么都没打印"的假启动。 */
    @Volatile
    private var lastOutputAt = 0L
    private var silentRetries = 0

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
        // 配置变更后的重启：只在服务本来就跑着时执行，不主动拉起
        if (intent?.action == "restart") {
            if (_running.value) {
                appendLog("[app] 收到配置变更，重启 QQ 生效")
                relaunch(force = true)
            }
            return START_NOT_STICKY
        }
        if (_running.value) return START_NOT_STICKY

        _running.value = true
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 QQ…"))
        // 息屏保活：否则 CPU 休眠会让容器里的 QQ 停摆
        keepAlive.acquire()

        // 手动启动 = 用户已经介入，清掉上一轮"被踢后暂停自动重启"的状态
        autoRestartSuppressed = false
        suppressedLogged = false
        silentRetries = 0
        forceRelaunch = false
        lastKickAt = 0L
        // 新进程还没登录：不复位的话 watchQrCode 会以"已登录"为由跳过新二维码，
        // 用户点「停止」再「启动」后就再也看不到码了（实测踩到过）。
        _loggedIn.value = false

        val runtime = LocalRuntime(applicationContext)
        runtime.resetLaunchLog()
        val uin = AppPrefs(applicationContext).qqUin.value

        scope.launch {
            runtime.cleanupStaleX11()
            launchAndWatch(runtime, uin)
            // 掉线自愈只在首次会话启动一次，relaunch 内部不再重复挂
            scope.launch { watchOffline() }
        }
        return START_NOT_STICKY
    }

    /**
     * 启动 QQ 并挂上日志管道 + 静默看门狗。
     *
     * onStartCommand 与 relaunch 共用：启动后的收尾逻辑（进程退出、静默检测）
     * 只写一份，避免两处漂移——之前正是 relaunch 少了这些，才出现"起了但没输出、
     * 也没人发现"的空窗。
     */
    private fun launchAndWatch(runtime: LocalRuntime, uin: String) {
        lastLaunchAt = System.currentTimeMillis()

        // QQ 要求人工验证期间强制走二维码模式：带 -q 只会再触发一次"需要验证码"，
        // 而 NapCat 在验证码分支里不会生成二维码，界面就一直没有码可扫。
        val verifyMode = captchaActive()
        val effectiveUin = if (verifyMode) "" else uin
        val passwordMd5 = if (effectiveUin.isBlank()) {
            ""
        } else {
            AppPrefs(applicationContext).qqPasswordMd5()
        }
        appendLog(
            "[app] 启动 QQ，传入号码：${effectiveUin.ifBlank { "（二维码模式）" }}；" +
                "密码回退：${
                    when {
                        verifyMode -> "暂停（QQ 要求人工验证）"
                        effectiveUin.isBlank() -> "不可用（未设置号码）"
                        passwordMd5.isNotBlank() -> "已配置（凭据失效时自动登录）"
                        else -> "未配置（失效后只能扫码）"
                    }
                }"
        )
        val result = runCatching { runtime.launchQq(effectiveUin, passwordMd5) }
        result.onFailure { error ->
            appendLog("[app] 启动失败：${error.message ?: "未知错误"}")
            _running.value = false
            stopSelf()
        }
        result.onSuccess { started ->
            process = started
            lastOutputAt = System.currentTimeMillis()
            appendLog("[app] QQ 进程已启动，等待二维码…")

            logJob = scope.launch {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        lastOutputAt = System.currentTimeMillis()
                        silentRetries = 0
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
            armSilenceWatchdog()
        }
    }

    /**
     * 假启动看门狗：进程创建成功但一行输出都没有，说明它卡在 xvfb-run / proot 里。
     *
     * 这种"活着的死进程"是最坑的形态——没有异常、没有退出、日志无从下手。
     * 判据只看"有没有任何 stdout"，不依赖具体内容，所以对任何卡死点都有效。
     */
    private fun armSilenceWatchdog() {
        val launchedAt = System.currentTimeMillis()
        scope.launch {
            delay(LAUNCH_SILENCE_TIMEOUT_MS)
            if (lastOutputAt >= launchedAt) return@launch
            appendLog(
                "[app] 启动 ${LAUNCH_SILENCE_TIMEOUT_MS / 1000} 秒内无任何输出，" +
                    "判定启动失败（多半卡在 xvfb/proot）"
            )
            if (autoRestartSuppressed) return@launch
            if (silentRetries >= MAX_SILENT_RETRIES) {
                appendLog("[app] 已连续 ${MAX_SILENT_RETRIES} 次无声失败，停止自动重试，请手动启动 QQ")
                return@launch
            }
            silentRetries += 1
            appendLog("[app] 清理残留后重试启动（第 $silentRetries 次）")
            relaunch()
        }
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
            // P0：这里**不再**用 _loggedIn 当闸门。
            // 闸门是隐式状态，任何一处忘记复位都会造成"有码但不推"，
            // 已经被踢、手动重启各坑过一次。现在一律按文件变化推送，
            // "登录后不展示二维码"交给 UI 依据 loggedIn 决定（登录成功时也会清空）。
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
            checkProtocolAlive()
            if (!_offline.value) {
                offlineSince = 0L
                continue
            }
            val now = System.currentTimeMillis()
            if (offlineSince == 0L) {
                offlineSince = now
                continue
            }
            // 服务端踢下线：重登只会更快作废凭据，停下来等人工扫码
            if (autoRestartSuppressed) {
                if (!suppressedLogged) {
                    suppressedLogged = true
                    appendLog("[app] 账号被服务端下线，已停止自动重启；扫码恢复后才能继续自愈")
                }
                offlineSince = 0L
                continue
            }
            val window = when {
                // NapCat 已经确认自愈失败、退回二维码了，再等就是浪费时间
                forceRelaunch -> 0L
                // NapCat 正在重启 worker：等它先走完，避免两个实例抢 X display/rootfs
                napcatSelfHealing -> 180_000L
                else -> 90_000L
            }
            if (now - offlineSince < window) continue

            // 距上次重启超过 1 小时，视为新的一轮故障，计数归零
            if (now - lastRelaunchAt > RESET_WINDOW_MS) relaunchCount = 0

            // 连续重启的退避阶梯：前 3 次即时重试，之后逐步拉长间隔。
            // 旧逻辑是「1 小时内满 3 次就静默到满 1 小时」，那等于把"没人管"
            // 当成默认结果——这正是一天里偶发"掉了就再也没自己起来"的来源。
            val backoff = when {
                relaunchCount < 3 -> 0L
                relaunchCount == 3 -> 5 * 60_000L
                relaunchCount == 4 -> 15 * 60_000L
                else -> 30 * 60_000L
            }
            if (backoff > 0 && now - lastRelaunchAt < backoff) continue

            relaunchCount += 1
            lastRelaunchAt = now
            offlineSince = now
            appendLog(
                "[app] 离线超过 ${window / 1000} 秒，第 $relaunchCount 次重启 QQ 容器" +
                    if (backoff > 0) "（仍离线则按 ${backoff / 60_000} 分钟阶梯重试）" else ""
            )
            updateNotification("正在重启 QQ…")
            relaunch()
        }
    }

    /**
     * 协议层存活判定：已登录状态下连不上 3001，说明 NapCat 的 OneBot 服务已经不在了，
     * 但内核可能还认为自己在"在线"。这种"半死"状态靠日志是看不出来的，
     * 只能靠端口探测兜住（借鉴 XiaoYou 用 pid 清单而不是单个句柄判活的思路）。
     */
    private suspend fun checkProtocolAlive() {
        if (!_loggedIn.value) {
            protocolDeadSince = 0L
            return
        }
        if (probeOneBotPort()) {
            protocolDeadSince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (protocolDeadSince == 0L) {
            protocolDeadSince = now
            return
        }
        if (now - protocolDeadSince < PROTOCOL_DEAD_MS) return

        protocolDeadSince = 0L
        _loggedIn.value = false
        _offline.value = true
        forceRelaunch = true
        appendLog(
            "[app] 已登录但 3001 端口 ${PROTOCOL_DEAD_MS / 1000}s 不可达，" +
                "判定协议层已死，准备重启"
        )
    }

    private suspend fun probeOneBotPort(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 3001), 1_000)
                }
                true
            }.getOrDefault(false)
        }

    /** 重启容器内的 QQ 进程（复用当前配置，含 -q 快速登录）。 */
    private fun relaunch(force: Boolean = false) {
        // 启动冷却：自愈循环与看门狗可能在同一秒内各自触发一次，
        // 第二次重启会把刚生成、还没被扫的二维码当场作废。
        // force = 用户在界面上明确改过配置，立即生效优先于冷却。
        val sinceLast = System.currentTimeMillis() - lastLaunchAt
        if (!force && lastLaunchAt > 0 && sinceLast < LAUNCH_COOLDOWN_MS) {
            appendLog("[app] 距上次启动仅 ${sinceLast / 1000}s，跳过本次重启（冷却中）")
            return
        }

        logJob?.cancel()
        qrJob?.cancel()

        val old = process
        process = null
        // 必须等旧进程真的退出：否则新旧两个实例会抢同一个 X display 和 rootfs 锁，
        // 新进程表现为"一行输出都没有"（实测出现过 5 小时空窗）。
        val exited = LocalRuntime(applicationContext).terminate(old)
        if (!exited) appendLog("[app] 旧 QQ 进程 8 秒内未退出，已强制结束")

        _qr.value = null
        _offline.value = false
        forceRelaunch = false
        napcatSelfHealing = false

        val runtime = LocalRuntime(applicationContext)
        runtime.cleanupStaleX11()
        runtime.resetLaunchLog()
        // 新进程等于重新登录：不清掉 _loggedIn，watchQrCode 会因为「已登录」而
        // 拒绝推送新二维码，用户就再也扫不到码了
        _loggedIn.value = false
        val uin = AppPrefs(applicationContext).qqUin.value
        appendLog("[app] 重启 QQ 容器（第 $relaunchCount 次）")
        launchAndWatch(runtime, uin)
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
            forceRelaunch = false
            protocolDeadSince = 0L
            // 登录成功 = 人工验证已通过，恢复"带 -q 快速登录"的正常模式
            captchaUntil = 0L
            _needVerify.value = false
            _verifyUrl.value = ""
            // 登录成功 = 用户已介入（多半刚扫码），把"被踢后暂停自愈"的状态还回去
            autoRestartSuppressed = false
            suppressedLogged = false
            // 这两行每次登录会各命中一次，只在首次迁移时落一条日志，
            // 否则日志里看不到"登录已就绪"这个关键锚点（NapCat 自己不打印在线）
            if (!wasLoggedIn) appendLog("[app] 登录成功")
            // 已登录就不该再展示二维码：清空而不是靠 watchQrCode 里的闸门拦
            _qr.value = null
            // 扫码成功后凭据会重新生效，界面上的「登录态已失效」提示必须同步撤掉
            _loginExpired.value = false
            _quickLoginFailed.value = false
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
        // NapCat 自愈失败：worker 重启完又退回二维码界面，继续等只是浪费时间
        if (line.contains("将允许重新获取二维码") && napcatSelfHealing) {
            napcatSelfHealing = false
            forceRelaunch = true
            appendLog("[app] NapCat 自愈失败（已退回二维码），立即容器重启")
        }
        // NapCat 的 WebUi 地址：短信验证必须在 WebUi 页面里完成，
        // 直接打开腾讯的 ti.qq.com 链接，验证结果传不回 NapCat（社区踩坑结论）。
        if (line.contains("WebUi Token:")) {
            val token = line.substringAfter("WebUi Token:").trim()
            if (token.isNotBlank()) {
                _verifyUrl.value = "http://127.0.0.1:6099/webui/web_login?token=$token"
            }
        }
        // QQ 要求短信/新设备验证：把腾讯原始链接也记下来，只作参考
        if (line.contains("proofWaterUrl:")) {
            val url = line.substringAfter("proofWaterUrl:").trim()
            if (url.startsWith("http")) {
                appendLog("[app] QQ 验证原始链接：$url（需在 NapCat WebUi 里完成，直接打开无效）")
            }
        }
        // 密码回退撞上人工验证：NapCat 在这个分支里**不会**生成二维码
        // （它不是失败也不是"未尝试"，调用方的 `!success && !attempted` 条件不成立），
        // 所以必须由我们改成二维码模式重启，否则界面会一直没有码。
        if (line.contains("密码回退需要验证码") || line.contains("密码回退需要新设备验证")) {
            // 6 小时后自动失效，避免永久停在扫码模式（借鉴 XiaoYou 的降级标记 TTL）
            captchaUntil = System.currentTimeMillis() + CAPTCHA_QR_MODE_MS
            _needVerify.value = true
            _loggedIn.value = false
            _offline.value = true
            autoRestartSuppressed = false
            forceRelaunch = true
            _loginExpired.value = true
            appendLog(
                "[app] 密码回退被 QQ 要求人工验证；改为二维码登录（6 小时内保持扫码模式）。" +
                    if (_verifyUrl.value.isNotBlank()) {
                        "也可打开 WebUi 完成验证：${_verifyUrl.value}"
                    } else {
                        "如需短信验证，请在 NapCat WebUi 中完成"
                    }
            )
            updateNotification("需要人工验证：请扫码，或在 WebUi 完成短信验证")
        }
        // 服务端主动踢下线：这不是网络抖动。用失效凭据反复重登只会更快触发风控，
        // 直接停下来等人工扫码，恢复后再交还自动自愈。
        if (line.contains("KickedOffLine")) {
            _offline.value = true
            _loginExpired.value = true
            // 关键：账号已经不在线了，必须把 _loggedIn 复位。
            // 否则 watchQrCode 会因为「已登录」而跳过新二维码——NapCat 重启 worker 后
            // 明明生成了新码（12:34:37 二维码已保存），界面上却一直没有码，表现为"无法登录"。
            _loggedIn.value = false

            val passwordArmed = AppPrefs(applicationContext).qqPasswordMd5().isNotBlank()
            val kickedRecently = lastKickAt > 0 &&
                System.currentTimeMillis() - lastKickAt < KICK_RETRY_WINDOW_MS
            lastKickAt = System.currentTimeMillis()

            if (passwordArmed && !kickedRecently) {
                // 有密码回退：凭据虽失效但密码可用，允许自动重登一次，不必等人扫码
                autoRestartSuppressed = false
                forceRelaunch = true
                appendLog("[app] 账号被服务端下线；已配置密码回退，本次自动重登（失败则转待扫码）")
                updateNotification("账号被下线，正在用密码自动重登")
            } else {
                autoRestartSuppressed = true
                suppressedLogged = false
                appendLog(
                    "[app] 账号被服务端下线（KickedOffLine），已停止自动重启；" +
                        if (passwordArmed) "短时间内重复被踢，请扫码重新登录" else "请扫码重新登录（未配置密码回退）"
                )
                updateNotification("账号被下线，请扫码重新登录")
            }
        }
        // 凭据作废时 NapCat 打的是 `快速登录错误： <errMsg>`，errMsg 为
        // 「你的用户身份已失效，为保证账号安全，请你重新登录。」——既不是
        // 「登录态已失效」也不是「快速登录失败」，必须按实际文案匹配，否则界面的
        // 「登录态已失效，需要重新扫码」提示永远不会出现。
        if (line.contains("登录态已失效") || line.contains("你的用户身份已失效")) {
            _quickLoginFailed.value = true
            _loginExpired.value = true
            appendLog("[app] 登录态已失效：需要重新扫一次码；扫码成功后快速登录才会再生效")
            updateNotification("登录态失效，请重新扫码")
        }
        if (!_loginExpired.value &&
            (line.contains("快速登录失败") || line.contains("快速登录错误") || line.contains("快速登录异常"))
        ) {
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
