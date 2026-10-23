package com.jiuguan.ailink.data.repo

import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.ConversationDao
import com.jiuguan.ailink.data.local.ConversationEntity
import com.jiuguan.ailink.data.local.MessageDao
import com.jiuguan.ailink.data.local.MessageEntity
import com.jiuguan.ailink.data.local.MessageImage
import com.jiuguan.ailink.data.local.MessageOrigin
import com.jiuguan.ailink.data.gateway.IncomingMessage
import com.jiuguan.ailink.data.gateway.convIdOf
import com.jiuguan.ailink.model.Direction
import com.jiuguan.ailink.model.MessageKind
import kotlinx.coroutines.flow.Flow

class MessageRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {

    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.observeAll()

    fun observeConversations(accountId: String): Flow<List<ConversationEntity>> =
        conversationDao.observeByAccount(accountId)

    fun observeConversation(convId: String): Flow<ConversationEntity?> =
        conversationDao.observe(convId)

    fun observeMessages(convId: String): Flow<List<MessageEntity>> =
        messageDao.observeByConversation(convId)

    fun observeTotalMessages(): Flow<Int> = messageDao.observeTotal()

    fun observeTodayCount(direction: Direction, since: Long): Flow<Int> =
        messageDao.observeCountSince(direction.value, since)

    /** 统计页：时间窗内的全部消息，供分桶聚合。 */
    fun observeSince(since: Long): Flow<List<MessageEntity>> = messageDao.observeSince(since)

    /** 时间窗内发送失败的条数。 */
    fun observeFailedSince(since: Long): Flow<Int> = messageDao.observeFailedSince(since)

    suspend fun recentForContext(convId: String, limit: Int = 8): List<MessageEntity> =
        messageDao.recent(convId, limit).reversed()

    /** 写入收到的消息；重复的消息（同一平台消息 id）会被忽略。 */
    suspend fun recordIncoming(account: AccountEntity, msg: IncomingMessage): Boolean {
        val convId = convIdOf(account.id, msg.peerKey)
        if (msg.platformMsgId.isNotBlank() &&
            messageDao.countByPlatformId(convId, msg.platformMsgId) > 0
        ) {
            return false
        }

        val existing = conversationDao.find(convId)
        conversationDao.upsert(
            ConversationEntity(
                id = convId,
                accountId = account.id,
                peerKey = msg.peerKey,
                title = msg.title.ifBlank { existing?.title ?: msg.peerKey },
                isGroup = msg.isGroup,
                lastMessage = msg.text,
                lastAt = msg.timestamp,
                unread = (existing?.unread ?: 0) + 1,
                autoReply = existing?.autoReply ?: true
            )
        )

        messageDao.insert(
            MessageEntity(
                convId = convId,
                accountId = account.id,
                senderName = msg.senderName,
                content = msg.text,
                direction = Direction.IN.value,
                kind = MessageKind.TEXT.value,
                status = "RECEIVED",
                origin = MessageOrigin.INCOMING,
                platformMsgId = msg.platformMsgId,
                imagesJson = MessageImage.encodeList(
                    msg.images.map { MessageImage(url = it.url, file = it.file) }
                ),
                createdAt = msg.timestamp
            )
        )
        return true
    }

    /** 按平台消息 id 取整条消息（引用回复用）。 */
    suspend fun findByPlatformMsgId(convId: String, platformMsgId: String): MessageEntity? =
        if (platformMsgId.isBlank()) null else messageDao.findByPlatformId(convId, platformMsgId)

    /**
     * 把已下载缓存的文件名回填到消息上。
     *
     * 时机：图片只在"确实要送进模型"时才下载（识图开关与能力判定之后），
     * 而消息在更早的 `recordIncoming` 就已入库，所以 cache 字段只能事后补。
     */
    suspend fun attachImageCache(convId: String, platformMsgId: String, images: List<MessageImage>) {
        if (platformMsgId.isBlank() || images.isEmpty()) return
        val encoded = MessageImage.encodeList(images)
        if (encoded.isBlank()) return
        messageDao.updateImagesJson(convId, platformMsgId, encoded)
    }

    /** 写入发出的消息（自动回复或手动发送）。 */
    suspend fun recordOutgoing(
        account: AccountEntity,
        peerKey: String,
        title: String,
        isGroup: Boolean,
        text: String,
        success: Boolean,
        origin: String = MessageOrigin.MANUAL,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val convId = convIdOf(account.id, peerKey)
        val existing = conversationDao.find(convId)
        conversationDao.upsert(
            ConversationEntity(
                id = convId,
                accountId = account.id,
                peerKey = peerKey,
                title = existing?.title ?: title,
                isGroup = isGroup,
                lastMessage = text,
                lastAt = createdAt,
                unread = existing?.unread ?: 0,
                autoReply = existing?.autoReply ?: true
            )
        )
        messageDao.insert(
            MessageEntity(
                convId = convId,
                accountId = account.id,
                senderName = "我",
                content = text,
                direction = Direction.OUT.value,
                kind = MessageKind.TEXT.value,
                status = if (success) "SENT" else "FAILED",
                origin = origin,
                createdAt = createdAt
            )
        )
    }

    /** 写入系统提示（例如 AI 调用失败原因），便于排查问题。 */
    suspend fun recordSystem(convId: String, accountId: String, text: String) {
        messageDao.insert(
            MessageEntity(
                convId = convId,
                accountId = accountId,
                senderName = "系统",
                content = text,
                direction = Direction.IN.value,
                kind = MessageKind.SYSTEM.value,
                status = "RECEIVED",
                origin = MessageOrigin.SYSTEM,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markRead(convId: String) = conversationDao.markRead(convId)

    suspend fun findConversation(convId: String): ConversationEntity? = conversationDao.find(convId)

    /** 会话级自动回复开关，默认开启。 */
    suspend fun isAutoReplyEnabled(convId: String): Boolean =
        conversationDao.find(convId)?.autoReply ?: true

    suspend fun setAutoReply(convId: String, enabled: Boolean) =
        conversationDao.setAutoReply(convId, enabled)

    suspend fun deleteMessage(id: Long) = messageDao.delete(id)

    suspend fun clearConversation(convId: String) {
        messageDao.deleteByConversation(convId)
        conversationDao.markRead(convId)
    }

    suspend fun clearAll() {
        messageDao.deleteAll()
        conversationDao.deleteAll()
    }

    /** 删除账号时一并清理其会话与消息。 */
    suspend fun clearAccountData(accountId: String) {
        conversationDao.findByAccount(accountId).forEach { conversation ->
            messageDao.deleteByConversation(conversation.id)
        }
        conversationDao.deleteByAccount(accountId)
    }
}
