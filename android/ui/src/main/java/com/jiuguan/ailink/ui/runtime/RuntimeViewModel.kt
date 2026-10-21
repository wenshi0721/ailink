package com.jiuguan.ailink.ui.runtime

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

    private var qqProcess: Process? = null
    private var qrJob: Job? = null

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

    /** 导出日志到文件（安装 + 启动合并）。 */
    fun exportLog(context: Context): String {
        val text = readFullLog()
        if (text.isBlank()) return "暂无日志可导出"
        return runCatching {
            val result = LogExporter.export(context, "ailink-log", text)
            LogExporter.share(context, result, "AiLink 日志")
            "已导出到 ${result.location}"
        }.getOrElse { error ->
            "导出失败：${error.message ?: "未知错误"}"
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
