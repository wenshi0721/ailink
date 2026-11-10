package com.jiuguan.ailink

import android.app.Application
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.assembly.AppHost
import com.jiuguan.ailink.core.SessionLog
import com.jiuguan.ailink.engine.bot.BotManager
import com.jiuguan.ailink.engine.di.BotHost
import java.io.File

/**
 * 应用入口：同时实现两个窄接口，把容器分别暴露给需要的模块。
 * - [AppHost] 给 `:ui`（每个 ViewModel 都要 AppContainer）
 * - [BotHost] 给 `:engine`（前台服务只需要 BotManager）
 *
 * 这样两个模块都不必强转本类，依赖方向保持单向。
 */
class AiLinkApp : Application(), AppHost, BotHost {

    override lateinit var container: AppContainer
        private set

    override val botManager: BotManager get() = container.botManager

    override fun onCreate() {
        super.onCreate()
        // 会话日志落盘：导出往往发生在"复现完 → 重启 App → 再导出"之后，
        // 只放内存的话现场已经没了。放在容器初始化之前，尽早接上。
        SessionLog.attach(File(filesDir, "session.log"))
        installCrashLogger()
        recordLastExitReasons()
        container = AppContainer(this)
        container.bootstrap()
    }

    /**
     * 上一次进程为什么死（API 30+）。
     *
     * UncaughtExceptionHandler 只能看到 Java 异常；ANR 被杀、低内存回收、信号杀、native 崩溃
     * 都不经过它——"闪退但没有 [crash]"正是这一类。系统会把真实死因留在
     * ApplicationExitInfo 里，这里在下次启动时补记进会话日志（tag=exit），
     * ANR / native 崩溃附带系统 trace 前 60 行。只记上次之后的新记录，避免重复。
     */
    private fun recordLastExitReasons() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        Thread({
            runCatching {
                val am = getSystemService(android.app.ActivityManager::class.java)
                val prefs = getSharedPreferences("diag", MODE_PRIVATE)
                val lastSeen = prefs.getLong("exit_ts", 0L)
                val infos = am.getHistoricalProcessExitReasons(packageName, 0, 10)
                    .filter { it.timestamp > lastSeen }
                    .sortedBy { it.timestamp }
                val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                infos.forEach { info ->
                    SessionLog.add(
                        "exit",
                        "上次退出 ${fmt.format(java.util.Date(info.timestamp))} " +
                            "进程=${info.processName} reason=${info.reason} " +
                            "status=${info.status} importance=${info.importance} " +
                            "pss=${info.pss}KB rss=${info.rss}KB desc=${info.description ?: ""}"
                    )
                    val withTrace = info.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                        info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                    if (withTrace) {
                        runCatching {
                            info.traceInputStream?.bufferedReader()?.useLines { lines ->
                                lines.take(60).forEach { SessionLog.add("exit", "    $it") }
                            }
                        }
                    }
                }
                infos.maxOfOrNull { it.timestamp }?.let {
                    prefs.edit().putLong("exit_ts", it).apply()
                }
            }
        }, "exit-info").start()
    }

    /**
     * 崩溃落盘。
     *
     * 闪退的真实栈只在 logcat 里，而用户拿不到 logcat——每次只能靠"猜 + 加日志 + 再复现"。
     * 这里把未捕获异常写进会话日志（含线程名与栈），下次导出日志就能直接看到崩因。
     * 记录完仍交给原处理器，保持系统的崩溃处理行为（弹窗/杀进程）不变。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                SessionLog.add(
                    "crash",
                    "未捕获异常 线程=${thread.name} ${error.javaClass.name}: ${error.message ?: ""}"
                )
                error.stackTraceToString()
                    .lineSequence()
                    .take(40)
                    .forEach { SessionLog.add("crash", "    $it") }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
