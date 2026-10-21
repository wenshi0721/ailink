package com.jiuguan.ailink.ui.accounts

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jiuguan.ailink.core.PairingBus
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.engine.service.RuntimeState
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.ConnStatus
import com.jiuguan.ailink.model.PairingParser
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.EmptyState
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.PlatformAvatar
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.StatusPill
import com.jiuguan.ailink.ui.common.TagChip
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.QqBlue
import com.jiuguan.ailink.ui.theme.WeChatGreen
import com.jiuguan.ailink.util.TextUtils
import com.jiuguan.ailink.util.TimeFmt

@Composable
fun AccountsScreen(
    nav: NavController,
    vm: AccountsViewModel = appViewModel { AccountsViewModel(it) }
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val states by vm.runtimeStates.collectAsStateWithLifecycle()
    val activeId by vm.activeAccountId.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<AccountEntity?>(null) }
    var showPairDialog by remember { mutableStateOf(false) }
    var pairInput by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "账号",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        MutedText("共 ${accounts.size} 个账号 · ${accounts.count { states[it.id]?.isOnline == true }} 个在线")

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { nav.navigate("account/edit") },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新增账号")
            }
            OutlinedButton(
                onClick = { showPairDialog = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("配对码导入")
            }
        }

        if (vm.localRuntimeInstalled) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    // 复用配对通道：自动填好本机地址与令牌，并触发连通性测试
                    PairingBus.offer(
                        com.jiuguan.ailink.model.PairingInfo(
                            platform = Platform.QQ,
                            name = "内置 QQ",
                            host = com.jiuguan.ailink.engine.container.LocalRuntime.LOCAL_WS_HOST,
                            port = com.jiuguan.ailink.engine.container.LocalRuntime.LOCAL_WS_PORT,
                            token = com.jiuguan.ailink.engine.container.LocalRuntime.LOCAL_WS_TOKEN
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("一键接入手机内置 QQ")
            }
        }

        Spacer(Modifier.height(14.dp))

        if (accounts.isEmpty()) {
            EmptyState(
                title = "还没有账号",
                description = "添加 QQ 或微信账号，配置 AI 服务后即可自动回复"
            )
        } else {
            accounts.forEach { account ->
                AccountRowCard(
                    account = account,
                    state = states[account.id] ?: RuntimeState(account.id),
                    isActive = activeId == account.id,
                    onEdit = { nav.navigate("account/edit?accountId=${account.id}") },
                    onSetActive = { vm.setActive(account.id) },
                    onToggle = { enabled -> vm.setEnabled(account, enabled) },
                    onDelete = { pendingDelete = account }
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = {
                showPairDialog = false
                pairError = ""
            },
            title = { Text("配对码导入") },
            text = {
                Column {
                    MutedText("在电脑上执行 node tools/pairing/make-pairing.mjs，把 ailink:// 开头的那一行复制过来。")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pairInput,
                        onValueChange = {
                            pairInput = it
                            pairError = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("ailink://pair?platform=qq&host=...") },
                        minLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    if (pairError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = pairError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val info = PairingParser.parse(pairInput)
                    if (info == null) {
                        pairError = "格式无法识别，请确认粘贴的是完整的 ailink://pair?... 内容"
                    } else {
                        PairingBus.offer(info)
                        showPairDialog = false
                        pairInput = ""
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPairDialog = false
                    pairError = ""
                }) { Text("取消") }
            }
        )
    }

    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账号") },
            text = {
                Text("将删除「${account.displayName.ifBlank { "未命名账号" }}」及其全部消息记录，此操作不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(account)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AccountRowCard(
    account: AccountEntity,
    state: RuntimeState,
    isActive: Boolean,
    onEdit: () -> Unit,
    onSetActive: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val platformColor = if (account.platformType == Platform.WECHAT) WeChatGreen else QqBlue

    AppCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformAvatar(
                name = account.displayName.ifBlank { "账号" },
                platform = account.platformType,
                size = 44.dp
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
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("当前", MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    TagChip(account.platformType.label, platformColor)
                    Spacer(Modifier.width(6.dp))
                    TagChip(
                        account.bridge.shortLabel,
                        if (account.bridge == BridgeType.DEMO) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    StatusPill(state.status)
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("设为当前账号") },
                        onClick = {
                            menuOpen = false
                            onSetActive()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑配置") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除账号", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SoftDivider()
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.accountNo.ifBlank { "未填写账号" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                MutedText(
                    when {
                        state.status == ConnStatus.ERROR && state.lastError.isNotBlank() ->
                            "异常：${TextUtils.truncate(state.lastError, 24)}"
                        state.status == ConnStatus.CONNECTING -> "正在重连，连接恢复后自动继续"
                        state.lastActiveAt > 0 -> "最近活动 ${TimeFmt.relative(state.lastActiveAt)}"
                        account.autoReply -> "自动回复已开启"
                        else -> "自动回复已关闭"
                    }
                )
            }
            Switch(
                checked = account.enabled,
                onCheckedChange = onToggle,
                colors = appSwitchColors()
            )
        }
    }
}
