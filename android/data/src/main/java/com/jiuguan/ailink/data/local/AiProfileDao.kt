package com.jiuguan.ailink.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AiProfileDao {

    @Query("SELECT * FROM ai_profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AiProfileEntity>>

    @Query("SELECT * FROM ai_profiles ORDER BY createdAt ASC")
    suspend fun listAll(): List<AiProfileEntity>

    @Query("SELECT * FROM ai_profiles WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AiProfileEntity?

    @Query("SELECT * FROM ai_profiles ORDER BY createdAt ASC LIMIT 1")
    suspend fun first(): AiProfileEntity?

    @Upsert
    suspend fun upsert(profile: AiProfileEntity)

    @Query("DELETE FROM ai_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM ai_profiles")
    suspend fun count(): Int
}
