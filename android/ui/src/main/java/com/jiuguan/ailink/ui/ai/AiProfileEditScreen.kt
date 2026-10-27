package com.jiuguan.ailink.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jiuguan.ailink.data.local.AiProfileState
import com.jiuguan.ailink.model.AiAuthStyle
import com.jiuguan.ailink.model.AiProtocol
import com.jiuguan.ailink.model.VisionSupport
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
fun AiProfileEditScreen(
    nav: NavController,
    profileId: String,
    vm: AiProfileEditViewModel = appViewModel(key = "ai-$profileId") {
        AiProfileEditViewModel(it, profileId)
    }
) {
    val form = vm.form

    var showDiscard by remember { mutableStateOf(false) }

    // 未保存的修改不能静默丢弃
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
                text = if (vm.isNew) "新增 AI 服务" else "编辑 AI 服务",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                if (vm.saved && !vm.isDirty) nav.popBackStack() else vm.save()
            }) {
                Text(
                    if (vm.saved && !vm.isDirty) "完成" else "保存",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (vm.saved && vm.isDirty) {
                InfoBanner(
                    title = "有未保存的修改",
                    message = "保存过之后又改了字段，点下方「保存并测试」才会生效；直接返回会丢弃这些改动。",
                    tone = BannerTone.WARNING
                )
                Spacer(Modifier.height(12.dp))
            } else if (vm.saved) {
                InfoBanner(
                    title = "配置已保存",
                    message = buildString {
                        if (form.isDefault) append("已设为默认配置，未单独指定配置的账号会使用它。")
                        else append("账号里可以单独指定这份配置。")
                        if (form.baseUrl.isNotBlank() && !vm.testing && vm.testResult?.ok != true) {
                            append("建议先点下方「测试连接」确认密钥可用。")
                        }
                    },
                    tone = BannerTone.SUCCESS
                )
                Spacer(Modifier.height(12.dp))
            }

            if (vm.error.isNotBlank()) {
                InfoBanner(title = "请检查配置", message = vm.error, tone = BannerTone.DANGER)
                Spacer(Modifier.height(12.dp))
            }

            if (form.needsReview) {
                InfoBanner(
                    title = "这是旧版本保存的配置，已自动停用",
                    message = "为避免继续误接过期接口，升级后不会自动启用。请核对接口地址、密钥与模型，" +
                        "确认无误后打开下方「启用此配置」并保存。",
                    tone = BannerTone.WARNING
                )
                Spacer(Modifier.height(12.dp))
            }

            AiVendorQuickConfig(
                selected = vm.selectedVendor,
                currentModel = form.model,
                onSelectVendor = vm::applyVendor,
                onSelectModel = vm::applyModel
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("连接信息")
            AppCard {
                LabeledField(
                    label = "配置名称（可留空）",
                    value = form.name,
                    onValueChange = { v -> vm.update { it.copy(name = v) } },
                    placeholder = "例如：DeepSeek 主力",
                    helper = "留空会按模型名自动取名，之后也能改"
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "接口地址（Base URL）",
                    value = form.baseUrl,
                    onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
                    placeholder = "https://api.deepseek.com/v1",
                    helper = "留空 = 使用内置模拟回复。多数兼容服务填到 /v1 即可，实际请求地址见下方「高级设置」"
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "API 密钥",
                    value = form.apiKey,
                    onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
                    placeholder = "sk-...",
                    helper = "使用系统 Keystore 加密后保存到本机数据库，不会上传；默认以圆点显示，可点右侧「显示」核对",
                    singleLine = true,
                    visualTransformation = if (vm.showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = vm::toggleShowKey) {
                            Text(if (vm.showKey) "隐藏" else "显示")
                        }
                    }
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "模型名称",
                    value = form.model,
                    onValueChange = { v -> vm.update { it.copy(model = v) } },
                    placeholder = "deepseek-flash / gpt-4o-mini / qwen-plus",
                    helper = "必须与服务商控制台里的模型 ID 完全一致"
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("启用状态")
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用此配置", style = MaterialTheme.typography.bodyLarge)
                        MutedText(
                            when {
                                form.needsReview -> "旧配置需你手动确认后才会生效"
                                form.enabled -> "账号可以使用这份配置"
                                else -> "停用后不会被任何账号使用"
                            }
                        )
                    }
                    Switch(
                        checked = form.enabled,
                        onCheckedChange = vm::setEnabled,
                        colors = appSwitchColors()
                    )
                }

                Spacer(Modifier.height(12.dp))
                SoftDivider()
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("设为默认配置", style = MaterialTheme.typography.bodyLarge)
                        MutedText("账号未单独指定时使用；同一时间只会有一份默认配置")
                    }
                    Switch(
                        checked = form.isDefault,
                        onCheckedChange = vm::setDefault,
                        colors = appSwitchColors()
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagChip(
                        text = stateLabel(form.needsReview, form.enabled, form.baseUrl, vm.testResult, vm.testStale),
                        color = stateColor(form.needsReview, form.enabled, form.baseUrl, vm.testResult, vm.testStale)
                    )
                    Spacer(Modifier.width(8.dp))
                    MutedText(
                        when {
                            vm.testStale -> "配置已修改，请重新测试后保存"
                            vm.testResult?.ok == true -> "上次测试成功，可正常调用"
                            vm.testResult != null -> "上次测试失败，请按提示修正"
                            else -> "尚未测试；建议先测试连接再启用"
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            VisionCard(
                enabled = form.visionEnabled,
                status = vm.visionStatus,
                checking = vm.visionChecking,
                model = form.model,
                onToggle = vm::setVisionEnabled
            )

            Spacer(Modifier.height(16.dp))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "高级设置",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
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
                MutedText("协议、接口路径、认证方式、请求头、超时与重试；不确定时保持默认即可。")

                if (!vm.showAdvanced) return@AppCard

                Spacer(Modifier.height(12.dp))
                Text("协议类型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = form.protocol == protocol,
                            onClick = { vm.update { it.copy(protocol = protocol) } },
                            label = { Text(protocol.label) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                MutedText(form.protocol.hint)

                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "接口路径（可选）",
                    value = form.chatPath,
                    onValueChange = { v -> vm.update { it.copy(chatPath = v) } },
                    placeholder = "/chat/completions",
                    helper = "留空自动补全。当前实际请求：${vm.resolvedEndpoint.ifBlank { "未填写地址" }}"
                )

                Spacer(Modifier.height(14.dp))
                Text("认证方式", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiAuthStyle.entries.forEach { style ->
                        FilterChip(
                            selected = form.authStyle == style,
                            onClick = { vm.update { it.copy(authStyle = style) } },
                            label = { Text(style.label) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                MutedText(form.authStyle.hint)

                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "自定义请求头（可选）",
                    value = form.extraHeaders,
                    onValueChange = { v -> vm.update { it.copy(extraHeaders = v) } },
                    placeholder = "X-Org-Id: my-team\nX-Api-Version: 2024-01",
                    singleLine = false,
                    minLines = 3,
                    helper = "每行一条「名称: 值」，可覆盖认证头；Host / Content-Length 由客户端管理"
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "请求超时：${form.timeoutSec.toInt()} 秒",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = form.timeoutSec,
                    onValueChange = { v -> vm.update { it.copy(timeoutSec = v) } },
                    valueRange = 10f..180f,
                    steps = 0
                )
                MutedText("长回复或思考型模型建议 60-120 秒；测试连接沿用同一超时。")

                Spacer(Modifier.height(10.dp))
                Text("失败重试次数：${form.maxRetries}", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2, 3).forEach { count ->
                        FilterChip(
                            selected = form.maxRetries == count,
                            onClick = { vm.update { it.copy(maxRetries = count) } },
                            label = { Text(count.toString()) }
                        )
                    }
                }
                MutedText("仅对网络错误、429 与 5xx 重试，已发出但超时的请求可能重复计费。")
            }

            Spacer(Modifier.height(16.dp))
            AppCard {
                Text(
                    text = "温度：" + String.format("%.1f", form.temperature),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = form.temperature,
                    onValueChange = { v -> vm.update { it.copy(temperature = v) } },
                    valueRange = 0f..2f,
                    steps = 0
                )
                MutedText("数值越高回复越随机，聊天场景建议 0.7 - 1.0。")

                Spacer(Modifier.height(10.dp))
                SoftDivider()
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "单次最大 Token：${form.maxTokens.toInt()}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = form.maxTokens,
                    onValueChange = { v -> vm.update { it.copy(maxTokens = v) } },
                    valueRange = 64f..2048f,
                    steps = 0
                )
                MutedText("限制回复长度，避免长文刷屏，聊天建议 256 - 512。")
            }

            Spacer(Modifier.height(16.dp))
            AppCard {
                LabeledField(
                    label = "系统提示词",
                    value = form.systemPrompt,
                    onValueChange = { v -> vm.update { it.copy(systemPrompt = v) } },
                    placeholder = "描述 AI 的身份与说话风格",
                    singleLine = false,
                    minLines = 4,
                    helper = "会与账号人设叠加，实际生效内容可在账号编辑页调整"
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = vm::testConnection,
                enabled = !vm.testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在调用…")
                } else {
                    Text("测试连接")
                }
            }

            vm.testResult?.let { result ->
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    title = result.summary,
                    message = buildString {
                        if (result.detail.isNotBlank()) appendLine(result.detail)
                        if (result.reply.isNotBlank()) append("模型返回：「${result.reply}」")
                    }.trim().ifBlank { "没有更多信息" },
                    tone = when {
                        result.ok -> BannerTone.SUCCESS
                        form.baseUrl.isBlank() -> BannerTone.INFO
                        else -> BannerTone.DANGER
                    }
                )
            }

            if (vm.testStale) {
                Spacer(Modifier.height(8.dp))
                MutedText("提示：地址、密钥或模型已修改，上一次测试结论已失效。")
            }

            Spacer(Modifier.height(14.dp))
            if (vm.saved && !vm.isDirty) {
                Button(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) { Text("完成") }
                Spacer(Modifier.height(6.dp))
                MutedText("返回列表后，可以在账号里为它单独指定，或把它设为默认配置。")
            } else {
                Button(
                    onClick = { vm.save(testAfter = true) },
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
                        Text("正在测试…")
                    } else {
                        Text(if (form.needsReview) "核对无误，保存并测试" else "保存并测试")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.save(testAfter = false) },
                    enabled = !vm.testing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("仅保存") }
                Spacer(Modifier.height(6.dp))
                MutedText("保存后会自动测试一次；失败时留在本页，按提示修正即可，不影响已保存的内容。")
            }

            Spacer(Modifier.height(36.dp))
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("还有改动没有保存，返回后这些修改会丢失。") },
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

private fun stateLabel(
    needsReview: Boolean,
    enabled: Boolean,
    baseUrl: String,
    result: com.jiuguan.ailink.data.ai.AiTestResult?,
    stale: Boolean
): String = when {
    needsReview -> AiProfileState.NEEDS_REVIEW.label
    !enabled -> AiProfileState.DISABLED.label
    baseUrl.isBlank() -> AiProfileState.UNCONFIGURED.label
    stale || result == null -> AiProfileState.UNTESTED.label
    result.ok -> AiProfileState.TEST_OK.label
    else -> AiProfileState.TEST_FAILED.label
}

private fun stateColor(
    needsReview: Boolean,
    enabled: Boolean,
    baseUrl: String,
    result: com.jiuguan.ailink.data.ai.AiTestResult?,
    stale: Boolean
) = when {
    needsReview || (!enabled && baseUrl.isNotBlank()) -> WarnAmber
    baseUrl.isBlank() -> MutedGray
    stale || result == null -> WarnAmber
    result.ok -> OnlineGreen
    else -> DangerRed
}

/**
 * 识图开关与检测结论。
 *
 * 结论来自模型名快判或一次真实探测，界面只负责解释「当前会不会把图片发给模型」，
 * 具体判定逻辑在 data 层的 VisionProbe 里，避免 UI 与判定规则耦合。
 */
@Composable
private fun VisionCard(
    enabled: Boolean,
    status: VisionSupport,
    checking: Boolean,
    model: String,
    onToggle: (Boolean) -> Unit
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "图片识别",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                MutedText(
                    if (enabled) {
                        "自动检测当前模型能否识图；支持时把收到的图片转码后交给模型理解。"
                    } else {
                        "已关闭：图片消息只以「[图片]」参与对话。"
                    }
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = appSwitchColors()
            )
        }

        if (!enabled) return@AppCard

        Spacer(Modifier.height(10.dp))
        SoftDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagChip(
                text = when {
                    checking -> "检测中"
                    status == VisionSupport.SUPPORTED -> "支持识图"
                    status == VisionSupport.UNSUPPORTED -> "不支持"
                    else -> "未检测"
                },
                color = when {
                    checking -> WarnAmber
                    status == VisionSupport.SUPPORTED -> OnlineGreen
                    status == VisionSupport.UNSUPPORTED -> MutedGray
                    else -> WarnAmber
                }
            )
            Spacer(Modifier.width(8.dp))
            MutedText(
                when {
                    checking -> "正在用一张测试图请求接口…"
                    status == VisionSupport.SUPPORTED ->
                        "模型 ${model.ifBlank { "未填" }} 接受图片输入，图片消息会附带图片内容。"
                    status == VisionSupport.UNSUPPORTED ->
                        "当前模型只接受文本，图片消息仍按「[图片]」处理。"
                    else -> "点下方「测试连接」可检测；确认前图片按纯文本处理。"
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
