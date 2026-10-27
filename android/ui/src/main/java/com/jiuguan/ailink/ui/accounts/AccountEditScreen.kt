package com.jiuguan.ailink.ui.accounts

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.model.TriggerScope
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.BannerTone
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.LabeledField
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.TagChip
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.MutedGray
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
    var showDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(vm.deleted) {
        if (vm.deleted) nav.popBackStack()
    }

    // 未保存的修改不能静默丢弃：返回时先确认
    BackHandler(enabled = vm.isDirty) { showDiscard = true }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (vm.isDirty) showDiscard = true else nav.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (vm.isNew) "新增账号" else "编辑账号",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (vm.saved) {
                TextButton(onClick = { nav.popBackStack() }) {
                    Text("完成", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (vm.saved) {
                SavedAccountPanel(
                    vm = vm,
                    onFinish = { nav.popBackStack() },
                    onManageAi = { nav.navigate("ai") },
                    onOpenRuntime = { nav.navigate("runtime") }
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            StepTitle("① 选择接入方式")
            AppCard {
                AccountPreset.entries.forEachIndexed { index, preset ->
                    PresetRow(
                        preset = preset,
                        selected = form.preset == preset,
                        onClick = { vm.applyPreset(preset) }
                    )
                    if (index != AccountPreset.entries.lastIndex) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            PresetHint(
                form = form,
                runtimeInstalled = vm.runtimeInstalled,
                qqRunning = vm.qqRunning,
                qqLoggedIn = vm.qqLoggedIn,
                onOpenRuntime = { nav.navigate("runtime") }
            )

            if (form.preset != AccountPreset.DEMO) {
                Spacer(Modifier.height(20.dp))
                StepTitle("② 填写账号")
                val statusLabel = when {
                    form.bridge == BridgeType.DEMO -> "无需测试"
                    form.endpoint.isBlank() -> "未配置"
                    vm.testing -> "测试中"
                    vm.testStatus.at > 0 && vm.testStatus.ok -> "测试成功"
                    vm.testStatus.at > 0 -> "测试失败"
                    vm.isNew -> "未测试"
                    else -> "已保存未测试"
                }
                val statusColor = when (statusLabel) {
                    "测试成功" -> OnlineGreen
                    "测试失败" -> DangerRed
                    "未配置", "未测试", "已保存未测试", "测试中" -> WarnAmber
                    else -> MutedGray
                }
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TagChip(statusLabel, statusColor)
                        Spacer(Modifier.width(8.dp))
                        MutedText(
                            when (statusLabel) {
                                "未配置" -> "先选择接入方式或填写 WebSocket 地址"
                                "未测试" -> "建议测试通过后再保存"
                                "已保存未测试" -> "配置已保存，但还没验证过连通性"
                                "测试成功" -> "桥接端可正常握手"
                                "测试失败" -> "按下方提示修正后重试"
                                "测试中" -> "正在连接桥接端…"
                                else -> "演示模式不连接真实平台"
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LabeledField(
                        label = "备注名（可留空）",
                        value = form.displayName,
                        onValueChange = { v -> vm.update { it.copy(displayName = v) } },
                        placeholder = if (form.platform == Platform.QQ) "例如：工作 QQ" else "例如：生活微信",
                        helper = "只用于本地区分多个账号，留空会自动取名"
                    )
                    Spacer(Modifier.height(14.dp))
                    LabeledField(
                        label = if (form.platform == Platform.QQ) "QQ 号（可留空）" else "微信号（可留空）",
                        value = form.accountNo,
                        onValueChange = { v ->
                            vm.update { it.copy(accountNo = v.filter { ch -> !ch.isWhitespace() }) }
                        },
                        placeholder = if (form.platform == Platform.QQ) "登录后自动带入，也可手动填写" else "用于核对登录身份",
                        helper = if (form.platform == Platform.QQ) {
                            "内置 QQ 填了可在下次启动时免扫码；不确定可留空"
                        } else {
                            "桥接端返回的 wxid 可能漂移，建议手填，便于核对"
                        },
                        keyboardType = if (form.platform == Platform.QQ) {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Text
                        }
                    )

                    if (form.bridge == BridgeType.ONEBOT_V11) {
                        Spacer(Modifier.height(14.dp))
                        LabeledField(
                            label = if (form.preset == AccountPreset.BUILTIN_QQ) {
                                "WebSocket 地址（已自动填好）"
                            } else {
                                "WebSocket 地址"
                            },
                            value = form.endpoint,
                            onValueChange = { v -> vm.update { it.copy(endpoint = v) } },
                            placeholder = "ws://192.168.1.10:3001",
                            helper = "局域网填电脑的内网 IP；跨网络请用 wss:// 或内网穿透地址",
                            keyboardType = KeyboardType.Uri
                        )
                        Spacer(Modifier.height(14.dp))
                        LabeledField(
                            label = "访问令牌（可留空）",
                            value = form.accessToken,
                            onValueChange = { v -> vm.update { it.copy(accessToken = v) } },
                            placeholder = "与桥接端配置的 token 保持一致",
                            helper = "NapCat 未设置 token 时留空即可"
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
                    if (vm.testStatus.at > 0) {
                        Spacer(Modifier.height(8.dp))
                        InfoBanner(
                            title = if (vm.testStatus.ok) "连接测试成功" else "连接测试失败",
                            message = vm.testStatus.message,
                            tone = if (vm.testStatus.ok) BannerTone.SUCCESS else BannerTone.DANGER
                        )
                    } else if (form.bridge == BridgeType.ONEBOT_V11) {
                        Spacer(Modifier.height(6.dp))
                        MutedText("保存前建议先测试；未测试的配置保存后仍可继续修改。")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            StepTitle("③ 选择 AI 服务")
            AppCard {
                SelectorRow(
                    title = "使用的 AI 服务",
                    value = vm.effectiveProfile?.let { profile ->
                        buildString {
                            append(profile.name.ifBlank { "未命名配置" })
                            if (vm.selectedProfile == null) append("（默认）")
                            if (!profile.enabled) append("（已停用）")
                        }
                    } ?: "未配置（使用内置模拟回复）",
                    onClick = { showProfileDialog = true }
                )
                Spacer(Modifier.height(4.dp))
                val selected = vm.effectiveProfile
                MutedText(
                    when {
                        selected == null -> "未绑定配置时使用内置模拟回复，不会产生 API 费用"
                        selected.isLocalOnly -> "内置模拟回复：不调用外部接口"
                        !selected.enabled -> "该配置已停用，运行时会回退到内置模拟回复"
                        selected.needsReview -> "该配置来自旧版本，需先在 AI 服务页确认启用"
                        selected.lastTestAt <= 0 -> "该配置尚未测试，建议先到 AI 服务页测试连接"
                        selected.lastTestOk -> "该配置上次测试通过"
                        else -> "该配置上次测试失败，请检查密钥与地址"
                    }
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
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("更多设置", style = MaterialTheme.typography.titleSmall)
                        MutedText("自动回复范围、延迟、人设与忽略关键词")
                    }
                    TextButton(onClick = vm::toggleAdvanced) {
                        Text(if (vm.showAdvanced) "收起" else "展开")
                        Icon(
                            imageVector = if (vm.showAdvanced) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (!vm.showAdvanced) return@AppCard

                Spacer(Modifier.height(10.dp))
                SoftDivider()
                Spacer(Modifier.height(12.dp))

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
                    label = "单次回复总字数（0 = 不限制）",
                    value = form.behavior.multi.totalMaxChars
                        .takeIf { it > 0 }?.toString().orEmpty(),
                    onValueChange = { raw ->
                        val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                        vm.update {
                            it.copy(behavior = it.behavior.copy(multi = it.behavior.multi.copy(totalMaxChars = value)))
                        }
                    },
                    placeholder = "600",
                    keyboardType = KeyboardType.Number,
                    helper = "一次回复所有内容加起来的字数上限。默认 600 与旧版一致；想让机器人多说点可以调大。"
                )

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("多条回复", style = MaterialTheme.typography.bodyLarge)
                        MutedText("一次回复拆成几条消息连续发出，更像真人在打字")
                    }
                    Switch(
                        checked = form.behavior.multi.enabled,
                        onCheckedChange = { value ->
                            vm.update {
                                it.copy(behavior = it.behavior.copy(multi = it.behavior.multi.copy(enabled = value)))
                            }
                        },
                        colors = appSwitchColors()
                    )
                }

                if (!form.behavior.multi.enabled) {
                    Spacer(Modifier.height(4.dp))
                    MutedText("关闭时保持单条回复，行为与旧版完全一致。")
                }

                if (form.behavior.multi.enabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "最多 ${form.behavior.multi.maxSegments} 条",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Slider(
                        value = form.behavior.multi.maxSegments.toFloat(),
                        onValueChange = { value ->
                            vm.update {
                                it.copy(behavior = it.behavior.copy(multi = it.behavior.multi.copy(maxSegments = value.toInt())))
                            }
                        },
                        valueRange = 1f..6f,
                        steps = 4
                    )
                    MutedText("设为 1 等于不拆分，但上面的「单次回复总字数」仍然生效。")

                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "单条字数上限（0 = 不限制）",
                        value = form.behavior.multi.segmentMaxChars
                            .takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = { raw ->
                            val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                            vm.update {
                                it.copy(behavior = it.behavior.copy(multi = it.behavior.multi.copy(segmentMaxChars = value)))
                            }
                        },
                        placeholder = "0",
                        keyboardType = KeyboardType.Number,
                        helper = "默认不限长。只有确实需要限制单条长度时才填。"
                    )

                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "每分钟输出总量（0 = 不限制）",
                        value = form.behavior.multi.budgetChars
                            .takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = { raw ->
                            val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                            vm.update {
                                it.copy(behavior = it.behavior.copy(multi = it.behavior.multi.copy(budgetChars = value)))
                            }
                        },
                        placeholder = "0",
                        keyboardType = KeyboardType.Number,
                        helper = "同一会话一分钟内累计发送的字数上限，防止连续被触发后越说越多。建议 600~1200。"
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("防刷屏保护", style = MaterialTheme.typography.bodyLarge)
                        MutedText("冷却合并、每小时发送上限、重复内容抑制")
                    }
                    Switch(
                        checked = form.behavior.throttle.enabled,
                        onCheckedChange = { value ->
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        throttle = it.behavior.throttle.copy(enabled = value)
                                    )
                                )
                            }
                        },
                        colors = appSwitchColors()
                    )
                }

                if (!form.behavior.throttle.enabled) {
                    Spacer(Modifier.height(4.dp))
                    MutedText("关闭后每条可回复的消息都会立即单独回复，与旧版一致。")
                }

                if (form.behavior.throttle.enabled) {
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "会话冷却（秒，0 = 不合并）",
                        value = form.behavior.throttle.convCooldownSeconds
                            .takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = { raw ->
                            val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        throttle = it.behavior.throttle.copy(convCooldownSeconds = value)
                                    )
                                )
                            }
                        },
                        placeholder = "3",
                        keyboardType = KeyboardType.Number,
                        helper = "刚回完一句，对方连着发来的几条会合并成一次回复，不会一句一句地追着回。"
                    )

                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "每小时发送上限（条，0 = 不限）",
                        value = form.behavior.throttle.accountMaxPerHour
                            .takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = { raw ->
                            val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        throttle = it.behavior.throttle.copy(accountMaxPerHour = value)
                                    )
                                )
                            }
                        },
                        placeholder = "60",
                        keyboardType = KeyboardType.Number,
                        helper = "按账号统计最近一小时成功发出的条数，超了就停手。多段回复的每个分段各算一条。这是防风控的最后一道闸。"
                    )

                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "重复内容抑制（分钟，0 = 关闭）",
                        value = form.behavior.throttle.dedupeWindowMinutes
                            .takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = { raw ->
                            val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        throttle = it.behavior.throttle.copy(dedupeWindowMinutes = value)
                                    )
                                )
                            }
                        },
                        placeholder = "10",
                        keyboardType = KeyboardType.Number,
                        helper = "同一会话里相同的内容不重复发送（忽略标点与空格）。「嗯」「好」这类短句不受影响。"
                    )

                    Spacer(Modifier.height(4.dp))
                    MutedText(
                        "连续 ${form.behavior.throttle.failBackoffThreshold} 次发送失败会自动停 " +
                            "${form.behavior.throttle.failBackoffMinutes} 分钟，期间消息照常入库、只不回复。"
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("主动发起", style = MaterialTheme.typography.bodyLarge)
                        MutedText("不等对方说话，机器人也能先开口")
                    }
                    Switch(
                        checked = form.behavior.proactive.enabled,
                        onCheckedChange = { value ->
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        proactive = it.behavior.proactive.copy(enabled = value)
                                    )
                                )
                            }
                        },
                        colors = appSwitchColors()
                    )
                }

                if (!form.behavior.proactive.enabled) {
                    Spacer(Modifier.height(4.dp))
                    MutedText("默认关闭。开启后只对「双方都说过话」的会话开口，群聊默认不主动。")
                }

                if (form.behavior.proactive.enabled) {
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "静默多久后续话（分钟，0 = 关闭）",
                        value = form.behavior.proactive.idleMinutes
                            .takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = { raw ->
                            val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        proactive = it.behavior.proactive.copy(idleMinutes = value)
                                    )
                                )
                            }
                        },
                        placeholder = "90",
                        keyboardType = KeyboardType.Number,
                        helper = "会话安静超过这个时长后，接着上次的话题续一句。太久没联系的会话（默认 7 天以上）不会打扰。"
                    )

                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "定时打招呼（如 09:00,21:30）",
                        value = form.behavior.proactive.scheduleTimes,
                        onValueChange = { raw ->
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        proactive = it.behavior.proactive.copy(scheduleTimes = raw)
                                    )
                                )
                            }
                        },
                        placeholder = "09:00,21:30",
                        helper = "每天到点主动打个招呼，一次只挑一个会话。留空则只按静默时长触发。"
                    )

                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "定时打招呼说什么（可留空）",
                        value = form.behavior.proactive.schedulePrompt,
                        onValueChange = { raw ->
                            vm.update {
                                it.copy(
                                    behavior = it.behavior.copy(
                                        proactive = it.behavior.proactive.copy(schedulePrompt = raw)
                                    )
                                )
                            }
                        },
                        placeholder = "例如：早上好，问一句今天有什么安排。",
                        singleLine = false,
                        minLines = 2
                    )

                    Spacer(Modifier.height(10.dp))
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            LabeledField(
                                label = "每小时上限",
                                value = form.behavior.proactive.maxPerHour
                                    .takeIf { it > 0 }?.toString().orEmpty(),
                                onValueChange = { raw ->
                                    val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                    vm.update {
                                        it.copy(
                                            behavior = it.behavior.copy(
                                                proactive = it.behavior.proactive.copy(maxPerHour = value)
                                            )
                                        )
                                    }
                                },
                                placeholder = "1",
                                keyboardType = KeyboardType.Number,
                                helper = "0 = 不限"
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            LabeledField(
                                label = "每天上限",
                                value = form.behavior.proactive.maxPerDay
                                    .takeIf { it > 0 }?.toString().orEmpty(),
                                onValueChange = { raw ->
                                    val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                    vm.update {
                                        it.copy(
                                            behavior = it.behavior.copy(
                                                proactive = it.behavior.proactive.copy(maxPerDay = value)
                                            )
                                        )
                                    }
                                },
                                placeholder = "3",
                                keyboardType = KeyboardType.Number,
                                helper = "0 = 不限"
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            LabeledField(
                                label = "免打扰开始（时）",
                                value = form.behavior.proactive.quietStartHour.toString(),
                                onValueChange = { raw ->
                                    val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                    vm.update {
                                        it.copy(
                                            behavior = it.behavior.copy(
                                                proactive = it.behavior.proactive.copy(quietStartHour = value)
                                            )
                                        )
                                    }
                                },
                                placeholder = "23",
                                keyboardType = KeyboardType.Number,
                                helper = "0–23"
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            LabeledField(
                                label = "免打扰结束（时）",
                                value = form.behavior.proactive.quietEndHour.toString(),
                                onValueChange = { raw ->
                                    val value = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                    vm.update {
                                        it.copy(
                                            behavior = it.behavior.copy(
                                                proactive = it.behavior.proactive.copy(quietEndHour = value)
                                            )
                                        )
                                    }
                                },
                                placeholder = "8",
                                keyboardType = KeyboardType.Number,
                                helper = "结束小时不含；开始>结束表示跨零点"
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("允许在群聊主动", style = MaterialTheme.typography.bodyLarge)
                            MutedText("群里主动说话被举报的概率最高，默认关闭")
                        }
                        Switch(
                            checked = form.behavior.proactive.allowInGroup,
                            onCheckedChange = { value ->
                                vm.update {
                                    it.copy(
                                        behavior = it.behavior.copy(
                                            proactive = it.behavior.proactive.copy(allowInGroup = value)
                                        )
                                    )
                                }
                            },
                            colors = appSwitchColors()
                        )
                    }
                }

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

                Spacer(Modifier.height(14.dp))
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

            Spacer(Modifier.height(20.dp))

            // 错误贴在操作按钮上方：用户点保存时视线就在这里，不用回滚到页首找原因
            if (vm.error.isNotBlank()) {
                InfoBanner(title = "还不能保存", message = vm.error, tone = BannerTone.DANGER)
                Spacer(Modifier.height(10.dp))
            }

            if (!vm.engineRunning) {
                Button(
                    onClick = { vm.requestSave(startAfterSave = true) },
                    enabled = !vm.testing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (vm.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("先测试连接…")
                    } else {
                        Text("保存并开始使用")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.requestSave(startAfterSave = false) },
                    enabled = !vm.testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("仅保存，稍后启动")
                }
                Spacer(Modifier.height(6.dp))
                MutedText("未测试过的配置会先自动测试一次；失败时可以选择返回修改或仍然保存。")
            } else {
                Button(
                    onClick = { vm.requestSave(startAfterSave = false) },
                    enabled = !vm.testing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (vm.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("先测试连接…")
                    } else {
                        Text("保存并应用")
                    }
                }
                Spacer(Modifier.height(6.dp))
                MutedText("服务已在运行，保存后会立即用新配置重连。")
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
                            ProfileRow(
                                profile = profile,
                                selected = form.aiProfileId == profile.id,
                                onSelect = {
                                    vm.update { it.copy(aiProfileId = profile.id) }
                                    showProfileDialog = false
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            showProfileDialog = false
                            nav.navigate("ai/edit")
                        }
                    ) { Text("新增 AI 服务") }
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

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前填写的内容还没有保存，返回后这些修改会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    nav.popBackStack()
                }) { Text("放弃修改", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("继续编辑") }
            }
        )
    }

    if (vm.saveBlocked) {
        AlertDialog(
            onDismissRequest = vm::dismissSaveBlock,
            title = { Text("连接测试未通过") },
            text = {
                Text(
                    "${vm.testStatus.message}\n\n" +
                        "可以先返回修正地址或令牌；也可以仍然保存，稍后在账号列表里继续修复。"
                )
            },
            confirmButton = {
                TextButton(onClick = vm::saveAnyway) { Text("仍然保存") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSaveBlock) { Text("返回修改") }
            }
        )
    }
}

