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
}
