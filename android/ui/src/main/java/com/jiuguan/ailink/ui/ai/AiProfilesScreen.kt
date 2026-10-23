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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.EmptyState
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.TagChip
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.MutedGray

@Composable
fun AiProfilesScreen(
    nav: NavController,
    vm: AiProfilesViewModel = appViewModel { AiProfilesViewModel(it) }
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<AiProfileEntity?>(null) }

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
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            InfoBanner(
                title = "一键套用主流厂商模板",
                message = "新增时点选 DeepSeek、通义千问、Kimi、智谱、豆包等厂商，接口地址与模型会自动填好，只需粘贴 API Key；也支持手动填写任意 OpenAI 兼容服务。",
                tone = com.jiuguan.ailink.ui.common.BannerTone.INFO
            )

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
                        onEdit = { nav.navigate("ai/edit?profileId=${profile.id}") },
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
            text = { Text("删除后，引用该配置的账号将回退到默认配置或内置模拟回复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(profile)
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
private fun AiProfileCard(
    profile: AiProfileEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
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
                    if (profile.isLocalOnly) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("内置", MutedGray)
                    }
                }
                Spacer(Modifier.height(4.dp))
                MutedText(
                    if (profile.isLocalOnly) "不调用外部接口，使用本地模板回复"
                    else "${profile.model.ifBlank { "未填模型" }} · ${profile.baseUrl}"
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SoftDivider()
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MutedText(
                text = if (profile.apiKey.isBlank()) "未设置密钥" else "密钥已设置",
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
