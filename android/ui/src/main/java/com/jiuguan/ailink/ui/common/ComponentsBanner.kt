package com.jiuguan.ailink.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.DangerRedSoft
import com.jiuguan.ailink.ui.theme.OnlineGreen
import com.jiuguan.ailink.ui.theme.OnlineGreenSoft
import com.jiuguan.ailink.ui.theme.QqBlue
import com.jiuguan.ailink.ui.theme.QqBlueSoft
import com.jiuguan.ailink.ui.theme.WarnAmber
import com.jiuguan.ailink.ui.theme.WarnAmberSoft

/** 提示横幅：用于承载说明、告警与错误信息。 */
@Composable
fun InfoBanner(
    title: String,
    message: String,
    tone: BannerTone = BannerTone.INFO,
    modifier: Modifier = Modifier
) {
    val bg = when (tone) {
        BannerTone.INFO -> QqBlueSoft
        BannerTone.SUCCESS -> OnlineGreenSoft
        BannerTone.WARNING -> WarnAmberSoft
        BannerTone.DANGER -> DangerRedSoft
    }
    val fg = when (tone) {
        BannerTone.INFO -> QqBlue
        BannerTone.SUCCESS -> OnlineGreen
        BannerTone.WARNING -> WarnAmber
        BannerTone.DANGER -> DangerRed
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = fg
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.86f)
            )
        }
    }
}