/**
 * 保存后的结果与下一步。
 *
 * 原先保存成功直接退回上一页，用户拿不到「有没有连上、用的是哪份 AI、接下来做什么」的反馈。
 * 这里把三件事一次说清，并把「启动接入服务」放在主按钮上。
 */
@Composable
private fun SavedAccountPanel(
    vm: AccountEditViewModel,
    onFinish: () -> Unit,
    onManageAi: () -> Unit,
    onOpenRuntime: () -> Unit
) {
    val form = vm.form
    val ai = vm.effectiveProfile
    val boundExplicitly = vm.selectedProfile != null
    val aiUsable = ai != null && ai.enabled && !ai.needsReview
    val aiTestedOk = aiUsable && ai?.lastTestOk == true

    InfoBanner(
        title = "账号已保存",
        message = if (vm.engineRunning) {
            "接入服务已在运行，账号会按新配置重连。"
        } else {
            "还差最后一步：启动接入服务，账号才会保持在线并自动回复。"
        },
        tone = BannerTone.SUCCESS
    )

    Spacer(Modifier.height(12.dp))
    AppCard {
        SummaryRow("账号", form.displayName.ifBlank { "未命名账号" })
        Spacer(Modifier.height(8.dp))
        SummaryRow(
            label = "连接",
            value = when {
                form.bridge == BridgeType.DEMO -> "演示模式，无需连接"
                vm.testStatus.ok -> "测试通过" + if (vm.testStatus.at > 0) {
                    " · " + com.jiuguan.ailink.util.TimeFmt.relative(vm.testStatus.at)
                } else ""
                vm.testStatus.at > 0 -> "测试失败（已保存，可稍后修复）"
                else -> "未测试"
            },
            color = when {
                form.bridge == BridgeType.DEMO -> MutedGray
                vm.testStatus.ok -> OnlineGreen
                vm.testStatus.at > 0 -> DangerRed
                else -> WarnAmber
            }
        )
        Spacer(Modifier.height(8.dp))
        SummaryRow(
            label = "AI 服务",
            value = when {
                ai == null -> "内置模拟回复"
                !aiUsable -> "${ai.name.ifBlank { "未命名配置" }}（不可用）"
                aiTestedOk -> "${ai.name.ifBlank { "未命名配置" }}${if (boundExplicitly) "" else "（默认）"} · 测试通过"
                else -> "${ai.name.ifBlank { "未命名配置" }}${if (boundExplicitly) "" else "（默认）"} · 未测试"
            },
            color = when {
                ai == null -> MutedGray
                !aiUsable -> DangerRed
                aiTestedOk -> OnlineGreen
                else -> WarnAmber
            }
        )
    }

    if (vm.testStatus.at > 0 && !vm.testStatus.ok) {
        Spacer(Modifier.height(10.dp))
        InfoBanner(
            title = "连接测试未通过",
            message = "${vm.testStatus.message}\n修好后重新测试，通过再启动服务。",
            tone = BannerTone.DANGER
        )
    }

    if (!aiUsable) {
        Spacer(Modifier.height(10.dp))
        InfoBanner(
            title = "当前会用内置模拟回复",
            message = "账号已保存，但还没有可用的 AI 服务。配置一份并测试通过后，自动回复才会调用真实模型。",
            tone = BannerTone.WARNING
        )
    } else if (!aiTestedOk) {
        Spacer(Modifier.height(10.dp))
        InfoBanner(
            title = "AI 服务还没测试通过",
            message = "自动回复可能失败。建议到 AI 服务页点「测试连接」确认密钥可用。",
            tone = BannerTone.WARNING
        )
    }

    if (vm.form.preset == AccountPreset.BUILTIN_QQ && !vm.qqRunning) {
        Spacer(Modifier.height(10.dp))
        InfoBanner(
            title = "内置 QQ 还没启动",
            message = "到运行时页启动 QQ 并完成扫码登录，账号才会真的上线。",
            tone = BannerTone.WARNING
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onOpenRuntime, modifier = Modifier.fillMaxWidth()) {
            Text("去启动内置 QQ")
        }
    }

    Spacer(Modifier.height(16.dp))
    if (!vm.engineRunning) {
        Button(
            onClick = vm::startEngine,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) { Text("启动接入服务") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text("返回概览")
        }
    } else {
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) { Text("完成") }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onManageAi, modifier = Modifier.fillMaxWidth()) {
        Text("管理 AI 服务")
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PresetRow(
    preset: AccountPreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    TagChip(
                        text = preset.tag,
                        color = if (preset.tag == "推荐") OnlineGreen else MutedGray
                    )
                }
                Spacer(Modifier.height(2.dp))
                MutedText(preset.subtitle)
            }
        }
    }
}

