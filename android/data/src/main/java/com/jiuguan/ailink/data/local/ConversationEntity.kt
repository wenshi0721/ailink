package com.jiuguan.ailink.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话：以「账号 + 对端」为唯一维度，便于未读数、按会话开关自动回复。
 */
@Entity(tableName = "conversations", indices = [Index("accountId"), Index("lastAt")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    /** 对端标识：私聊为 u:<QQ号>，群聊为 g:<群号>。 */
    val peerKey: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessage: String = "",
    val lastAt: Long = 0L,
    val unread: Int = 0,
    val autoReply: Boolean = true
)
