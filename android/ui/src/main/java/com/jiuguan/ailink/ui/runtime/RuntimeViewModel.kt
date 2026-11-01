package com.jiuguan.ailink.ui.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.engine.service.QqBridgeService
import com.jiuguan.ailink.engine.service.RuntimeInstallService
import com.jiuguan.ailink.engine.container.LocalRuntime
import com.jiuguan.ailink.engine.container.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuntimeViewModel(private val container: AppContainer) : ViewModel() {

    private val runtime = LocalRuntime(container.appContext)

    var report: LocalRuntime.Report by mutableStateOf(runtime.inspect())
        private set

    var testing by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    var progress by mutableStateOf("")
        private set

    var containerOutput by mutableStateOf("")
        private set

    // ---- QQ 运行时 ----
    var qqInstalled by mutableStateOf(runtime.isQqRuntimeInstalled())
        private set

    var installing by mutableStateOf(false)
        private set

    var qqRunning by mutableStateOf(false)
        private set

    var logs by mutableStateOf<List<String>>(emptyList())
        private set

    var qrCode by mutableStateOf<Bitmap?>(null)
        private set

    var qrVersion by mutableStateOf(0)
        private set

    var decodeUrl by mutableStateOf<String?>(null)
        private set

    var loggedIn by mutableStateOf(false)
        private set

    /** QQ 要求人工验证（短信/新设备）：此时只有扫码或在 NapCat WebUi 里完成验证两条路。 */
    var needVerify by mutableStateOf(false)
        private set

    /** 可直接打开的 NapCat WebUi 验证页地址（验证必须在这里做，直接开腾讯链接无效）。 */
    var verifyUrl by mutableStateOf("")
        private set

    var quickLoginFailed by mutableStateOf(false)
        private set

    var loginExpired by mutableStateOf(false)
        private set

    /** 免扫码快速登录用的 QQ 号（空表示每次都扫码）。 */
    var qqUin by mutableStateOf(container.prefs.qqUin.value)
        private set

    fun updateQqUin(value: String) {
        qqUin = value
        container.prefs.setQqUin(value)
    }

    /** 密码输入框的内容：只存在于内存，保存后立刻清空。 */
    var qqPasswordInput by mutableStateOf("")
        private set

    /** 是否已保存密码（界面上只显示这个布尔值，不回显密码）。 */
    var qqPasswordSet by mutableStateOf(container.prefs.qqPasswordSet.value)
        private set

    /** 密码相关的即时提示（保存成功/清除/Keystore 不可用）。 */
    var passwordNotice by mutableStateOf("")
        private set

    fun updateQqPasswordInput(value: String) {
        qqPasswordInput = value
    }

    fun saveQqPassword() {
        if (qqPasswordInput.isBlank()) return
        container.prefs.setQqPassword(qqPasswordInput)
        qqPasswordInput = ""
        qqPasswordSet = container.prefs.qqPasswordSet.value
        passwordNotice = if (qqPasswordSet) {
            "已保存，凭据失效时将自动登录"
        } else {
            "保存失败：本机 Keystore 不可用"
        }
    }

    fun clearQqPassword() {
        container.prefs.clearQqPassword()
        qqPasswordSet = false
        qqPasswordInput = ""
        passwordNotice = "已清除，此后凭据失效只能扫码"
    }

    private var qqProcess: Process? = null
    private var qrJob: Job? = null

    /** 反检测加固开关（写 NapCat 配置 + 重启 QQ 生效，可一键还原）。 */
    var antiDetect by mutableStateOf(container.prefs.antiDetect.value)
        private set

    /** 开关操作的即时反馈：成功会带上当前配置摘要。 */
    var antiDetectNotice by mutableStateOf("")
        private set

    fun toggleAntiDetect(enabled: Boolean) {
        val uin = container.prefs.qqUin.value
        val result = com.jiuguan.ailink.engine.container.NapcatConfig
            .setHardened(runtime.runtimeDir, uin, enabled)
        result.fold(
            onSuccess = { state ->
                container.prefs.setAntiDetect(enabled)
                antiDetect = enabled
                antiDetectNotice =
                    "已${if (enabled) "开启" else "还原"}：${state.summary}；正在重启 QQ 使其生效"
                // 服务在跑才重启；没在跑就等用户自己点启动
                QqBridgeService.restart(container.appContext)
            },
            onFailure = { error ->
                // 写失败不改变开关状态，避免"界面说开了、实际没写进去"
                antiDetectNotice = "写入失败：${error.message ?: "未知错误"}"
            }
        )
    }

    /** 读取当前 NapCat 配置，用于在界面上显示真实状态（而不是只信偏好里的布尔值）。 */
    fun describeAntiDetect(): String {
        val state = com.jiuguan.ailink.engine.container.NapcatConfig
            .read(runtime.runtimeDir, container.prefs.qqUin.value)
            ?: return "未找到 NapCat 配置（需先安装运行时并登录一次）"
        return state.summary
    }

    fun refresh() {
        val previous = report
        report = runtime.inspect().copy(
            execOutput = previous.execOutput,
            execLevel = previous.execLevel
        )
        qqInstalled = runtime.isQqRuntimeInstalled()
    }

    fun selfTest() {
        if (busy) return
        busy = true
        testing = true
        progress = ""
        viewModelScope.launch {
            report = runtime.selfTest()
            testing = false
            busy = false
        }
    }

    fun installRootfs() {
        if (busy) return
        busy = true
        containerOutput = ""
        progress = "准备解压…"
        viewModelScope.launch {
            runtime.installRootfs { message -> progress = message }
                .onSuccess { summary ->
                    progress = summary
                    refresh()
                }
                .onFailure { error ->
                    progress = "安装失败：${error.message ?: "未知错误"}"
                }
            busy = false
        }
    }

    fun runContainerProbe() {
        if (busy) return
        busy = true
        containerOutput = "正在进入容器…"
        viewModelScope.launch {
            runtime.containerProbe()
                .onSuccess { output -> containerOutput = output }
                .onFailure { error ->
                    containerOutput = "执行失败：${error.message ?: "未知错误"}"
                }
            busy = false
        }
    }

    init {
        // 打开页面时先把已落盘的日志读出来，重启后也能看到/导出上次的日志
        val persisted = runtime.readInstallLog()
        if (persisted.isNotBlank()) logs = persisted.lines().takeLast(200)

        // QQ 运行态由前台服务托管，这里只做镜像
        viewModelScope.launch {
            QqBridgeService.running.collect { qqRunning = it }
        }
        viewModelScope.launch {
            QqBridgeService.logs.collect { list ->
                if (list.isNotEmpty()) logs = list.takeLast(200)
            }
        }
        viewModelScope.launch {
            QqBridgeService.qr.collect { bytes ->
                qrCode = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }
        viewModelScope.launch {
            QqBridgeService.qrVersion.collect { qrVersion = it }
        }
        viewModelScope.launch {
            QqBridgeService.decodeUrl.collect { decodeUrl = it }
        }
        viewModelScope.launch {
            QqBridgeService.loggedIn.collect { loggedIn = it }
        }
        viewModelScope.launch {
            QqBridgeService.needVerify.collect { needVerify = it }
        }
        viewModelScope.launch {
            QqBridgeService.verifyUrl.collect { verifyUrl = it }
        }
        viewModelScope.launch {
            QqBridgeService.quickLoginFailed.collect { quickLoginFailed = it }
        }
        viewModelScope.launch {
            QqBridgeService.loginExpired.collect { loginExpired = it }
        }

        // 安装在前台服务里跑，界面只做镜像：切走应用、熄屏都不会中断
        viewModelScope.launch {
            RuntimeInstallService.running.collect { installing = it }
        }
        viewModelScope.launch {
            RuntimeInstallService.lines.collect { list ->
                if (list.isNotEmpty()) logs = list.takeLast(200)
            }
        }
        viewModelScope.launch {
            RuntimeInstallService.finished.collect { message ->
                if (!message.isNullOrBlank()) {
                    appendLog(message)
                    refresh()
                }
            }
        }
    }

    /** 安装 QQ + NapCat（约 350 MB，需要 Wi-Fi）。交给前台服务执行。 */
    fun installQq() {
        if (installing) return
        logs = emptyList()
        RuntimeInstallService.start(container.appContext)
    }

    /** 启动容器内的 QQ：交给前台服务托管，切到手机 QQ 扫码也不会被回收。 */
    fun startQq() {
        QqBridgeService.clear()
        QqBridgeService.start(container.appContext)
    }

    fun stopQq() {
        QqBridgeService.stop(container.appContext)
    }

    /**
     * 分享二维码图片（微信/QQ 发到电脑即可扫）。
     *
     * 为什么必须走分享：QQ 对 PC 端登录授权**禁止相册识别与长按识别**，
     * 只能用摄像头扫真实屏幕。所以二维码必须在"另一块屏幕"上显示。
     */
    fun shareQr(context: Context): String {
        val bytes = QqBridgeService.qr.value ?: return "还没有二维码"
        return runCatching {
            val name = "ailink-qq-qr-${System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/AiLink"
                    )
                }
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return "分享失败：无法创建图片"
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "把二维码发到电脑")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "选择微信/QQ 发到电脑，在电脑上打开后用手机 QQ 摄像头扫码"
        }.getOrElse { error -> "分享失败：${error.message ?: "未知错误"}" }
    }

    /** 把当前二维码存进相册——单手机登录时用它：手机 QQ → 扫一扫 → 相册 → 选图。 */
    fun saveQrToGallery(context: Context): String {
        val bytes = QqBridgeService.qr.value ?: return "还没有二维码"
        return runCatching {
            val name = "ailink-qq-qr-${System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/AiLink"
                    )
                }
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return "保存失败：无法创建文件"
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            "已保存到相册（Pictures/AiLink）\n打开手机 QQ → 扫一扫 → 右上角相册 → 选择这张图"
        }.getOrElse { error -> "保存失败：${error.message ?: "未知错误"}" }
    }


    fun readFullLog(): String {
        val install = runtime.readInstallLogFull()
        val launch = runtime.readLaunchLog()
        return buildString {
            if (install.isNotBlank()) {
                appendLine("===== 安装日志 =====")
                appendLine(install)
            }
            if (launch.isNotBlank()) {
                appendLine("===== 启动日志 =====")
                appendLine(launch)
            }
            val session = com.jiuguan.ailink.core.SessionLog.dump()
            if (session.isNotBlank()) {
                appendLine("===== 会话日志（AiLink 侧） =====")
                appendLine(session)
            }
        }.ifBlank { logs.joinToString("\n") }
    }

    /**
     * 组装完整日志包：环境信息 + 安装日志 + 容器启动日志 + 会话日志。
     *
     * 环境信息不是装饰：一份没有版本号、没有网络类型的日志，事后基本无法归因
     * （"是哪个包出的问题""当时是 WiFi 还是移动数据"都只能靠追问用户）。
     */
    fun buildLogBundle(): String = buildString {
        val info = runCatching {
            container.appContext.packageManager
                .getPackageInfo(container.appContext.packageName, 0)
        }.getOrNull()
        val versionCode = info?.let {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)
        } ?: 0L

        appendLine("===== 环境信息 =====")
        appendLine("导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("应用版本：${info?.versionName ?: "?"}（code $versionCode）")
        appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("系统：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
        appendLine("网络：${currentNetworkLabel()}")
        appendLine("QQ 运行时：${if (QqBridgeService.running.value) "运行中" else "已停止"}")
        appendLine("QQ 登录：${if (QqBridgeService.loggedIn.value) "已登录" else "未登录"}")
        appendLine("离线标记：${if (QqBridgeService.offline.value) "是" else "否"}")
        appendLine("容器：${if (runtime.isQqRuntimeInstalled()) "已安装" else "未安装"}")
        appendLine()

        val install = runtime.readInstallLogFull()
        if (install.isNotBlank()) {
            appendLine("===== 安装日志 =====")
            appendLine(install)
        }
        val launch = runtime.readLaunchLog()
        if (launch.isNotBlank()) {
            appendLine("===== 容器日志（QQ/NapCat 输出） =====")
            appendLine(launch)
        }
        val session = com.jiuguan.ailink.core.SessionLog.dump()
        if (session.isNotBlank()) {
            appendLine("===== 会话日志（AiLink 侧） =====")
            appendLine(session)
        }
    }

    /** 日志规模摘要：让用户在导出前知道文件大概多大，不用猜（只查文件长度，不读内容）。 */
    fun logSummary(): String {
        val sessionLines = com.jiuguan.ailink.core.SessionLog.lineCount()
        val (launchBytes, installBytes) = runtime.logSizes()
        fun kb(bytes: Long) = if (bytes < 1024) "${bytes}B" else "${bytes / 1024}KB"
        return "会话 $sessionLines 行 · 容器日志 ${kb(launchBytes)} · 安装日志 ${kb(installBytes)}"
    }

    /** 界面尾部预览：优先显示会话日志最后几行（安装期之外也有内容可看）。 */
    fun logTail(lines: Int = 8): String {
        val session = com.jiuguan.ailink.core.SessionLog.tail(lines)
        if (session.isNotBlank()) return session
        return logs.takeLast(lines).joinToString("\n")
    }

    private fun currentNetworkLabel(): String {
        val manager = container.appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return "未知"
        val network = manager.activeNetwork ?: return "无网络"
        val caps = manager.getNetworkCapabilities(network) ?: return "未知"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            else -> "其他"
        }
    }

    /**
     * 导出日志到文件。
     *
     * 与旧实现的两点区别：
     *  - 不再依赖"安装日志有没有内容"这个前提——QQ 正常运行时也要能导出；
     *  - 组装（可能几 MB）与写文件都放到 IO 线程，主线程只负责弹提示与拉起分享，
     *    避免点一下导出就卡死/被系统判为无响应。
     */
    fun exportLog(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { buildLogBundle() }
            if (text.isBlank()) {
                onResult("暂无日志可导出")
                return@launch
            }
            val exported = withContext(Dispatchers.IO) {
                runCatching { LogExporter.export(context, "ailink-log", text) }.getOrNull()
            }
            if (exported == null) {
                onResult("导出失败：无法写入下载目录")
                return@launch
            }
            val shared = runCatching { LogExporter.share(context, exported, "AiLink 日志") }.isSuccess
            onResult(
                if (shared) "已导出到 ${exported.location}（已打开分享）"
                else "已导出到 ${exported.location}"
            )
        }
    }

    /** 复制完整日志到剪贴板（同样在 IO 线程组装）。 */
    fun copyAllLog(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { buildLogBundle() }
            if (text.isBlank()) {
                onResult("暂无日志可复制")
                return@launch
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ailink-log", text))
            onResult("已复制 ${text.length / 1024} KB")
        }
    }


    private fun appendLog(line: String) {
        logs = (logs + line).takeLast(200)
    }

    override fun onCleared() {
        super.onCleared()
        // 进程由 QqBridgeService 托管，界面销毁不影响 QQ 运行
    }
}
