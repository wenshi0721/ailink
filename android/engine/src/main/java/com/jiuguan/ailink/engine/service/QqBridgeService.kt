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
import com.jiuguan.ailink.engine.container.NetworkDns
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

        /** 会话存活不足这个时长，视为"短命会话"（登录后很快被断，多见于风控期）。 */
        private const val SHORT_SESSION_MS = 3 * 60_000L

        /** 连续这么多次短命会话，判定为风控活跃期，进入冷静期而不是继续重启。 */
        private const val SHORT_SESSION_LIMIT = 3

        /** 冷静期时长：这段时间内不再自动重启，避免把风控分越刷越高。 */
        private const val COOLDOWN_MS = 15 * 60_000L

        /** 降级为扫码模式的保持时长（借鉴 XiaoYou：6 小时后自动回到快速登录）。 */
        private const val CAPTCHA_QR_MODE_MS = 6 * 60 * 60_000L

        /** 这个窗口内再次命中"需要验证码"，判定为切换未生效的死循环。 */
        private const val CAPTCHA_LOOP_GUARD_MS = 120_000L

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
            // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 这类异常发生在主线程，不兜住就是"点一下闪退"。
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                com.jiuguan.ailink.core.SessionLog.add(
                    "qq",
                    "启动 QQ 服务失败：${error.javaClass.simpleName} ${error.message ?: ""}"
                )
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, QqBridgeService::class.java).setAction("stop")
            runCatching { context.startService(intent) }.onFailure {
                // 后台被拒时退化为直接停服务：少了优雅收尾，但不会把界面带崩
                com.jiuguan.ailink.core.SessionLog.add(
                    "qq",
                    "停止 QQ 服务失败，改为直接 stopService：${it.javaClass.simpleName}"
                )
                runCatching { context.stopService(Intent(context, QqBridgeService::class.java)) }
            }
        }

        /**
         * 重启容器内的 QQ（配置变更后用）。
         *
         * 与用户在界面上点「启动」的区别：这条路跳过启动冷却，因为用户刚刚明确改过配置，
         * 必须立即生效；但也不会在服务未运行时拉起进程（交给用户手动启动）。
         */
        fun restart(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, QqBridgeService::class.java).setAction("restart")
                )
            }.onFailure { error ->
                com.jiuguan.ailink.core.SessionLog.add(
                    "qq",
                    "重启 QQ 服务失败：${error.javaClass.simpleName} ${error.message ?: ""}"
                )
            }
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
    @Volatile
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
     * QQ 要求人工验证（短信/新设备）的判定。
     *
     * 时间戳落在 [AppPrefs] 里而不是内存：服务被重建后还要靠它决定"这次启动别带 -q"，
     * 放内存会丢，直接导致验证码死循环（见 10-25 那次日志）。
     */
    private fun captchaActive(): Boolean =
        AppPrefs(applicationContext).captchaUntil() > System.currentTimeMillis()

    /** 上一次处理"验证码"事件的时间，用于识别"切了二维码模式仍被要求验证"的循环。 */
    @Volatile
    private var lastCaptchaHandledAt = 0L

    /** 上一次记录"掉线"的时间，用于抑制 NapCat 自愈期间重复推送的离线事件。 */
    @Volatile
    private var lastOfflineLogAt = 0L

    /** 系统网络监听：切网/断网/弱网都会回调。 */
    private var networkWatcher: NetworkWatcher? = null

    /** 当前网络是否真正可用（VALIDATED）。不可用时不做容器重启，避免白折腾。 */
    @Volatile
    private var networkValidated = true

    /** 最近一次网络事件的时间，用来判断"这次掉线是不是紧跟换网"。 */
    @Volatile
    private var lastNetworkEventAt = 0L

    /** 最近一次断网的时间，用于识别"网络刚恢复"。 */
    @Volatile
    private var lastNetworkLostAt = 0L

    @Volatile
    private var lastNetworkLogAt = 0L

    /** 上一次已知的传输类型，用于断网时正确标注（断网后 transport 查不到了）。 */
    @Volatile
    private var lastCellular = false
    @Volatile
    private var lastWifi = false

    /** 一次断网只换算一次重启：系统会连发 onAvailable + onCapabilitiesChanged。 */
    @Volatile
    private var networkForwardedRestart = false

    /** 最近一次登录成功的时间，用于计算会话存活时长。 */
    @Volatile
    private var lastLoginAt = 0L

    /** 连续"短命会话"次数：登录后不足 3 分钟就掉，重启只会加重风控。 */
    @Volatile
    private var shortSessions = 0

    /** 疑似风控的冷静期截止时间；期间不自动重启。 */
    @Volatile
    private var cooldownUntil = 0L
    private var coolingLogged = false

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
        lifecycleLog("onCreate")
        createChannel()
        // 网络事件直接决定 QQ 长连接的生死：切 WiFi/移动数据、弱网掉线都在这里
        networkWatcher = NetworkWatcher(applicationContext) { available, validated, cellular ->
            handleNetworkChanged(available, validated, cellular)
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleLog("onStartCommand action=${intent?.action} startId=$startId running=${_running.value}")
        if (intent?.action == "stop") {
            shutdown()
            return START_NOT_STICKY
        }
        // 配置变更后的重启：只在服务本来就跑着时执行，不主动拉起
        if (intent?.action == "restart") {
            if (_running.value) {
                appendLog("[app] 收到配置变更，重启 QQ 生效")
                // relaunch 内部 terminate() 会阻塞等旧进程退出（最长 10 秒），
                // 放在主线程就是 ANR——ANR 被杀不经过崩溃处理器，表现为"无栈闪退"。
                scope.launch { relaunch(force = true) }
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
        // 手动启动 = 用户主动介入，清掉冷静期与短命会话计数
        cooldownUntil = 0L
        coolingLogged = false
        shortSessions = 0
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
    private fun launchAndWatch(runtime: LocalRuntime, uin: String, qrMode: Boolean = false) {
        lastLaunchAt = System.currentTimeMillis()

        // 启动前扫尾：上一轮的 QQ 可能还活着（shutdown 只发 SIGTERM 不等退出），
        // 它会继续占着 3001 与账号，导致本次启动的 QQ 只能停在二维码界面。
        val swept = runCatching { runtime.killStrayQq() }.getOrDefault(emptyList())
        val killed = swept.filter { it.startsWith("KILLED-") }
        if (killed.isNotEmpty()) {
            appendLog("[app] 已清理容器内残留进程：${killed.joinToString("、")}")
        }

        // 每次启动前按当前网络重写容器 DNS：换过卡/换过 WiFi 后，
        // 旧的 resolv.conf 可能指向已经不可达的 DNS，表现为"连不上、登录不了"。
        val dns = NetworkDns.writeResolvConf(applicationContext, runtime.runtimeDir)
        if (dns.isNotEmpty()) {
            appendLog("[net] 启动前刷新 DNS：${dns.take(3).joinToString(",")}")
        }

        // QQ 要求人工验证期间强制走二维码模式：带 -q 只会再触发一次"需要验证码"，
        // 而 NapCat 在验证码分支里不会生成二维码，界面就一直没有码可扫。
        // qrMode 由调用方显式传入，避免"状态读不到就退回带 -q"。
        val verifyMode = qrMode || captchaActive()
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
            // 与"旧进程退出"同类：如果这次失败属于上一轮启动，此时可能已经有新进程在跑，
            // 无条件 stopSelf 会把新实例停掉。只有确实没有进程时才停服务。
            if (process == null) {
                _running.value = false
                stopSelf()
            } else {
                lifecycleLog("旧启动失败（已有新进程在跑），不停服务")
            }
        }
        result.onSuccess { started ->
            process = started
            lastOutputAt = System.currentTimeMillis()
            appendLog("[app] QQ 进程已启动，等待二维码…")

            logJob = scope.launch {
                // relaunch()/shutdown() 的 destroy()/terminate() 会在别的线程关掉子进程 stdout，
                // 阻塞中的 readLine 随即抛 InterruptedIOException / IOException("Stream closed")。
                // 这不是错误，而是"日志流已关闭"；不接住它会逃逸到 UncaughtExceptionHandler
                // 直接带走进程（1.1.7 停止/重启必崩，[exit] reason=4 共 15 次）。
                try {
                    started.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            if (line.isNotBlank()) {
                                lastOutputAt = System.currentTimeMillis()
                                silentRetries = 0
                                appendLog(line)
                                runtime.appendLaunchLog(line)
                                parseLine(line)
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    lifecycleLog("QQ 日志流已关闭（进程被替换/停止）：${e.javaClass.simpleName}")
                }
                appendLog("[app] QQ 进程已退出")
                // forEachLine 是阻塞调用，logJob.cancel() 拦不住它：旧进程被 relaunch/shutdown
                // 结束后这里仍会执行到。只有"退出的就是当前进程"才代表服务该停，
                // 否则会把刚重启好的新实例连同服务一起停掉（日志里每次重启都重建 keepalive）。
                if (process === started) {
                    _running.value = false
                    stopSelf()
                } else {
                    lifecycleLog("旧 QQ 进程退出（已被替换/停止），不停服务")
                }
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
            relaunch(qrMode = captchaActive())
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
            // 冷静期：连续短命会话说明重启已经无效，继续重启只会把风控分刷得更高
            if (now < cooldownUntil) {
                if (!coolingLogged) {
                    coolingLogged = true
                    appendLog(
                        "[app] 冷静期剩余 ${(cooldownUntil - now) / 60_000} 分钟，" +
                            "暂停自动重启（期间手工扫码不受影响）"
                    )
                }
                offlineSince = 0L
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
                if (window == 0L) {
                    "[app] 立即重启 QQ 容器（第 $relaunchCount 次）"
                } else {
                    "[app] 离线超过 ${window / 1000} 秒，第 $relaunchCount 次重启 QQ 容器"
                } + if (backoff > 0) "（仍离线则按 ${backoff / 60_000} 分钟阶梯重试）" else ""
            )
            updateNotification("正在重启 QQ…")
            // 验证码模式下必须显式带上 qrMode，避免状态丢失后又退回带 -q 的死循环
            relaunch(qrMode = captchaActive())
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
        kotlinx.coroutines.withContext(Dispatchers.IO) { probePort(3001) }

    /** 阻塞式端口探测（调用方必须在 IO 线程）：本地回环，超时给足 800ms 即可。 */
    private fun probePort(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
        }
        true
    }.getOrDefault(false)

    /**
     * 网络状态变化处理。
     *
     * 三类事件分开对待：
     *  - 断网：只记录，不重启（没网重启也是白搭，还会白耗凭据）；
     *  - 已连接但未校验（弱网/认证页）：同样不重启；
     *  - 已校验：重写容器 DNS；若 QQ 正处于离线，立刻触发重启，不等 90 秒窗口。
     */
    private fun handleNetworkChanged(available: Boolean, validated: Boolean, cellular: Boolean) {
        // 系统回调线程上抛异常会直接带走整个进程，这里兜住
        runCatching { handleNetworkChangedInner(available, validated, cellular) }
            .onFailure { appendLog("[net] 处理网络变化失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun handleNetworkChangedInner(available: Boolean, validated: Boolean, cellular: Boolean) {
        val now = System.currentTimeMillis()

        if (!available) {
            lastNetworkEventAt = now
            lastNetworkLostAt = now
            networkValidated = false
            networkForwardedRestart = false
            if (now - lastNetworkLogAt > 3_000L) {
                lastNetworkLogAt = now
                // 断网时 transport 已经拿不到了，用上一次已知的类型标注，避免误报成 WiFi
                appendLog("[net] 网络已断开（原 ${lastNetworkKind()}）")
            }
            return
        }

        lastNetworkEventAt = now
        networkValidated = validated
        if (cellular) lastCellular = true else lastWifi = true

        if (!validated) {
            if (now - lastNetworkLogAt > 10_000L) {
                lastNetworkLogAt = now
                val kind = if (cellular) "移动数据" else "WiFi"
                appendLog("[net] 网络已连接但未校验通过（$kind），暂不重启容器")
            }
            return
        }

        val kind = if (cellular) "移动数据" else "WiFi"
        // 换网（WiFi↔移动数据）后运营商 DNS 会变，容器的 resolv.conf 必须跟着换
        val dns = NetworkDns.writeResolvConf(
            applicationContext,
            LocalRuntime(applicationContext).runtimeDir
        )
        val justRecovered = lastNetworkLostAt > 0 && now - lastNetworkLostAt < 120_000L
        if (now - lastNetworkLogAt > 10_000L || justRecovered) {
            lastNetworkLogAt = now
            appendLog(
                "[net] 网络已可用（$kind${if (justRecovered) "，刚恢复" else ""}），" +
                    "DNS=${dns.take(3).joinToString(",").ifBlank { "未取到" }}"
            )
        }

        // 一次断网只触发一次重启：onAvailable 与 onCapabilitiesChanged 会连着回调好几次，
        // 之前 14:23:20 和 14:23:22 各触发一次，日志里出现重复的"立即重启容器"。
        if (justRecovered && _offline.value && !networkForwardedRestart) {
            networkForwardedRestart = true
            forceRelaunch = true
            appendLog("[net] 网络恢复且 QQ 处于离线，立即重启容器")
        }
    }

    private fun lastNetworkKind(): String = when {
        lastCellular -> "移动数据"
        lastWifi -> "WiFi"
        else -> "网络"
    }

    /**
     * 记录本次会话的存活时长，并在"连续短命"时拉起冷静期。
     *
     * 依据：10-28 与 11-01 两份日志里，风控活跃期的会话存活从 1.5 小时塌到 22 秒～3 分钟，
     * 而每次重启都会产生新的登录事件——越重启，风控分越高，会话越短，形成正反馈。
     * 所以判定"重启已经没用"时，应当停下来等评分回落，而不是继续重试。
     */
    private fun noteSessionEnded(now: Long) {
        val loginAt = lastLoginAt
        if (loginAt <= 0) return
        val alive = now - loginAt

        // 网络刚抖过就掉线，归因给网络，不能算作风控信号。
        // 依据：11-01 早上的两次"短命会话"发生在地铁里，QQ 并未下发风险设备通知，
        // 把它们计入风控只会让冷静期误伤。
        val networkRecent = lastNetworkEventAt > 0 && now - lastNetworkEventAt < 5 * 60_000L
        if (alive >= SHORT_SESSION_MS || networkRecent) {
            if (shortSessions > 0) shortSessions = 0
            return
        }

        shortSessions += 1
        if (shortSessions >= SHORT_SESSION_LIMIT) {
            cooldownUntil = now + COOLDOWN_MS
            coolingLogged = false
            appendLog(
                "[app] 连续 $shortSessions 次会话不足 ${SHORT_SESSION_MS / 60_000} 分钟且期间无网络异常" +
                    "（疑似风控），暂停自动重启 ${COOLDOWN_MS / 60_000} 分钟"
            )
            updateNotification("疑似风控：暂停自动重启 ${COOLDOWN_MS / 60_000} 分钟")
        } else {
            appendLog("[app] 本次会话仅存活 ${alive / 1000} 秒（第 $shortSessions 次短命会话）")
        }
    }

    /** 掉线日志的附注：最近 2 分钟内有网络事件时标出来，便于事后归因。 */
    private fun networkHint(): String {
        val since = System.currentTimeMillis() - lastNetworkEventAt
        return if (lastNetworkEventAt > 0 && since < 120_000L) {
            "（最近 ${since / 1000}s 内有网络变化）"
        } else {
            ""
        }
    }

    /** 重启容器内的 QQ 进程（复用当前配置，含 -q 快速登录）。 */
    private fun relaunch(force: Boolean = false, qrMode: Boolean = false) {
        // 启动冷却：自愈循环与看门狗可能在同一秒内各自触发一次，
        // 第二次重启会把刚生成、还没被扫的二维码当场作废。
        // force = 用户在界面上明确改过配置，立即生效优先于冷却。
        val sinceLast = System.currentTimeMillis() - lastLaunchAt
        if (!force && lastLaunchAt > 0 && sinceLast < LAUNCH_COOLDOWN_MS) {
            appendLog("[app] 距上次启动仅 ${sinceLast / 1000}s，跳过本次重启（冷却中）")
            return
        }
        // 没网时重启只会白折腾一场，还会白白作废一次登录凭据
        if (!networkValidated) {
            appendLog("[app] 当前网络未通过校验（弱网/无网），跳过本次重启，等网络恢复")
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
        appendLog(
            "[app] 重启 QQ 容器（第 $relaunchCount 次）" +
                if (qrMode) "，本次为二维码模式（不带 -q）" else ""
        )
        launchAndWatch(runtime, uin, qrMode = qrMode)
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
            AppPrefs(applicationContext).clearCaptchaUntil()
            lastCaptchaHandledAt = 0L
            _needVerify.value = false
            _verifyUrl.value = ""
            // 登录成功 = 用户已介入（多半刚扫码），把"被踢后暂停自愈"的状态还回去
            autoRestartSuppressed = false
            suppressedLogged = false
            // 这两行每次登录会各命中一次，只在首次迁移时落一条日志，
            // 否则日志里看不到"登录已就绪"这个关键锚点（NapCat 自己不打印在线）
            if (!wasLoggedIn) {
                appendLog("[app] 登录成功")
                lastLoginAt = System.currentTimeMillis()
            }
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
            // NapCat 在自愈过程中会把这条重复推好几次（实测 09:44 连推 4 条），
            // 5 秒内只记一条，否则日志被同一事件刷屏。
            val now = System.currentTimeMillis()
            if (now - lastOfflineLogAt > 5_000L) {
                lastOfflineLogAt = now
                _offline.value = true
                noteSessionEnded(now)
                appendLog("[app] 检测到 QQ 掉线，90 秒内未恢复将自动重启容器${networkHint()}")
                updateNotification("QQ 掉线，正在等待恢复")
            }
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
        // 优先用 NapCat 自己打印的 User Panel Url（形如 /webui?token=…），
        // 不要拼 /webui/web_login —— 那是带账号密码表单的登录页，点进去只会"登录失败"。
        if (line.contains("WebUi User Panel Url:")) {
            val url = line.substringAfter("WebUi User Panel Url:").trim()
            if (url.startsWith("http") && !url.contains("[::]")) {
                _verifyUrl.value = url
            }
        }
        if (line.contains("WebUi Token:") && _verifyUrl.value.isBlank()) {
            val token = line.substringAfter("WebUi Token:").trim()
            if (token.isNotBlank()) {
                _verifyUrl.value = "http://127.0.0.1:6099/webui?token=$token"
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
            val now = System.currentTimeMillis()
            // 落盘：6 小时后自动失效（借鉴 XiaoYou 的降级标记 TTL），且跨服务重建保持
            AppPrefs(applicationContext).setCaptchaUntil(now + CAPTCHA_QR_MODE_MS)
            _needVerify.value = true
            _loggedIn.value = false
            _loginExpired.value = true

            val looped = lastCaptchaHandledAt > 0 && now - lastCaptchaHandledAt < CAPTCHA_LOOP_GUARD_MS
            lastCaptchaHandledAt = now
            if (looped) {
                // 已经切到二维码模式还被要求验证：说明本轮改动没生效，再重启只会原地打转
                autoRestartSuppressed = true
                suppressedLogged = false
                appendLog(
                    "[app] 检测到验证码循环（已切二维码模式仍被要求验证），停止自动重启；" +
                        "请手动扫码，或在设置里清空 QQ 密码强制走扫码"
                )
                updateNotification("需要人工处理：验证码循环，请扫码")
                return
            }

            _offline.value = true
            autoRestartSuppressed = false
            forceRelaunch = true
            val webuiReachable = probePort(6099)
            appendLog(
                "[app] 密码回退被 QQ 要求人工验证；改为二维码登录（6 小时内保持扫码模式）" +
                    if (_verifyUrl.value.isNotBlank()) {
                        "。WebUI ${if (webuiReachable) "可达" else "不可达"}：${_verifyUrl.value}"
                    } else {
                        "。WebUI 地址未获取到，只能扫码"
                    }
            )
            updateNotification("需要人工验证：请扫码，或在 WebUI 完成短信验证")
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

    /**
     * 追加一行到界面日志，并持久化"我们自己的"日志。
     *
     * 只有 `[app]` / `[net]` 前缀的行会进 SessionLog：容器原始输出（QQ/NapCat 的 stdout）
     * 已经由 launch log 落盘，再抄一份只会把会话日志刷成几万行。
     * 之前只认 `[app]`，导致 `[net]` 网络事件在导出的日志里彻底消失——排查时等于瞎子。
     */
    private fun appendLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(500)
        val tag = when {
            line.startsWith("[app]") -> "qq"
            line.startsWith("[net]") -> "net"
            else -> return
        }
        com.jiuguan.ailink.core.SessionLog.add(tag, line.substringAfter("] ").trim())
    }

    private fun shutdown() {
        lifecycleLog("shutdown")
        val dying = process
        process = null
        runCatching { logJob?.cancel() }
        runCatching { qrJob?.cancel() }
        _running.value = false
        runCatching { stopForegroundCompat() }
        runCatching { keepAlive.release() }
        runCatching { stopSelf() }
        // 必须等旧容器真正退出：只发 SIGTERM 的话，服务 3 秒后就被重新拉起，
        // 而旧 QQ 还占着 3001 与账号，新进程只能停在二维码界面（界面与事实脱节）。
        // 用独立线程等待，避免阻塞主线程（onStartCommand 在主线程调用这里）。
        if (dying != null) {
            Thread({
                val exited = LocalRuntime(applicationContext).terminate(dying)
                lifecycleLog(if (exited) "旧容器已退出，服务收尾完成" else "旧容器 10 秒未退出，已强制结束")
            }, "qq-shutdown").start()
        }
    }

    override fun onDestroy() {
        // 收尾阶段任何一步抛异常都会让进程崩在 onDestroy 里（表现为"点停止就闪退"），
        // 逐步兜住：宁可少释放一个锁，也不能崩。
        lifecycleLog("onDestroy")
        runCatching { keepAlive.release() }
        runCatching { networkWatcher?.stop() }
        networkWatcher = null
        runCatching { logJob?.cancel() }
        runCatching { qrJob?.cancel() }
        runCatching { process?.destroy() }
        process = null
        runCatching { scope.cancel() }
        _running.value = false
        runCatching { super.onDestroy() }
    }

    /** 服务生命周期埋点：带实例 id，用来判断服务是否在重启过程中被反复重建。 */
    private fun lifecycleLog(event: String) {
        com.jiuguan.ailink.core.SessionLog.add(
            "svc",
            "qq@${Integer.toHexString(System.identityHashCode(this))} $event " +
                "thread=${Thread.currentThread().name}"
        )
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
