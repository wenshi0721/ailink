package com.jiuguan.ailink.ui.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.ConversationEntity
import com.jiuguan.ailink.engine.service.RuntimeState
import com.jiuguan.ailink.model.ConnStatus
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.EmptyState
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.PlatformAvatar
import com.jiuguan.ailink.ui.common.SectionHeader
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.StatTile
import com.jiuguan.ailink.ui.common.StatusDot
import com.jiuguan.ailink.ui.common.StatusPill
import com.jiuguan.ailink.ui.common.TagChip
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.ui.theme.QqBlue
import com.jiuguan.ailink.ui.theme.WeChatGreen
import com.jiuguan.ailink.util.TextUtils
import com.jiuguan.ailink.util.TimeFmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    nav: NavController,
    vm: HomeViewModel = appViewModel { HomeViewModel(it) }
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val states by vm.runtimeStates.collectAsStateWithLifecycle()
    val activeId by vm.activeAccountId.collectAsStateWithLifecycle()
    val running by vm.engineRunning.collectAsStateWithLifecycle()
    val recent by vm.recentConversations.collectAsStateWithLifecycle()
    val todayIn by vm.todayIncoming.collectAsStateWithLifecycle()
    val todayOut by vm.todayReplied.collectAsStateWithLifecycle()
    val todayFailed by vm.todayFailed.collectAsStateWithLifecycle()

    val successRate = if (todayOut > 0) {
        ((todayOut - todayFailed).coerceAtLeast(0) * 100 / todayOut)
    } else null

    val onlineCount = accounts.count { states[it.id]?.isOnline == true }
    val activeAccount = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
    val hasDemoAccount = accounts.any { it.bridgeType == com.jiuguan.ailink.model.BridgeType.DEMO.name }
    val dateLine = remember {
        SimpleDateFormat("M 月 d 日 · EEEE", Locale.CHINA).format(Date())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "接入助手",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                MutedText(dateLine)
            }
            IconButton(onClick = { nav.navigate("settings") }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        AccountFilterStrip(
            accounts = accounts,
            activeId = activeId,
            states = states,
            onSelect = vm::selectAccount,
            onAdd = { nav.navigate("account/edit") }
        )

        Spacer(Modifier.height(14.dp))

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (running) statusOfAny(states, accounts) else ConnStatus.OFFLINE, 9.dp)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "接入服务",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (running) {
                            Spacer(Modifier.width(8.dp))
                            TagChip("运行中", OnlineGreen)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    MutedText(
                        if (running) "账号保持在线，收到的消息将自动回复"
                        else "服务已停止，账号处于离线状态"
                    )
                }
                Switch(
                    checked = running,
                    onCheckedChange = vm::setEngineRunning,
                    colors = appSwitchColors()
                )
            }

            Spacer(Modifier.height(14.dp))
            SoftDivider()
            Spacer(Modifier.height(12.dp))

            Row {
                StatTile(
                    value = "$onlineCount/${accounts.size}",
                    label = "在线账号",
                    modifier = Modifier.weight(1f),
                    valueColor = if (onlineCount > 0) OnlineGreen else MaterialTheme.colorScheme.onSurface
                )
                StatTile(
                    value = todayIn.toString(),
                    label = "今日收到",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = todayOut.toString(),
                    label = "今日回复",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = successRate?.let { "$it%" } ?: "—",
                    label = "成功率",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        if (activeAccount != null) {
            SectionHeader(title = "当前账号")
            Spacer(Modifier.height(8.dp))
            ActiveAccountCard(
                account = activeAccount,
                state = states[activeAccount.id] ?: RuntimeState(activeAccount.id),
                onEdit = { nav.navigate("account/edit?accountId=${activeAccount.id}") },
                onToggleOnline = { online ->
                    vm.setAccountEnabled(activeAccount, online)
                }
            )
        } else {
            EmptyState(
                title = "还没有接入账号",
                description = "点击上方「新增」添加一个 QQ 或微信账号"
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader(
            title = "最近消息",
            actionText = "查看全部",
            onAction = { nav.navigate("messages") }
        )
        Spacer(Modifier.height(8.dp))

        if (recent.isEmpty()) {
            AppCard {
                EmptyState(
                    title = "暂无消息记录",
                    description = "启动接入服务后，收到的消息会显示在这里"
                )
            }
        } else {
            AppCard(contentPadding = 6.dp) {
                recent.take(4).forEachIndexed { index, conversation ->
                    val account = accounts.firstOrNull { it.id == conversation.accountId }
                    RecentConversationRow(
                        conversation = conversation,
                        platform = account?.platformType ?: Platform.QQ,
                        onClick = { nav.navigate("chat/${Uri.encode(conversation.id)}") }
                    )
                    if (index != recent.take(4).lastIndex) {
                        SoftDivider(Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (hasDemoAccount) {
            InfoBanner(
                title = "演示模式可用",
                message = "「本地演示」账号不连接任何平台，按脚本模拟收发消息，可直接验证自动回复流程。",
                tone = com.jiuguan.ailink.ui.common.BannerTone.INFO
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

private fun statusOfAny(states: Map<String, RuntimeState>, accounts: List<AccountEntity>): ConnStatus {
    val list = accounts.mapNotNull { states[it.id] }
    return when {
        list.any { it.status == ConnStatus.ONLINE } -> ConnStatus.ONLINE
        list.any { it.status == ConnStatus.CONNECTING } -> ConnStatus.CONNECTING
        list.any { it.status == ConnStatus.ERROR } -> ConnStatus.ERROR
        else -> ConnStatus.OFFLINE
    }
}

@Composable
private fun AccountFilterStrip(
    accounts: List<AccountEntity>,
    activeId: String,
    states: Map<String, RuntimeState>,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = activeId.isBlank(),
                onClick = { onSelect("") },
                label = { Text("全部账号") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        items(accounts, key = { it.id }) { account ->
            FilterChip(
                selected = activeId == account.id,
                onClick = { onSelect(account.id) },
                label = { Text(account.displayName.ifBlank { "未命名" }) },
                leadingIcon = {
                    StatusDot(
                        status = states[account.id]?.status ?: ConnStatus.OFFLINE,
                        size = 7.dp
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onAdd,
                label = { Text("新增") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun ActiveAccountCard(
    account: AccountEntity,
    state: RuntimeState,
    onEdit: () -> Unit,
    onToggleOnline: (Boolean) -> Unit
) {
    val platformColor = if (account.platformType == Platform.WECHAT) WeChatGreen else QqBlue

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformAvatar(
                name = account.displayName.ifBlank { "账号" },
                platform = account.platformType,
                size = 46.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.displayName.ifBlank { "未命名账号" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    TagChip(account.platformType.label, platformColor)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(state.status)
                    Spacer(Modifier.width(8.dp))
                    MutedText(
                        when {
                            state.status == ConnStatus.ONLINE && state.connectedAt > 0 ->
                                "已在线 ${TimeFmt.duration(System.currentTimeMillis() - state.connectedAt)}"
                            state.lastError.isNotBlank() -> TextUtils.truncate(state.lastError, 18)
                            else -> account.bridge.shortLabel
                        }
                    )
                }
            }
            Switch(
                checked = account.enabled,
                onCheckedChange = onToggleOnline,
                colors = appSwitchColors()
            )
        }

        Spacer(Modifier.height(14.dp))
        SoftDivider()
        Spacer(Modifier.height(12.dp))

        Row {
            MiniMetric("接入方式", account.bridge.shortLabel, Modifier.weight(1f))
            MiniMetric(
                "延迟",
                if (state.latencyMs >= 0) "${state.latencyMs} ms" else "—",
                Modifier.weight(1f)
            )
            MiniMetric("本次收发", "${state.received} / ${state.replied}", Modifier.weight(1f))
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("编辑配置")
            }
            Spacer(Modifier.width(16.dp))
            MutedText(if (!account.enabled) "该账号未参与接入" else if (state.isOnline) "开关可临时下线该账号" else "服务运行时会自动上线")
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecentConversationRow(
    conversation: ConversationEntity,
    platform: Platform,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlatformAvatar(conversation.title, platform, 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title.ifBlank { "未知会话" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = TimeFmt.relative(conversation.lastAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TextUtils.preview(conversation.lastMessage, 26),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (conversation.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(17.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (conversation.unread > 99) "99" else conversation.unread.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
