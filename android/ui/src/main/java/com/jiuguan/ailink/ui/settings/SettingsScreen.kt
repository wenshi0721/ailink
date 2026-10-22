package com.jiuguan.ailink.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.BannerTone
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SectionHeader
import com.jiuguan.ailink.ui.common.SettingRow
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.data.update.UpdateState

@Composable
fun SettingsScreen(
    nav: NavController,
    vm: SettingsViewModel = appViewModel { SettingsViewModel(it) }
) {
    val running by vm.engineRunning.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val profiles by vm.aiProfiles.collectAsStateWithLifecycle()
    val totalMessages by vm.totalMessages.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val updateMirror by vm.updateMirror.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showMirror by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(14.dp))

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("接入服务", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(3.dp))
                    MutedText(if (running) "前台服务运行中，退出应用仍保持在线" else "已停止，账号不会收发消息")
                }
                Switch(
                    checked = running,
                    onCheckedChange = vm::setEngineRunning,
                    colors = appSwitchColors()
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("配置")
        Spacer(Modifier.height(8.dp))
        AppCard {
            SettingRow(
                title = "AI 服务",
                subtitle = "已配置 ${profiles.size} 个（内置模拟回复始终可用）",
                onClick = { nav.navigate("ai") },
                trailing = { Chevron() }
            )
            SoftDivider()
            SettingRow(
                title = "账号管理",
                subtitle = "共 ${accounts.size} 个账号，${accounts.count { it.enabled }} 个参与接入",
                onClick = { nav.navigate("accounts") },
                trailing = { Chevron() }
            )
            SoftDivider()
            SettingRow(
                title = "手机内置运行时（QQ）",
                subtitle = "不依赖电脑与 Termux，查看执行链路就绪度",
                onClick = { nav.navigate("runtime") },
                trailing = { Chevron() }
            )
            SoftDivider()
            SettingRow(
                title = "息屏保持在线（电池白名单）",
                subtitle = "加入忽略电池优化，避免息屏后消息中断",
                onClick = {
                    val context = nav.context
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + context.packageName)
                    )
                    runCatching { context.startActivity(intent) }
                },
                trailing = { Chevron() }
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("数据")
        Spacer(Modifier.height(8.dp))
        AppCard {
            SettingRow(
                title = "消息记录",
                subtitle = "本地共保存 $totalMessages 条消息",
                trailing = { MutedText("Room 本地库") }
            )
            SoftDivider()
            SettingRow(
                title = "清空全部聊天记录",
                subtitle = "只删除消息，不影响账号与 AI 配置",
                onClick = { confirmClear = true },
                trailing = {
                    Text("清空", color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                }
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("关于")
        Spacer(Modifier.height(8.dp))
        AppCard {
            SettingRow(
                title = "版本",
                trailing = { MutedText("v${vm.versionName}") }
            )
            SoftDivider()
            UpdateRow(
                state = updateState,
                onCheck = vm::checkUpdate,
                onDownload = vm::downloadUpdate,
                onInstall = { file -> AppUpdateInstaller.install(context, file) }
            )
            SoftDivider()
            SettingRow(
                title = "下载加速镜像（可选）",
                subtitle = if (updateMirror.isBlank()) "未设置，直连 GitHub；国内网络慢时可填加速前缀"
                else updateMirror,
                onClick = { showMirror = true },
                trailing = { Chevron() }
            )
            SoftDivider()
            SettingRow(
                title = "接入协议与合规说明",
                subtitle = "非官方协议的风险提示与使用边界",
                onClick = { showAbout = true },
                trailing = { Chevron() }
            )
        }

        Spacer(Modifier.height(16.dp))

        InfoBanner(
            title = "合规使用提示",
            message = "请仅接入你本人拥有合法使用权的账号，并遵守平台用户协议。通过非官方协议接入 QQ / 微信 存在被限制登录的风险，建议使用小号或独立设备测试。",
            tone = BannerTone.WARNING
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = "接入助手 · 本地数据不上传 · 仅用于个人学习与自建服务",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(28.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部聊天记录") },
            text = { Text("将删除所有账号的会话与消息，操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllMessages()
                    confirmClear = false
                }) {
                    Text("确认清空", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("接入协议与合规说明") },
            text = {
                Column {
                    Text("一、接入方式", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    MutedText("应用通过 OneBot v11 正向 WebSocket 与你自建的桥接端通信，本身不破解、不代理任何官方协议。")
                    Spacer(Modifier.height(10.dp))
                    Text("二、账号风险", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    MutedText("第三方桥接端属于非官方实现，可能触发平台风控导致限制登录。请勿用于营销群发、自动加好友等行为。")
                    Spacer(Modifier.height(10.dp))
                    Text("三、数据与隐私", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    MutedText("账号配置、消息记录全部保存在本机 Room 数据库；AI 调用仅发送当前会话上下文，不经过第三方中转。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("我知道了") }
            }
        )
    }

    if (showMirror) {
        var mirrorInput by remember { mutableStateOf(updateMirror) }
        AlertDialog(
            onDismissRequest = { showMirror = false },
            title = { Text("下载加速镜像") },
            text = {
                Column {
                    MutedText("GitHub Releases 直连较慢时，可填加速镜像前缀，例如：")
                    Spacer(Modifier.height(4.dp))
                    MutedText("https://gh-proxy.com/")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = mirrorInput,
                        onValueChange = { mirrorInput = it },
                        singleLine = true,
                        placeholder = { Text("留空 = 直连 GitHub") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setUpdateMirror(mirrorInput)
                    showMirror = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showMirror = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.Default.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 检查更新行：随 [UpdateState] 切换文案与点击行为。 */
@Composable
private fun UpdateRow(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (com.jiuguan.ailink.data.update.UpdateInfo) -> Unit,
    onInstall: (java.io.File) -> Boolean
) {
    when (state) {
        is UpdateState.Checking -> SettingRow(
            title = "检查更新",
            subtitle = "正在连接 GitHub Releases…",
            trailing = { MutedText("…") }
        )

        is UpdateState.UpToDate -> SettingRow(
            title = "检查更新",
            subtitle = "当前已是最新版本",
            onClick = onCheck,
            trailing = { Text("检查", color = OnlineGreen, style = MaterialTheme.typography.bodyMedium) }
        )

        is UpdateState.Available -> SettingRow(
            title = "发现新版本 v${state.info.version}",
            subtitle = "点击下载更新包（约 ${state.info.apkSizeBytes / 1024 / 1024} MB）",
            onClick = { onDownload(state.info) },
            trailing = { Text("下载", color = OnlineGreen, style = MaterialTheme.typography.bodyMedium) }
        )

        is UpdateState.Downloading -> {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("下载更新 v${state.info.version}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(3.dp))
                        MutedText("下载中 ${state.percent}%")
                    }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        is UpdateState.Downloaded -> SettingRow(
            title = "更新包已就绪 v${state.info.version}",
            subtitle = "点击交给系统安装器安装（安装后应用会自动重启）",
            onClick = { onInstall(state.file) },
            trailing = { Text("安装", color = OnlineGreen, style = MaterialTheme.typography.bodyMedium) }
        )

        is UpdateState.Failed -> SettingRow(
            title = "检查更新",
            subtitle = "失败：${state.message}（可配置镜像后重试）",
            onClick = onCheck,
            trailing = { Text("重试", color = DangerRed, style = MaterialTheme.typography.bodyMedium) }
        )

        is UpdateState.Idle -> SettingRow(
            title = "检查更新",
            subtitle = "从 GitHub Releases 检查新版本",
            onClick = onCheck,
            trailing = { Text("检查", color = OnlineGreen, style = MaterialTheme.typography.bodyMedium) }
        )
    }
}
