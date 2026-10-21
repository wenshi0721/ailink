package com.jiuguan.ailink.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jiuguan.ailink.ui.common.AppCard
import com.jiuguan.ailink.ui.common.EmptyState
import com.jiuguan.ailink.ui.common.InfoBanner
import com.jiuguan.ailink.ui.common.BannerTone
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.PlatformAvatar
import com.jiuguan.ailink.ui.common.StatTile
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.ChartBarSoft
import com.jiuguan.ailink.ui.theme.OnlineGreen
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    nav: NavController,
    vm: StatsViewModel = appViewModel { StatsViewModel(it) }
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "统计",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        MutedText("就近 ${state.period.label} 的消息汇总 · 数据保存在本机")

        Spacer(Modifier.height(12.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StatsPeriod.entries.toList(), key = { it.name }) { item ->
                FilterChip(
                    selected = state.period == item,
                    onClick = { vm.setPeriod(item) },
                    label = { Text(item.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        if (state.loading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))

        AppCard {
            Row {
                StatTile(
                    value = state.received.toString(),
                    label = "收到",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = state.replied.toString(),
                    label = "已回复",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = state.successRate?.let { "$it%" } ?: "—",
                    label = "成功率",
                    modifier = Modifier.weight(1f),
                    valueColor = when {
                        state.successRate == null -> MaterialTheme.colorScheme.onSurface
                        state.successRate!! >= 95 -> OnlineGreen
                        state.successRate!! >= 80 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                StatTile(
                    value = formatSeconds(state.avgResponseMs),
                    label = "平均首响",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "消息量趋势",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (state.period.byHour) "按小时 · 峰值 ${state.peak} 条" else "按天 · 峰值 ${state.peak} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            if (state.hasData) {
                TrendChart(points = state.trend, height = 132.dp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChartLegendItem("收到", MaterialTheme.colorScheme.primary)
                    ChartLegendItem("已回复", ChartBarSoft)
                }
            } else {
                EmptyState(
                    title = "这段时间还没有消息",
                    description = "启动接入服务并收到消息后，这里会生成趋势图"
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        AppCard {
            Text(
                text = "AI 回复质量",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                QualityDonut(state.successRate)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    QualityRow("已回复", "${state.replied} 条")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    QualityRow(
                        label = "发送失败",
                        value = "${state.failed} 次",
                        valueColor = if (state.failed > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    QualityRow("平均首响", formatSeconds(state.avgResponseMs))
                }
            }
            if (state.failed > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "失败的具体原因可在对应会话的记录里查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.shares.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            AppCard {
                Text(
                    text = "账号分布",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                state.shares.forEach { share ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlatformAvatar(share.name, share.platform, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = share.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${share.count} 条 · ${(share.fraction * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(3.dp)
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(share.fraction.coerceIn(0.02f, 1f))
                                        .fillMaxHeight()
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        InfoBanner(
            title = "口径说明",
            message = "成功率 = 发出成功条数 / 全部发出条数；平均首响取同一会话内 10 分钟内首次回复的间隔均值。",
            tone = BannerTone.INFO
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ChartLegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrendChart(points: List<TrendPoint>, height: Dp) {
    val max = maxOf(1, points.maxOfOrNull { maxOf(it.received, it.replied) } ?: 1)
    val barWidth = when {
        points.size > 20 -> 3.dp
        points.size > 10 -> 5.dp
        else -> 8.dp
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        verticalAlignment = Alignment.Bottom
    ) {
        points.forEach { point ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    BarColumn(point.received, max, barWidth, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(2.dp))
                    BarColumn(point.replied, max, barWidth, ChartBarSoft)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = point.label,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BarColumn(value: Int, max: Int, width: Dp, color: Color) {
    val fraction = (value.toFloat() / max).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight(fraction)
            .background(color, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
    )
}

@Composable
private fun QualityDonut(rate: Int?) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            if (rate != null) {
                drawArc(
                    color = OnlineGreen,
                    startAngle = -90f,
                    sweepAngle = 360f * rate / 100f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = rate?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "成功率",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QualityRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor
        )
    }
}

private fun formatSeconds(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 1_000L -> "$ms ms"
    ms < 60_000L -> String.format(Locale.getDefault(), "%.1f 秒", ms / 1000f)
    else -> "${ms / 60_000L} 分"
}
