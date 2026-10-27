package com.jiuguan.ailink.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE convId = :convId ORDER BY createdAt ASC")
    fun observeByConversation(convId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE convId = :convId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(convId: String, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE convId = :convId AND platformMsgId = :platformMsgId")
    suspend fun countByPlatformId(convId: String, platformMsgId: String): Int

    /** 按平台消息 id 取整条：引用回复（reply 段）要靠它还原被引用的图片与文本。 */
    @Query(
        "SELECT * FROM messages WHERE convId = :convId AND platformMsgId = :platformMsgId " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun findByPlatformId(convId: String, platformMsgId: String): MessageEntity?

    /** 图片下载缓存后回填 cache 文件名，供后续轮次直接读本地文件。 */
    @Query("UPDATE messages SET imagesJson = :imagesJson WHERE convId = :convId AND platformMsgId = :platformMsgId")
    suspend fun updateImagesJson(convId: String, platformMsgId: String, imagesJson: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE convId = :convId")
    suspend fun deleteByConversation(convId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE direction = :direction AND createdAt >= :since")
    fun observeCountSince(direction: String, since: Long): Flow<Int>

    /** 统计页：按时间窗取全部分桶原始数据（个人库量级在千条内，内存聚合即可）。 */
    @Query("SELECT * FROM messages WHERE createdAt >= :since ORDER BY createdAt ASC")
    fun observeSince(since: Long): Flow<List<MessageEntity>>

    /** 发出但失败的条数，用于计算发送成功率。 */
    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'OUT' AND status = 'FAILED' AND createdAt >= :since")
    fun observeFailedSince(since: Long): Flow<Int>

    /**
     * 该账号在 [since] 之后成功发出的条数（小时配额用）。
     *
     * 配额必须从库里数，不能只靠内存计数——进程重启后内存清零，
     * 等于把「每小时上限」变成「每次重启上限」。
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE accountId = :accountId " +
            "AND direction = 'OUT' AND status = 'SENT' AND createdAt >= :since"
    )
    suspend fun countSentSince(accountId: String, since: Long): Int

    /** 指定来源在 [since] 之后成功发出的条数（主动发起的每小时 / 每天配额）。 */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE accountId = :accountId AND origin = :origin " +
            "AND status = 'SENT' AND createdAt >= :since"
    )
    suspend fun countByOriginSince(accountId: String, origin: String, since: Long): Int

    /** 每个会话最后一次指定来源消息的时间（同一会话两次主动发起的间隔）。 */
    @Query(
        "SELECT convId AS convId, MAX(createdAt) AS at FROM messages " +
            "WHERE accountId = :accountId AND origin = :origin GROUP BY convId"
    )
    suspend fun lastByOriginPerConversation(
        accountId: String,
        origin: String
    ): List<ConvOriginStamp>

    /** 每个会话里出现过的消息方向数量，2 表示双方都说过话。 */
    @Query(
        "SELECT convId AS convId, COUNT(DISTINCT direction) AS total FROM messages " +
            "WHERE accountId = :accountId AND kind = 'TEXT' GROUP BY convId"
    )
    suspend fun directionKindsPerConversation(accountId: String): List<ConvDirectionCount>
}