/** 按当前接入方式给出「下一步做什么」，避免用户在一堆术语里自己找。 */
@Composable
private fun PresetHint(
    form: AccountForm,
    runtimeInstalled: Boolean,
    qqRunning: Boolean,
    qqLoggedIn: Boolean,
    onOpenRuntime: () -> Unit
) {
    when (form.preset) {
        AccountPreset.BUILTIN_QQ -> {
            when {
                !runtimeInstalled -> {
                    InfoBanner(
                        title = "还差一步：安装内置 QQ 运行时",
                        message = "首次使用需下载约 350 MB（QQ + 依赖），建议 Wi-Fi。装完后回到这里点「测试连接」。",
                        tone = BannerTone.WARNING
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenRuntime, modifier = Modifier.fillMaxWidth()) {
                        Text("去安装运行时")
                    }
                }

                !qqRunning -> {
                    InfoBanner(
                        title = "运行时已安装，但 QQ 未启动",
                        message = "到运行时页点「启动 QQ」，首次需要用另一台手机扫二维码登录。",
                        tone = BannerTone.WARNING
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenRuntime, modifier = Modifier.fillMaxWidth()) {
                        Text("去启动并扫码")
                    }
                }

                !qqLoggedIn -> {
                    InfoBanner(
                        title = "等待 QQ 扫码登录",
                        message = "QQ 进程已启动，二维码在运行时页。登录成功后本账号会自动上线。",
                        tone = BannerTone.INFO
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenRuntime, modifier = Modifier.fillMaxWidth()) {
                        Text("查看二维码")
                    }
                }

                else -> InfoBanner(
                    title = "内置 QQ 已就绪",
                    message = "地址与令牌已自动填好，直接测试连接即可。",
                    tone = BannerTone.SUCCESS
                )
            }
        }

        AccountPreset.LAN_QQ -> InfoBanner(
            title = "先在电脑上启动桥接端",
            message = "NapCat / LLOneBot 开启「正向 WebSocket」（默认端口 3001），" +
                "手机与电脑连同一 Wi-Fi；也可以用配对码一键导入地址与令牌。",
            tone = BannerTone.INFO
        )

        AccountPreset.WECHAT -> InfoBanner(
            title = "微信需要 Windows 桥接",
            message = "在电脑上运行微信 hook 与 onebot-bridge（默认端口 3002），" +
                "手机填写电脑的内网 IP。本机无法直接运行微信协议端。",
            tone = BannerTone.INFO
        )

        AccountPreset.DEMO -> InfoBanner(
            title = "演示模式不连接真实平台",
            message = "保存并启动服务后会按脚本产生消息，用来验证自动回复是否正常。",
            tone = BannerTone.INFO
        )
    }
}

@Composable
private fun StepTitle(text: String) {
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

@Composable
private fun ProfileRow(
    profile: AiProfileEntity,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val usable = profile.enabled && !profile.needsReview
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = usable) { onSelect() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = if (usable) onSelect else null
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name.ifBlank { "未命名配置" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (usable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (profile.isDefault) {
                    Spacer(Modifier.width(6.dp))
                    TagChip("默认", MaterialTheme.colorScheme.primary)
                }
                if (!usable) {
                    Spacer(Modifier.width(6.dp))
                    TagChip(
                        if (profile.needsReview) "待确认" else "已停用",
                        WarnAmber
                    )
                }
            }
            MutedText(
                when {
                    profile.isLocalOnly -> "内置模拟回复"
                    !usable -> "需先在 AI 服务页启用"
                    else -> "${profile.model} · ${profile.baseUrl}"
                }
            )
        }
    }
}
