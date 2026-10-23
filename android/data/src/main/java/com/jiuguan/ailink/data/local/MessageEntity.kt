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
    /**
     * 本条消息附带的图片引用（JSON 数组，见 [MessageImage]）。空串表示没有图片。
     *
     * 之所以存成 JSON 而不是独立表：单条消息最多 3 张图，`recent()` 一次查询就能
     * 连同图片一起取回，省掉 join；迁移也只是一条 ALTER TABLE。
     */
    val imagesJson: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isOutgoing: Boolean get() = Direction.of(direction) == Direction.OUT
    val isSystem: Boolean get() = kind == MessageKind.SYSTEM.value
    val isAuto: Boolean get() = origin == MessageOrigin.AUTO

    /** 解析出的图片引用；没有图片时为空列表。 */
    val images: List<MessageImage> get() = MessageImage.decodeList(imagesJson)
}

/** 消息来源常量。数据库以字符串存储，避免枚举迁移成本。 */
object MessageOrigin {
    const val AUTO = "AUTO"
    const val MANUAL = "MANUAL"
    const val SYSTEM = "SYSTEM"
    const val INCOMING = "INCOMING"
}
