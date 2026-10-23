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
        container = AppContainer(this)
        container.bootstrap()
    }
}
