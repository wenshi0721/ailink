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
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.data.local.ConversationEntity
import com.jiuguan.ailink.engine.service.RuntimeState
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.ConnStatus
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.EmptyState
import com.jiuguan.ailink.ui.common.ErrorGuideCard
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
import com.jiuguan.ailink.ui.common.guideFor
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.ui.theme.QqBlue
import com.jiuguan.ailink.ui.theme.WarnAmber
import com.jiuguan.ailink.ui.theme.WeChatGreen
import com.jiuguan.ailink.util.TextUtils
import com.jiuguan.ailink.util.TimeFmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 首页「待处理」条目：标题 + 一句做法 + 一个入口。 */
private data class AttentionItem(
    val title: String,
    val detail: String,
    val action: String,
    val onAction: () -> Unit
)

@Composable
fun HomeScreen(
    nav: NavController,
    vm: HomeViewModel = appViewModel { HomeViewModel(it) }
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val aiProfiles by vm.aiProfiles.collectAsStateWithLifecycle()
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
    val hasDemoAccount = accounts.any { it.bridgeType == BridgeType.DEMO.name }
    val realAccountCount = accounts.count { it.bridgeType != BridgeType.DEMO.name }
    val enabledAccounts = accounts.filter { it.enabled }
    val errorAccounts = accounts.filter { states[it.id]?.status == ConnStatus.ERROR }
    val usableAi = aiProfiles.any { !it.isLocalOnly && it.enabled && !it.needsReview }
    val testedAi = aiProfiles.any { !it.isLocalOnly && it.enabled && !it.needsReview && it.lastTestOk }
    val legacyPending = aiProfiles.count { it.needsReview }
    val currentProfile = activeAccount?.let { account ->
        aiProfiles.firstOrNull { it.id == account.aiProfileId }
    } ?: aiProfiles.firstOrNull { it.isDefault && it.enabled && !it.needsReview }
    val guideDone = realAccountCount > 0 && testedAi && running
    val dateLine = remember {
        SimpleDateFormat("M 月 d 日 · EEEE", Locale.CHINA).format(Date())
    }

    // 待处理清单：只收「必须用户动手」的问题，按影响面排序
    val attention = buildList<AttentionItem> {
        errorAccounts.take(2).forEach { account ->
            val guide = guideFor(states[account.id]?.lastError.orEmpty())
            add(
                AttentionItem(
                    title = "「${account.displayName.ifBlank { "未命名账号" }}」${guide.title}",
                    detail = guide.action,
                    action = "去修复",
                    onAction = { nav.navigate("account/edit?accountId=${account.id}") }
                )
            )
        }
        if (legacyPending > 0) {
            add(
                AttentionItem(
                    title = "$legacyPending 个旧版 API 配置待确认",
                    detail = "升级后已停用，不会自动生效；核对地址与密钥后再启用。",
                    action = "去确认",
                    onAction = { nav.navigate("ai") }
                )
            )
        }
        if (currentProfile != null && !currentProfile.isLocalOnly && currentProfile.enabled &&
            !currentProfile.needsReview && currentProfile.lastTestAt <= 0L
        ) {
            add(
                AttentionItem(
                    title = "「${currentProfile.name.ifBlank { "未命名配置" }}」还没测试",
                    detail = "没验证过的密钥会在收到消息时才报错，建议现在测一次。",
                    action = "去测试",
                    onAction = { nav.navigate("ai") }
                )
            )
        }
        // 「服务未启动」由上方三步引导负责，这里不再重复列出
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

        if (!guideDone) {
            SetupGuide(
                realAccountCount = realAccountCount,
                aiConfigured = usableAi,
                aiTested = testedAi,
                running = running,
                onAddAccount = { nav.navigate("account/edit") },
                onOpenAi = { nav.navigate("ai") },
                onStart = { vm.setEngineRunning(true) }
            )
            Spacer(Modifier.height(14.dp))
        }

        if (attention.isNotEmpty()) {
            AttentionCard(items = attention)
            Spacer(Modifier.height(14.dp))
        }

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
                            TagChip(
                                text = if (enabledAccounts.isEmpty()) "空转中" else "运行中",
                                color = if (enabledAccounts.isEmpty()) WarnAmber else OnlineGreen
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    MutedText(
                        when {
                            !running -> "服务已停止，账号处于离线状态"
                            enabledAccounts.isEmpty() -> "服务已开启，但还没有参与接入的账号"
                            onlineCount == 0 && errorAccounts.isNotEmpty() -> "服务已开启，账号连接失败"
                            onlineCount == 0 -> "服务已开启，正在连接账号…"
                            else -> "$onlineCount 个账号在线，收到的消息将自动回复"
                        }
                    )
                }
                Switch(
                    checked = running,
                    onCheckedChange = vm::setEngineRunning,
                    colors = appSwitchColors()
                )
            }

            if (running && enabledAccounts.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    title = "现在收不到任何消息",
                    message = "总开关已打开，但没有启用的账号。先添加账号，或在账号页把开关打开。",
                    tone = com.jiuguan.ailink.ui.common.BannerTone.WARNING
                )
            } else if (running && onlineCount == 0 && errorAccounts.isNotEmpty()) {
                val guide = guideFor(states[errorAccounts.first().id]?.lastError.orEmpty())
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    title = "账号还没连上：${guide.title}",
                    message = guide.action,
                    tone = com.jiuguan.ailink.ui.common.BannerTone.DANGER
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
                aiProfile = currentProfile,
                state = states[activeAccount.id] ?: RuntimeState(activeAccount.id),
                onEdit = { nav.navigate("account/edit?accountId=${activeAccount.id}") },
                onManageAi = { nav.navigate("ai") },
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
    aiProfile: com.jiuguan.ailink.data.local.AiProfileEntity?,
    state: RuntimeState,
    onEdit: () -> Unit,
    onManageAi: () -> Unit,
    onToggleOnline: (Boolean) -> Unit
) {
    val platformColor = if (account.platformType == Platform.WECHAT) WeChatGreen else QqBlue
    val aiName = aiProfile?.name?.ifBlank { "未命名配置" } ?: "内置模拟回复"
    val (aiState, aiStateColor) = when {
        aiProfile == null || aiProfile.isLocalOnly -> "内置回复" to MaterialTheme.colorScheme.onSurfaceVariant
        aiProfile.needsReview -> "旧配置待确认" to WarnAmber
        !aiProfile.enabled -> "已停用" to MaterialTheme.colorScheme.onSurfaceVariant
        aiProfile.lastTestAt <= 0L -> "未测试" to WarnAmber
        aiProfile.lastTestOk -> "测试通过" to OnlineGreen
        else -> "测试失败" to MaterialTheme.colorScheme.error
    }

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
                            state.lastError.isNotBlank() -> guideFor(state.lastError).title
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

        if (state.lastError.isNotBlank() && state.status != ConnStatus.ONLINE) {
            Spacer(Modifier.height(10.dp))
            ErrorGuideCard(
                raw = state.lastError,
                actionText = "去修复",
                onAction = onEdit
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI 服务",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = aiName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (aiProfile?.isDefault == true) {
                Spacer(Modifier.width(4.dp))
                TagChip("默认", MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(6.dp))
            TagChip(aiState, aiStateColor)
            TextButton(
                onClick = onManageAi,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("管理") }
        }

        // 测试结论带时间才有意义：三天前通过不代表现在还能用
        if (aiProfile != null && !aiProfile.isLocalOnly && aiProfile.lastTestAt > 0) {
            MutedText(
                (if (aiProfile.lastTestOk) "上次测试通过于 " else "上次测试失败于 ") +
                    TimeFmt.relative(aiProfile.lastTestAt)
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("编辑配置")
            }
            Spacer(Modifier.width(16.dp))
            MutedText(
                when {
                    !account.enabled -> "该账号未参与接入"
                    !state.isOnline -> "服务运行时会自动上线"
                    else -> "开关可临时下线该账号"
                }
            )
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

/** 首页「待处理」清单：把需要动手的问题集中到一处，避免散落在各页。 */
@Composable
private fun AttentionCard(items: List<AttentionItem>) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "待处理",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TagChip("${items.size} 项", WarnAmber)
        }
        Spacer(Modifier.height(6.dp))

        items.forEachIndexed { index, item ->
            if (index > 0) {
                SoftDivider(Modifier.padding(vertical = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    MutedText(item.detail)
                }
                TextButton(onClick = item.onAction) { Text(item.action) }
            }
        }
    }
}

/**
 * 首次使用的三步引导。
 *
 * 只保留「当前这一步」的操作按钮，已完成与未轮到的步骤弱化展示，
 * 避免三行都放按钮导致用户不知道先点哪个。
 */
@Composable
private fun SetupGuide(
    realAccountCount: Int,
    aiConfigured: Boolean,
    aiTested: Boolean,
    running: Boolean,
    onAddAccount: () -> Unit,
    onOpenAi: () -> Unit,
    onStart: () -> Unit
) {
    val step1 = realAccountCount > 0
    val step2 = aiTested
    val step3 = running
    val doneCount = listOf(step1, step2, step3).count { it }
    val current = when {
        !step1 -> 1
        !step2 -> 2
        !step3 -> 3
        else -> 0
    }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "开始使用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TagChip("$doneCount / 3 完成", if (doneCount == 3) OnlineGreen else WarnAmber)
        }
        Spacer(Modifier.height(4.dp))
        MutedText("按顺序完成三步；现在做到第 $current 步。")

        Spacer(Modifier.height(12.dp))
        GuideStep(
            index = 1,
            title = "接入一个账号",
            detail = if (step1) {
                "已接入 $realAccountCount 个真实账号"
            } else {
                "手机内置 QQ、电脑桥接或微信，任选一种"
            },
            done = step1,
            active = current == 1,
            actionText = "去添加",
            onAction = onAddAccount
        )
        SoftDivider(Modifier.padding(vertical = 8.dp))
        GuideStep(
            index = 2,
            title = "配置并测试 AI 服务",
            detail = when {
                step2 -> "已测试通过，收到消息就能调用模型"
                aiConfigured -> "已填好地址与密钥，但还没测试通过"
                else -> "粘贴 API Key 即可，内置主流厂商模板"
            },
            done = step2,
            active = current == 2,
            actionText = if (aiConfigured) "去测试" else "去配置",
            onAction = onOpenAi
        )
        SoftDivider(Modifier.padding(vertical = 8.dp))
        GuideStep(
            index = 3,
            title = "启动接入服务",
            detail = if (step3) "服务运行中，退出应用仍保持在线" else "打开后会保持在线并自动回复",
            done = step3,
            active = current == 3,
            actionText = "启动",
            onAction = onStart
        )
    }
}

@Composable
private fun GuideStep(
    index: Int,
    title: String,
    detail: String,
    done: Boolean,
    active: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !done) { onAction() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    when {
                        done -> OnlineGreen
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (done) "✓" else index.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (done || active) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done || active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    TagChip("当前", MaterialTheme.colorScheme.primary)
                }
            }
            MutedText(detail)
        }
        if (active) {
            TextButton(onClick = onAction) { Text(actionText) }
        } else if (done) {
            Text(
                text = "已完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
