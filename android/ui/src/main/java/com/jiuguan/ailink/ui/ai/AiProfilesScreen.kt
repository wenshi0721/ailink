package com.jiuguan.ailink.ui.ai

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jiuguan.ailink.data.ai.AiEndpoint
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.data.local.AiProfileState
import com.jiuguan.ailink.data.local.state
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.BannerTone
import com.jiuguan.ailink.ui.common.EmptyState
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.TagChip
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.MutedGray
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.ui.theme.WarnAmber
import kotlinx.coroutines.delay

@Composable
fun AiProfilesScreen(
    nav: NavController,
    vm: AiProfilesViewModel = appViewModel { AiProfilesViewModel(it) }
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<AiProfileEntity?>(null) }
    var pendingEnable by remember { mutableStateOf<AiProfileEntity?>(null) }
    var confirmAllLegacy by remember { mutableStateOf(false) }

    val legacy = profiles.filter { it.needsReview }

    LaunchedEffect(vm.notice) {
        if (vm.notice.isNotBlank()) {
            delay(4_000)
            vm.clearNotice()
        }
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
                text = "AI 服务",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${profiles.count { it.enabled }} / ${profiles.size} 启用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (legacy.isNotEmpty()) {
                InfoBanner(
                    title = "检测到 ${legacy.size} 个旧版 API 配置，已自动停用",
                    message = "这些配置来自升级前的版本，接口地址或密钥可能已过期。为避免误接旧接口，" +
                        "应用不会自动使用它们；请逐个核对后手动启用。",
                    tone = BannerTone.WARNING
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { nav.navigate("ai/edit?profileId=${legacy.first().id}") },
                        modifier = Modifier.weight(1f)
                    ) { Text("逐个核对") }
                    TextButton(
                        onClick = { confirmAllLegacy = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("全部确认启用") }
                }
            } else {
                InfoBanner(
                    title = "新增时先套用厂商模板",
                    message = "点选 DeepSeek、通义千问、Kimi、智谱、豆包等会自动填好地址与模型，" +
                        "只需粘贴 API Key；也支持任意 OpenAI 兼容服务与自建网关。",
                    tone = BannerTone.INFO
                )
            }

            if (vm.notice.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                InfoBanner(title = "已更新", message = vm.notice, tone = BannerTone.SUCCESS)
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { nav.navigate("ai/edit") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新增 AI 服务")
            }

            Spacer(Modifier.height(14.dp))

            if (profiles.isEmpty()) {
                EmptyState(
                    title = "尚未配置 AI 服务",
                    description = "未配置时，账号会使用内置模拟回复，仍可完整体验自动回复流程"
                )
            } else {
                profiles.forEach { profile ->
                    AiProfileCard(
                        profile = profile,
                        testing = vm.testingId == profile.id,
                        testBusy = vm.testingId.isNotBlank(),
                        liveSummary = vm.testResults[profile.id]?.summary,
                        liveDetail = vm.testResults[profile.id]?.detail,
                        liveOk = vm.testResults[profile.id]?.ok,
                        onEdit = { nav.navigate("ai/edit?profileId=${profile.id}") },
                        onToggle = { enabled ->
                            if (enabled && profile.needsReview) {
                                pendingEnable = profile
                            } else {
                                vm.setEnabled(profile, enabled)
                            }
                        },
                        onTest = { vm.test(profile) },
                        onSetDefault = { vm.setDefault(profile) },
                        onClearDefault = { vm.clearDefault(profile) },
                        onDuplicate = { vm.duplicate(profile) },
                        onDelete = { pendingDelete = profile }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 AI 服务") },
            text = {
                Text(
                    "将删除「${profile.name.ifBlank { "未命名配置" }}」。引用该配置的账号会改用默认配置，" +
                        "没有默认配置时回退到内置模拟回复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(profile)
                    pendingDelete = null
                }) {
                    Text("删除", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    pendingEnable?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingEnable = null },
            title = { Text("启用旧版配置？") },
            text = {
                Text(
                    "「${profile.name.ifBlank { "未命名配置" }}」来自旧版本，地址为 " +
                        "${profile.baseUrl.ifBlank { "（空）" }}。\n\n启用后账号会立即用这份配置发起真实请求。" +
                        "请确认该接口仍然有效、密钥仍在有效期内。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setEnabled(profile, true)
                    pendingEnable = null
                }) { Text("确认启用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEnable = null }) { Text("先核对") }
            }
        )
    }

    if (confirmAllLegacy) {
        AlertDialog(
            onDismissRequest = { confirmAllLegacy = false },
            title = { Text("全部确认启用？") },
            text = {
                Text(
                    "将启用 ${legacy.size} 个旧版配置。若其中包含已失效的接口，" +
                        "对应账号的自动回复会失败并写入会话日志。建议先逐个测试。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmAllLegacy()
                    confirmAllLegacy = false
                }) { Text("仍然启用") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAllLegacy = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AiProfileCard(
    profile: AiProfileEntity,
    testing: Boolean,
    testBusy: Boolean,
    liveSummary: String?,
    liveDetail: String?,
    liveOk: Boolean?,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSetDefault: () -> Unit,
    onClearDefault: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val state = profile.state()

    AppCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name.ifBlank { "未命名配置" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (profile.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("默认", MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(6.dp))
                    TagChip(state.label, stateColor(state))
                }
                Spacer(Modifier.height(4.dp))
                MutedText(
                    if (profile.isLocalOnly) {
                        "不调用外部接口，使用本地模板回复"
                    } else {
                        "${profile.protocolType.label} · ${profile.model.ifBlank { "未填模型" }}"
                    }
                )
                if (!profile.isLocalOnly) {
                    Spacer(Modifier.height(2.dp))
                    ClampedText(
                        text = AiEndpoint.resolve(profile.baseUrl, profile.chatPath),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = profile.enabled,
                onCheckedChange = onToggle,
                colors = appSwitchColors()
            )
        }

        Spacer(Modifier.height(10.dp))
        SoftDivider()
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTest, enabled = !testBusy && !profile.isLocalOnly) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("测试中…")
                } else {
                    Text(if (profile.isLocalOnly) "无需测试" else "测试连接")
                }
            }
            TextButton(onClick = onEdit) { Text("编辑") }
            MutedText(
                if (profile.apiKey.isBlank()) "未设密钥" else "密钥已保存",
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (profile.isDefault) {
                    DropdownMenuItem(
                        text = { Text("取消默认") },
                        onClick = {
                            menuOpen = false
                            onClearDefault()
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("设为默认") },
                        onClick = {
                            menuOpen = false
                            onSetDefault()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("复制配置") },
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = DangerRed) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }

        val summary = liveSummary ?: when {
            profile.lastTestAt <= 0L -> ""
            profile.lastTestOk -> "上次测试成功"
            else -> "上次测试失败"
        }
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    liveOk != null -> if (liveOk) OnlineGreen else DangerRed
                    profile.lastTestOk -> OnlineGreen
                    else -> WarnAmber
                }
            )
            val detail = liveDetail ?: profile.lastTestMessage
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                ClampedText(detail, maxLines = 3)
            }
        }
    }
}

@Composable
private fun ClampedText(text: String, maxLines: Int) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

private fun stateColor(state: AiProfileState) = when (state) {
    AiProfileState.TEST_OK -> OnlineGreen
    AiProfileState.TEST_FAILED, AiProfileState.NEEDS_REVIEW -> DangerRed
    AiProfileState.UNTESTED -> WarnAmber
    AiProfileState.DISABLED, AiProfileState.UNCONFIGURED, AiProfileState.LOCAL -> MutedGray
}
