package com.jiuguan.ailink.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.ui.theme.DangerRedSoft

/**
 * 连接异常的可执行解释。
 *
 * 运行期错误来自 OkHttp / WebSocket，原文多是英文且只说明"连不上"。
 * 这里统一归纳成三段：哪里错（[title]）、为什么（[reason]）、怎么改（[action]），
 * 界面只负责展示，不再各自拼接截断文本。
 */
data class ErrorGuide(
    val title: String,
    val reason: String,
    val action: String
)

/** 把原始异常/网关错误翻译成固定结构的引导；无法识别时保留原文，避免丢失信息。 */
fun guideFor(raw: String): ErrorGuide {
    val text = raw.trim()
    val lower = text.lowercase()
    return when {
        lower.contains("401") || lower.contains("403") ||
            lower.contains("forbidden") || lower.contains("unauthorized") ||
            lower.contains("token") ->
            ErrorGuide(
                title = "访问令牌被拒绝",
                reason = "桥接端要求鉴权，当前填写的 token 与它不一致。",
                action = "打开桥接端配置复制 token 重新填写，再点「测试连接」。"
            )

        lower.contains("refused") || lower.contains("failed to connect") ||
            lower.contains("econnrefused") || lower.contains("connect failed") ->
            ErrorGuide(
                title = "端口没有服务在监听",
                reason = "地址能到达，但该端口没有 WebSocket 服务；通常是桥接端没启动或端口填错。",
                action = "启动桥接端（NapCat 默认 3001、微信默认 3002），核对端口后重试。"
            )

        lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时") ->
            ErrorGuide(
                title = "连接超时",
                reason = "请求发出后没有在限定时间内响应，多与网络隔离或桥接端卡住有关。",
                action = "确认手机与电脑在同一网络、防火墙放行端口；内置 QQ 首次启动需等 30–60 秒。"
            )

        lower.contains("unable to resolve") || lower.contains("unknownhost") ||
            lower.contains("nodename") || lower.contains("no address") ->
            ErrorGuide(
                title = "地址无法解析",
                reason = "域名或主机名找不到，常见于拼写错误，或用电脑名代替了 IP。",
                action = "局域网请直接填电脑内网 IP，例如 ws://192.168.1.5:3001。"
            )

        lower.contains("404") ->
            ErrorGuide(
                title = "路径不是 OneBot 接口",
                reason = "端口通了，但该路径没有 WebSocket 端点。",
                action = "核对桥接端「正向 WebSocket」地址，通常不带额外路径。"
            )

        lower.contains("login") || lower.contains("登录") || lower.contains("offline") ->
            ErrorGuide(
                title = "桥接端未登录",
                reason = "协议端进程在运行，但账号尚未登录。",
                action = "在桥接端完成扫码登录，登录成功后再点「测试连接」。"
            )

        lower.contains("closed") || lower.contains("1006") || lower.contains("reset") ||
            lower.contains("断开") ->
            ErrorGuide(
                title = "连接被中途关闭",
                reason = "握手后被对端断开，常见于令牌校验失败或协议端重启。",
                action = "查看桥接端日志确认原因；反复出现请核对 token 是否被改动。"
            )

        else -> ErrorGuide(
            title = "连接异常",
            reason = text.ifBlank { "桥接端没有返回具体原因。" },
            action = "确认桥接端在运行、地址与令牌正确，然后重试；仍失败请导出运行日志。"
        )
    }
}

/**
 * 错误引导卡片：比普通横幅多一个恢复入口。
 *
 * 只在「确实需要用户动手」时使用，避免把提示做成第二套导航。
 */
@Composable
fun ErrorGuideCard(
    raw: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val guide = guideFor(raw)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DangerRedSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = guide.title,
            style = MaterialTheme.typography.titleSmall,
            color = DangerRed
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = guide.reason,
            style = MaterialTheme.typography.bodySmall,
            color = DangerRed.copy(alpha = 0.86f)
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "怎么改：${guide.action}",
                style = MaterialTheme.typography.bodySmall,
                color = DangerRed.copy(alpha = 0.86f),
                modifier = Modifier.weight(1f)
            )
            if (actionText != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionText) }
            }
        }
    }
}
