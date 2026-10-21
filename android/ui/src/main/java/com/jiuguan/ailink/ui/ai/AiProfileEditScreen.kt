package com.jiuguan.ailink.ui.ai

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.BannerTone
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.LabeledField
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SoftDivider
import com.jiuguan.ailink.ui.common.appViewModel
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

    LaunchedEffect(vm.saved) {
        if (vm.saved) nav.popBackStack()
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
                text = if (vm.isNew) "新增 AI 服务" else "编辑 AI 服务",
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

            AppCard {
                LabeledField(
                    label = "配置名称",
                    value = form.name,
                    onValueChange = { v -> vm.update { it.copy(name = v) } },
                    placeholder = "例如：DeepSeek 主力"
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "接口地址",
                    value = form.baseUrl,
                    onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
                    placeholder = "https://api.deepseek.com/v1",
                    helper = "填写到 /v1 即可，应用会自动补全 /chat/completions；留空则使用内置模拟回复"
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "API 密钥",
                    value = form.apiKey,
                    onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
                    placeholder = "sk-...",
                    helper = "仅保存在本机数据库中，不会上传"
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    label = "模型名称",
                    value = form.model,
                    onValueChange = { v -> vm.update { it.copy(model = v) } },
                    placeholder = "deepseek-chat / gpt-4o-mini / qwen-plus"
                )
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
                    Text("测试调用")
                }
            }

            if (vm.testMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = vm.testMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.testMessage.contains("成功")) OnlineGreen else WarnAmber
                )
            }

            Spacer(Modifier.height(36.dp))
        }
    }
}
