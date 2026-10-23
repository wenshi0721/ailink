package com.jiuguan.ailink.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiuguan.ailink.model.AiVendor
import com.jiuguan.ailink.model.AiVendorCatalog
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.SoftDivider

/**
 * AI 服务编辑页顶部的「快速配置」：点厂商即预填地址与模型，降低普通用户的配置门槛。
 *
 * 选中态由传入的 [selected] 决定（由接口地址反查得到），因此用户手动改掉地址后会
 * 自动退回「未匹配模板」的提示，不会出现界面显示某厂商、实际地址已被改掉的错位。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiVendorQuickConfig(
    selected: AiVendor?,
    currentModel: String,
    onSelectVendor: (AiVendor) -> Unit,
    onSelectModel: (String) -> Unit
) {
    AppCard {
        Text(
            text = "快速配置",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        MutedText("选择厂商会自动填好接口地址与常用模型，只需再粘贴 API Key。")

        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AiVendorCatalog.vendors.forEach { vendor ->
                FilterChip(
                    selected = selected?.id == vendor.id,
                    onClick = { onSelectVendor(vendor) },
                    label = { Text(vendor.name) }
                )
            }
        }

        if (selected == null) {
            Spacer(Modifier.height(6.dp))
            MutedText("未匹配到内置模板时，下方接口地址仍可手动填写任意 OpenAI 兼容服务。")
            return@AppCard
        }

        if (selected.models.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "常用模型",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                selected.models.forEach { model ->
                    FilterChip(
                        selected = currentModel.trim() == model.id,
                        onClick = { onSelectModel(model.id) },
                        label = { Text(model.label) }
                    )
                }
            }
        }

        if (selected.modelHint.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            MutedText(selected.modelHint)
        }

        Spacer(Modifier.height(10.dp))
        SoftDivider()
        Spacer(Modifier.height(8.dp))
        MutedText("密钥获取：${selected.keyHint}")
    }
}
