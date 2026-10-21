package com.jiuguan.ailink.data.repo

import com.jiuguan.ailink.data.local.AccountDao
import com.jiuguan.ailink.data.local.AccountEntity
import kotlinx.coroutines.flow.Flow

class AccountRepository(private val accountDao: AccountDao) {

    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    suspend fun find(id: String): AccountEntity? = accountDao.findById(id)

    suspend fun listEnabled(): List<AccountEntity> = accountDao.listEnabled()

    suspend fun save(account: AccountEntity) = accountDao.upsert(account)

    suspend fun delete(id: String) = accountDao.delete(id)

    suspend fun setEnabled(id: String, enabled: Boolean) = accountDao.setEnabled(id, enabled)

    suspend fun markOnline(id: String, ts: Long = System.currentTimeMillis()) =
        accountDao.markOnline(id, ts)

    suspend fun count(): Int = accountDao.count()
}
