package com.jiuguan.ailink.data.repo

import com.jiuguan.ailink.data.local.AccountDao
import com.jiuguan.ailink.data.local.AiProfileDao
import com.jiuguan.ailink.data.local.AiProfileEntity
import kotlinx.coroutines.flow.Flow

class AiRepository(
    private val dao: AiProfileDao,
    private val accountDao: AccountDao
) {

    fun observeProfiles(): Flow<List<AiProfileEntity>> = dao.observeAll()

    suspend fun list(): List<AiProfileEntity> = dao.listAll()

    suspend fun find(id: String): AiProfileEntity? = dao.findById(id)

    suspend fun resolve(profileId: String): AiProfileEntity? =
        dao.findById(profileId) ?: dao.first()

    suspend fun save(profile: AiProfileEntity) = dao.upsert(profile)

    /** 删除配置时解除账号上的引用，避免留下悬空 id。 */
    suspend fun delete(id: String) {
        dao.delete(id)
        accountDao.clearAiProfile(id)
    }

    suspend fun count(): Int = dao.count()
}
