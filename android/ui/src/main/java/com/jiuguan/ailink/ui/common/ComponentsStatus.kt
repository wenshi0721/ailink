package com.jiuguan.ailink.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiuguan.ailink.model.ConnStatus
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.MutedGray
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.ui.theme.QqBlue
import com.jiuguan.ailink.ui.theme.QqBlueSoft
import com.jiuguan.ailink.ui.theme.WarnAmber
import com.jiuguan.ailink.ui.theme.WeChatGreen
import com.jiuguan.ailink.ui.theme.WeChatGreenSoft
import com.jiuguan.ailink.util.TextUtils

/** 状态相关的原子组件：状态色、状态点、状态胶囊、平台头像。 */
fun statusColor(status: ConnStatus): Color = when (status) {
    ConnStatus.ONLINE -> OnlineGreen
    ConnStatus.CONNECTING -> WarnAmber
    ConnStatus.ERROR -> DangerRed
    ConnStatus.OFFLINE -> MutedGray
}

@Composable
fun StatusDot(status: ConnStatus, size: Dp = 8.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(statusColor(status), CircleShape)
    )
}

@Composable
fun StatusPill(status: ConnStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        StatusDot(status, 6.dp)
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun PlatformAvatar(
    name: String,
    platform: Platform,
    size: Dp = 44.dp
) {
    val bg = if (platform == Platform.WECHAT) WeChatGreenSoft else QqBlueSoft
    val fg = if (platform == Platform.WECHAT) WeChatGreen else QqBlue
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = TextUtils.initials(name),
            color = fg,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

