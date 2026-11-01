package com.jiuguan.ailink.ui.runtime

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.navigation.NavController
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.BannerTone
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.TagChip
import com.jiuguan.ailink.core.AppVersion
import com.jiuguan.ailink.engine.container.LocalRuntime
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.ui.theme.WarnAmber

@Composable
fun RuntimeScreen(
    nav: NavController,
    vm: RuntimeViewModel = appViewModel { RuntimeViewModel(it) }
) {
    val report = vm.report
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "手机内置运行时",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "QQ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            MutedText(
                "App ${AppVersion.label(context)} · 运行时脚本 ${LocalRuntime.SCRIPT_VERSION}"
            )
            Spacer(Modifier.height(8.dp))

            InfoBanner(
                title = "这是 QQ 单机运行时的底座",
                message = "Android 10 起，应用不能执行自己数据目录里的二进制。这个页面用来验证唯一合法路径：执行 APK 内嵌、已解压到 native 库目录的二进制。",
                tone = BannerTone.INFO
            )

            if (report.items.any { it.label == "Linux rootfs" && !it.ok }) {
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    title = "首次使用：还差一步",
                    message = "先点下方「安装 / 重装最小运行时」（约 30 秒），再点「进入容器执行 uname」。rootfs 只需装一次。",
                    tone = BannerTone.WARNING
                )
            }

            Spacer(Modifier.height(14.dp))

            AppCard {
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        MutedText("设备架构")
                        Spacer(Modifier.height(3.dp))
                        Text(report.abi, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        MutedText("Android")
                        Spacer(Modifier.height(3.dp))
                        Text(report.androidRelease, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SoftDivider()
                Spacer(Modifier.height(12.dp))
                MutedText("native 库目录")
                Spacer(Modifier.height(3.dp))
                Text(
                    text = report.nativeDir,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "就绪度检查",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            AppCard {
                report.items.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Spacer(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(8.dp)
                                .background(
                                    if (item.ok) OnlineGreen else DangerRed,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (index != report.items.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                        SoftDivider()
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = vm::selfTest,
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在执行…")
                } else {
                    Text("运行执行自检")
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = vm::installRootfs,
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("安装 / 重装最小运行时")
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = vm::runContainerProbe,
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("进入容器执行 uname")
            }

            if (vm.progress.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                MutedText(vm.progress)
            }

            if (vm.containerOutput.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    Text(
                        text = "容器输出",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = vm.containerOutput,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 17.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                    )
                }
            }

            if (report.execOutput.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    val (title, subtitle, tone) = when (report.execLevel) {
                        LocalRuntime.ExecLevel.OK ->
                            Triple("execve 成功", "内嵌二进制可以正常执行", OnlineGreen)
                        LocalRuntime.ExecLevel.INCOMPATIBLE ->
                            Triple(
                                "execve 成功，但二进制不兼容",
                                "进程已启动（execve 可达），但调用未完成：musl 静态包在 Android 上会因 argv0 分发或被 seccomp 终止而失败，正式运行时需改用 NDK 构建的 bionic 二进制",
                                WarnAmber
                            )
                        LocalRuntime.ExecLevel.FAILED ->
                            Triple("execve 失败", "见下方输出", DangerRed)
                        LocalRuntime.ExecLevel.NONE -> Triple("", "", OnlineGreen)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = tone
                    )
                    Spacer(Modifier.height(3.dp))
                    MutedText(subtitle)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = report.execOutput,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 17.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = {
                    val text = buildString {
                        appendLine("AiLink 运行时诊断")
                        appendLine("ABI=${report.abi} Android=${report.androidRelease}")
                        appendLine("nativeDir=${report.nativeDir}")
                        report.items.forEach { appendLine("[${if (it.ok) "OK" else "NO"}] ${it.label}: ${it.detail}") }
                        appendLine("execLevel=${report.execLevel}")
                        if (report.execOutput.isNotBlank()) {
                            appendLine("--- exec ---")
                            appendLine(report.execOutput)
                        }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ailink-runtime", text))
                    copied = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (copied) "已复制到剪贴板" else "复制诊断报告")
            }

            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                text = "QQ 运行时",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (vm.qqInstalled) "已安装" else "未安装",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(3.dp))
                        MutedText(
                            if (vm.qqInstalled) "QQ 3.2.34 + NapCat 已注入，容器内约 800 MB"
                            else "首次需下载约 350 MB（QQ 198 + 依赖 150），请用 Wi-Fi"
                        )
                    }
                    if (vm.qqRunning) {
                        TagChip("运行中", OnlineGreen)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = vm::installQq,
                    enabled = !vm.installing && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (vm.installing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("安装中，请勿切走…")
                    } else {
                        Text(if (vm.qqInstalled) "重新安装 QQ 运行时" else "安装 QQ 运行时")
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = vm.qqUin,
                    onValueChange = vm::updateQqUin,
                    label = { Text("QQ 号（可选，用于免扫码登录）") },
                    supportingText = {
                        MutedText("填了就用快速登录；首次登录仍需扫码，成功后再填")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vm.qqPasswordInput,
                    onValueChange = vm::updateQqPasswordInput,
                    label = { Text("QQ 密码（可选，用于凭据失效时自动登录）") },
                    placeholder = {
                        Text(if (vm.qqPasswordSet) "已保存，重新输入可更换" else "留空则失效后只能扫码")
                    },
                    supportingText = {
                        MutedText("只在本机保存 MD5（Keystore 加密），明文密码不落盘")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    trailingIcon = {
                        if (vm.qqPasswordInput.isNotBlank()) {
                            TextButton(onClick = vm::saveQqPassword) { Text("保存") }
                        } else if (vm.qqPasswordSet) {
                            TextButton(onClick = vm::clearQqPassword) { Text("清除") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (vm.passwordNotice.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    MutedText(vm.passwordNotice)
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("反检测加固", style = MaterialTheme.typography.bodyMedium)
                        MutedText(
                            "o3HookMode=0、bypass 六项全开。用兼容性换在线稳定性；" +
                                "切换后会自动重启 QQ，随时可关闭还原（第一次改写会留 .bak 备份）"
                        )
                        MutedText(remember(vm.antiDetect) { vm.describeAntiDetect() })
                    }
                    Switch(
                        checked = vm.antiDetect,
                        onCheckedChange = vm::toggleAntiDetect,
                        colors = appSwitchColors()
                    )
                }
                if (vm.antiDetectNotice.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    MutedText(vm.antiDetectNotice)
                }

                if (vm.needVerify) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        title = "QQ 要求人工验证",
                        message = "密码登录被要求短信/新设备验证。推荐直接扫下方二维码，这条路径最可靠——" +
                            "WebUI 那条路需要 NapCat 面板密码（登录成功后才下发），而且验证必须在该页面内完成，" +
                            "直接打开腾讯的验证链接结果传不回来。验证期间不要重启，重启会让验证会话作废。",
                        tone = BannerTone.WARNING
                    )
                    if (vm.verifyUrl.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(vm.verifyUrl)
                                    )
                                )
                            }
                        }) {
                            Text("打开 NapCat 面板（可选）")
                        }
                    }
                }

                if (vm.loginExpired) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        title = "登录态已失效，需要重新扫码",
                        message = "QQ 作废了上次的登录凭据。用另一台手机在 2 分钟内扫下面的二维码，成功后快速登录会重新生效。",
                        tone = BannerTone.WARNING
                    )
                } else if (vm.quickLoginFailed) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        title = "快速登录失败",
                        message = "QQ 号可能填错了，或这个号还没在本机成功登录过。核对后重新启动；这一步会回退为二维码登录。",
                        tone = BannerTone.WARNING
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = vm::startQq,
                        enabled = !vm.installing && !vm.qqRunning && vm.qqInstalled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("启动 QQ")
                    }
                    OutlinedButton(
                        onClick = { confirmStop = true },
                        enabled = vm.qqRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("停止")
                    }
                }

                vm.qrCode?.let { bitmap ->
                    Spacer(Modifier.height(14.dp))
                    MutedText(
                        "二维码第 ${vm.qrVersion} 次刷新，约 2 分钟自动更换一次，请在有效期内用另一台手机扫完。" +
                            "QQ 禁止相册识别授权，必须用摄像头扫真实屏幕。"
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QQ 登录二维码",
                            modifier = Modifier.size(220.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val message = vm.shareQr(context)
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("分享二维码")
                        }
                        OutlinedButton(
                            onClick = {
                                val url = vm.decodeUrl
                                if (url == null) {
                                    Toast.makeText(context, "还没拿到解码链接", Toast.LENGTH_SHORT).show()
                                } else {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("qq-qr-url", url))
                                    Toast.makeText(context, "已复制解码链接", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制解码链接")
                        }
                    }
                    if (vm.loggedIn) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "QQ 已登录",
                            style = MaterialTheme.typography.titleSmall,
                            color = OnlineGreen
                        )
                    }
                }

                // 日志与诊断：无条件常驻。
                // 旧实现把入口藏在 `if (vm.logs.isNotEmpty())` 里，而 vm.logs 只在安装期有内容，
                // 于是"QQ 正常在线"这个最需要导日志的时刻，按钮反而不存在。
                Spacer(Modifier.height(14.dp))
                SoftDivider()
                Spacer(Modifier.height(12.dp))
                Text("日志与诊断", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(3.dp))
                MutedText(vm.logSummary())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.exportLog(context) { message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导出日志文件")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.copyAllLog(context) { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("复制全部")
                    }
                }
                Spacer(Modifier.height(4.dp))
                MutedText("导出不影响 QQ 运行。内容：环境信息 + 安装日志 + 容器日志 + 会话日志。")

                val tail = remember(vm.logs.size) { vm.logTail() }
                if (tail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = tail,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    )
                }
            }


            NextStepsCard(vm = vm, onBack = { nav.popBackStack() })

            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止 QQ？") },
            text = {
                Text(
                    "停止后内置 QQ 会下线，使用它的账号会断开连接，自动回复随之中断。" +
                        "重新启动需要再等一次容器启动。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.stopQq()
                    confirmStop = false
                }) { Text("停止", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 「接下来」清单。
 *
 * 旧版本这里写的是「接下来要安装 QQ 与 NapCat」，但插件已经把这两步做完，
 * 静态文案会和实际能力矛盾。改成按实时状态勾选，并给出回到账号页的出口。
 */
@Composable
private fun NextStepsCard(vm: RuntimeViewModel, onBack: () -> Unit) {
    val finished = listOf(vm.qqInstalled, vm.qqRunning, vm.loggedIn).count { it }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "接下来",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TagChip(
                text = "$finished / 3",
                color = if (vm.loggedIn) OnlineGreen else WarnAmber
            )
        }
        Spacer(Modifier.height(4.dp))
        MutedText("这一页只负责让 QQ 在线；登录成功后回账号页点「测试连接」，通过即可开始自动回复。")

        Spacer(Modifier.height(10.dp))
        NextStepRow(1, "安装运行时", if (vm.qqInstalled) "已安装" else "点上方「安装 / 重装最小运行时」", vm.qqInstalled)
        NextStepRow(2, "启动 QQ", if (vm.qqRunning) "进程已运行" else "点上方「启动 QQ」", vm.qqRunning)
        NextStepRow(3, "扫码登录", if (vm.loggedIn) "已登录" else "用另一台手机扫上方二维码", vm.loggedIn)

        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.loggedIn) "已登录，回账号页测试连接" else "返回上一页")
        }
    }
}

@Composable
private fun NextStepRow(index: Int, title: String, detail: String, done: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    if (done) OnlineGreen else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (done) "✓" else index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (done) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            MutedText(detail)
        }
    }
}
