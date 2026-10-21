package com.jiuguan.ailink.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.jiuguan.ailink.core.PairingBus
import com.jiuguan.ailink.assembly.AppHost
import com.jiuguan.ailink.model.PairingParser
import com.jiuguan.ailink.ui.nav.AppNavHost
import com.jiuguan.ailink.ui.theme.AiLinkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePairingIntent(intent)
        resumeQqBridgeIfNeeded()
        setContent {
            AiLinkTheme {
                NotificationPermissionEffect()
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    /**
     * 已配置 QQ 号且运行时已安装时，自动恢复内置 QQ。
     *
     * 带 -q 启动会走本地凭据，一般不弹二维码；这样用户重新打开 App 不必手动点启动。
     */
    private fun resumeQqBridgeIfNeeded() {
        val container = (applicationContext as AppHost).container
        if (container.prefs.qqUin.value.isBlank()) return
        val runtime = com.jiuguan.ailink.engine.container.LocalRuntime(applicationContext)
        if (!runtime.isQqRuntimeInstalled()) return
        if (com.jiuguan.ailink.engine.service.QqBridgeService.running.value) return
        com.jiuguan.ailink.engine.service.QqBridgeService.start(applicationContext)
    }

    /** 外部 ailink:// 链接唤起时，把配对信息投递给界面层。 */
    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val info = PairingParser.parse(data.toString()) ?: return
        PairingBus.offer(info)
    }
}

/** 首次进入时申请通知权限：前台服务通知与消息提醒都依赖它。 */
@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val prefs = (context.applicationContext as AppHost).container.prefs
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) prefs.markNotificationAsked()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.notificationAsked.value) {
            prefs.markNotificationAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
