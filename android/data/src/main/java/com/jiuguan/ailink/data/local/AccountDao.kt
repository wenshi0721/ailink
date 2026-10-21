package com.jiuguan.ailink.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun listEnabled(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE accounts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE accounts SET lastOnlineAt = :ts WHERE id = :id")
    suspend fun markOnline(id: String, ts: Long)

    @Query("UPDATE accounts SET aiProfileId = '' WHERE aiProfileId = :profileId")
    suspend fun clearAiProfile(profileId: String)
}
