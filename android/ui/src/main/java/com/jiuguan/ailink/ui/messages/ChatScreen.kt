package com.jiuguan.ailink.ui.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jiuguan.ailink.data.local.MessageEntity
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.ui.common.MutedText
import com.jiuguan.ailink.ui.common.PlatformAvatar
import com.jiuguan.ailink.ui.common.StatusDot
import com.jiuguan.ailink.ui.common.appSwitchColors
import com.jiuguan.ailink.ui.common.appViewModel
import com.jiuguan.ailink.ui.theme.DangerRed
import com.jiuguan.ailink.util.TimeFmt

@Composable
fun ChatScreen(
    nav: NavController,
    convId: String,
    vm: ChatViewModel = appViewModel(key = "chat-$convId") { ChatViewModel(it, convId) }
) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val states by vm.runtimeStates.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val state = account?.let { states[it.id] }
    val platform = account?.platformType ?: Platform.QQ
    val isGroup = conversation?.isGroup == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation?.title ?: "会话",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state?.status ?: com.jiuguan.ailink.model.ConnStatus.OFFLINE, 6.dp)
                    Spacer(Modifier.width(5.dp))
                    MutedText(
                        buildString {
                            append(if (isGroup) "群聊" else "私聊")
                            append(" · ")
                            append(account?.displayName?.ifBlank { account?.platformType?.label ?: "未绑定账号" }
                                ?: "未绑定账号")
                        }
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("清空聊天记录") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            vm.clearHistory()
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "该会话自动回复",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = conversation?.autoReply ?: true,
                onCheckedChange = vm::setAutoReply,
                colors = appSwitchColors()
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MutedText("暂无消息，等待对方发来消息或主动发送一条")
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        val previous = messages.getOrNull(index - 1)
                        val showTime = previous == null ||
                            message.createdAt - previous.createdAt > 5 * 60_000L
                        MessageBubble(
                            message = message,
                            platform = platform,
                            isGroup = isGroup,
                            showTime = showTime,
                            resolveImage = { cache -> vm.imageFile(cache) },
                            onRetry = { vm.retry(message) }
                        )
                    }
                }
            }
        }

        if (vm.notice.isNotBlank()) {
            Text(
                text = vm.notice,
                style = MaterialTheme.typography.bodySmall,
                color = DangerRed,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = vm.input,
                onValueChange = vm::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("输入消息…", style = MaterialTheme.typography.bodyMedium)
                },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = vm::send,
                enabled = vm.input.isNotBlank(),
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        if (vm.input.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "发送",
                    tint = if (vm.input.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 历史图片缩略图。
 *
 * 只读 `filesDir/imgcache` 里的本地副本——QQ 图床 URL 带 rkey 会过期，
 * 界面上再去拉网络图只会得到 403，反而比不显示更糟。
 */
@Composable
private fun MessageThumb(file: java.io.File) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    ?: return@runCatching null
                val maxSide = maxOf(decoded.width, decoded.height).coerceAtLeast(1)
                val scale = 220f / maxSide
                val scaled = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    decoded
                }
                scaled.asImageBitmap()
            }.getOrNull()
        }
    }

    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = "图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(110.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    platform: Platform,
    isGroup: Boolean,
    showTime: Boolean,
    resolveImage: (String) -> java.io.File?,
    onRetry: () -> Unit
) {
    if (message.isSystem) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTime) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = TimeFmt.chatStamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!message.isOutgoing) {
                PlatformAvatar(message.senderName.ifBlank { "?" }, platform, 32.dp)
                Spacer(Modifier.width(8.dp))
            }

            Column(
                horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 260.dp)
            ) {
                if (isGroup && !message.isOutgoing) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .background(
                            color = if (message.isOutgoing) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(
                                topStart = 14.dp,
                                topEnd = 14.dp,
                                bottomStart = if (message.isOutgoing) 14.dp else 4.dp,
                                bottomEnd = if (message.isOutgoing) 4.dp else 14.dp
                            )
                        )
                        .then(
                            if (message.isOutgoing) Modifier
                            else Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(
                                    topStart = 14.dp,
                                    topEnd = 14.dp,
                                    bottomStart = 4.dp,
                                    bottomEnd = 14.dp
                                )
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Column {
                        if (message.isAuto) {
                            Text(
                                text = "AI 自动回复",
                                fontSize = 9.5.sp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.isOutgoing) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        // 历史图片缩略图：只读本地缓存，不触发网络（缓存缺失就不显示）
                        val thumbs = remember(message.imagesJson) {
                            message.images.mapNotNull { image -> resolveImage(image.cache) }
                        }
                        if (thumbs.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                thumbs.take(3).forEach { file -> MessageThumb(file) }
                            }
                        }
                    }
                }

                if (message.status == "FAILED") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "发送失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = DangerRed
                        )
                        if (message.isOutgoing && !message.isSystem) {
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = "重试",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DangerRed
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
