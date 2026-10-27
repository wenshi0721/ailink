package com.jiuguan.ailink.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AiProfileDao {

    @Query("SELECT * FROM ai_profiles ORDER BY isDefault DESC, createdAt ASC")
    fun observeAll(): Flow<List<AiProfileEntity>>

    @Query("SELECT * FROM ai_profiles ORDER BY isDefault DESC, createdAt ASC")
    suspend fun listAll(): List<AiProfileEntity>

    @Query("SELECT * FROM ai_profiles WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AiProfileEntity?

    @Query("SELECT * FROM ai_profiles ORDER BY isDefault DESC, createdAt ASC LIMIT 1")
    suspend fun first(): AiProfileEntity?

    @Upsert
    suspend fun upsert(profile: AiProfileEntity)

    @Query("DELETE FROM ai_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM ai_profiles")
    suspend fun count(): Int

    @Query("UPDATE ai_profiles SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE ai_profiles SET isDefault = 0 WHERE id = :id")
    suspend fun clearDefaultOf(id: String)

    /** 设为默认即视为用户确认：同时启用并清除待确认标记。 */
    @Query("UPDATE ai_profiles SET isDefault = 1, enabled = 1, needsReview = 0 WHERE id = :id")
    suspend fun markDefault(id: String)

    /** 启用即视为用户确认；停用保留待确认标记，避免误以为「已审核」。 */
    @Query(
        "UPDATE ai_profiles SET enabled = :enabled, " +
            "needsReview = CASE WHEN :enabled = 1 THEN 0 ELSE needsReview END WHERE id = :id"
    )
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query(
        "UPDATE ai_profiles SET lastTestAt = :at, lastTestOk = :ok, lastTestMessage = :message " +
            "WHERE id = :id"
    )
    suspend fun recordTest(id: String, at: Long, ok: Boolean, message: String)
}
