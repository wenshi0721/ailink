package com.jiuguan.ailink.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY lastAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE accountId = :accountId ORDER BY lastAt DESC")
    fun observeByAccount(accountId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun find(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE accountId = :accountId")
    suspend fun findByAccount(accountId: String): List<ConversationEntity>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unread = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE conversations SET autoReply = :enabled WHERE id = :id")
    suspend fun setAutoReply(id: String, enabled: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)

    @Query("SELECT COUNT(*) FROM conversations")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
