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
        container = AppContainer(this)
        container.bootstrap()
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
