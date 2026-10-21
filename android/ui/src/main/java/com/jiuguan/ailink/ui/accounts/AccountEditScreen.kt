package com.jiuguan.ailink.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.model.TriggerScope
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.BannerTone
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.LabeledField
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.ui.theme.WarnAmber

@Composable
fun AccountEditScreen(
    nav: NavController,
    accountId: String,
    vm: AccountEditViewModel = appViewModel(key = "account-$accountId") {
        AccountEditViewModel(it, accountId)
    }
) {
    val form = vm.form
    var showProfileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm.saved, vm.deleted) {
        if (vm.saved || vm.deleted) nav.popBackStack()
    }

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
                text = if (vm.isNew) "新增账号" else "编辑账号",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = vm::save) {
                Text("保存", fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (vm.error.isNotBlank()) {
                InfoBanner(
                    title = "请检查配置",
                    message = vm.error,
                    tone = BannerTone.DANGER
                )
                Spacer(Modifier.height(12.dp))
            }

            SectionTitle("账号信息")
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Platform.entries.forEach { platform ->
                        FilterChip(
                            selected = form.platform == platform,
                            onClick = { vm.update { it.copy(platform = platform) } },
                            label = { Text(platform.label) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "备注名",
                    value = form.displayName,
                    onValueChange = { v -> vm.update { it.copy(displayName = v) } },
                    placeholder = "例如：工作 QQ"
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = if (form.platform == Platform.QQ) "QQ 号" else "微信号",
                    value = form.accountNo,
                    onValueChange = { v -> vm.update { it.copy(accountNo = v) } },
                    placeholder = "用于核对登录身份",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("接入方式")
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BridgeType.entries.forEach { bridge ->
                        FilterChip(
                            selected = form.bridge == bridge,
                            onClick = { vm.update { it.copy(bridge = bridge) } },
                            label = { Text(bridge.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                MutedText(
                    if (form.bridge == BridgeType.DEMO) {
                        "本地演示不连接真实平台，按脚本产生消息，适合先验证自动回复流程。"
                    } else {
                        "需在 NapCat / LLOneBot / Lagrange 等桥接端开启「正向 WebSocket」，并保证手机与其网络互通。"
                    }
                )

                if (form.bridge == BridgeType.ONEBOT_V11) {
                    Spacer(Modifier.height(14.dp))
                    LabeledField(
                        label = "WebSocket 地址",
                        value = form.endpoint,
                        onValueChange = { v -> vm.update { it.copy(endpoint = v) } },
                        placeholder = "ws://192.168.1.10:3001",
                        helper = "局域网填内网 IP；跨网络请使用 wss:// 或内网穿透地址"
                    )
                    Spacer(Modifier.height(14.dp))
                    LabeledField(
                        label = "访问令牌",
                        value = form.accessToken,
                        onValueChange = { v -> vm.update { it.copy(accessToken = v) } },
                        placeholder = "可留空",
                        helper = "与桥接端配置的 token 保持一致"
                    )
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = vm::testConnection,
                    enabled = !vm.testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (vm.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在测试…")
                    } else {
                        Text("测试连接")
                    }
                }
                if (vm.testMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = vm.testMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            vm.testMessage.contains("成功") || vm.testMessage.contains("无需") -> OnlineGreen
                            else -> WarnAmber
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("AI 服务")
            AppCard {
                SelectorRow(
                    title = "使用的 AI 服务",
                    value = vm.profiles.firstOrNull { it.id == form.aiProfileId }?.name
                        ?: vm.profiles.firstOrNull()?.name
                        ?: "未配置（使用内置模拟回复）",
                    onClick = { showProfileDialog = true }
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { nav.navigate("ai") },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("管理 AI 服务配置")
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("自动回复")
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用自动回复", style = MaterialTheme.typography.bodyLarge)
                        MutedText("关闭后仅保留在线与消息接收")
                    }
                    Switch(
                        checked = form.autoReply,
                        onCheckedChange = { v -> vm.update { it.copy(autoReply = v) } },
                        colors = appSwitchColors()
                    )
                }

                Spacer(Modifier.height(12.dp))
                SoftDivider()
                Spacer(Modifier.height(12.dp))

                Text("触发范围", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TriggerScope.entries.forEach { scope ->
                        FilterChip(
                            selected = form.scope == scope,
                            onClick = { vm.update { it.copy(scope = scope) } },
                            label = { Text(scope.label) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                MutedText(form.scope.desc)

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "回复延迟：" + String.format("%.1f", form.replyDelayMs / 1000f) + " 秒",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = form.replyDelayMs,
                    onValueChange = { v -> vm.update { it.copy(replyDelayMs = v) } },
                    valueRange = 0f..5000f,
                    steps = 0
                )
                MutedText("收到消息后等待一段时间再回复，更像真人操作。")

                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "人设提示词",
                    value = form.persona,
                    onValueChange = { v -> vm.update { it.copy(persona = v) } },
                    placeholder = "例如：你是账号主人，说话简短随意，喜欢用语气词。",
                    singleLine = false,
                    minLines = 3
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "忽略关键词",
                    value = form.ignoreKeywords,
                    onValueChange = { v -> vm.update { it.copy(ignoreKeywords = v) } },
                    placeholder = "广告, 收款, 验证码",
                    helper = "命中任一关键词的消息不会被自动回复，英文逗号分隔"
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("运行")
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("参与接入", style = MaterialTheme.typography.bodyLarge)
                        MutedText("关闭后服务启动时不会拉起该账号")
                    }
                    Switch(
                        checked = form.enabled,
                        onCheckedChange = { v -> vm.update { it.copy(enabled = v) } },
                        colors = appSwitchColors()
                    )
                }
            }

            if (!vm.isNew) {
                Spacer(Modifier.height(20.dp))
                TextButton(
                    onClick = vm::askDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除该账号", color = DangerRed)
                }
            }

            Spacer(Modifier.height(36.dp))
        }
    }

    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("选择 AI 服务") },
            text = {
                Column {
                    if (vm.profiles.isEmpty()) {
                        Text(
                            "尚未配置 AI 服务，将使用内置模拟回复。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        vm.profiles.forEach { profile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.update { it.copy(aiProfileId = profile.id) }
                                        showProfileDialog = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = form.aiProfileId == profile.id,
                                    onClick = {
                                        vm.update { it.copy(aiProfileId = profile.id) }
                                        showProfileDialog = false
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                                    MutedText(
                                        if (profile.isLocalOnly) "内置模拟回复"
                                        else "${profile.model} · ${profile.baseUrl}"
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfileDialog = false }) { Text("关闭") }
            }
        )
    }

    if (vm.confirmDelete) {
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("删除账号") },
            text = { Text("将同时删除该账号的会话与消息记录，操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = vm::delete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDelete) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SelectorRow(title: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
