package com.jiuguan.ailink.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jiuguan.ailink.model.Direction
import com.jiuguan.ailink.model.MessageKind

/** 消息记录。 */
@Entity(
    tableName = "messages",
    indices = [Index("convId"), Index("createdAt"), Index("platformMsgId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val convId: String,
    val accountId: String,
    val senderName: String = "",
    val content: String = "",
    val direction: String = Direction.IN.value,
    val kind: String = MessageKind.TEXT.value,
    /** SENT / FAILED / RECEIVED */
    val status: String = "SENT",
    /** 消息来源：AUTO（AI 自动回复）/ MANUAL（聊天页手动发送）/ SYSTEM（系统提示） */
    val origin: String = MessageOrigin.MANUAL,
    val platformMsgId: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isOutgoing: Boolean get() = Direction.of(direction) == Direction.OUT
    val isSystem: Boolean get() = kind == MessageKind.SYSTEM.value
    val isAuto: Boolean get() = origin == MessageOrigin.AUTO
}

/** 消息来源常量。数据库以字符串存储，避免枚举迁移成本。 */
object MessageOrigin {
    const val AUTO = "AUTO"
    const val MANUAL = "MANUAL"
    const val SYSTEM = "SYSTEM"
    const val INCOMING = "INCOMING"
}
