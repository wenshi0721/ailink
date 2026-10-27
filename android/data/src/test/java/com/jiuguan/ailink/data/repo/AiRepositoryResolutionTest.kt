package com.jiuguan.ailink.data.repo

import com.jiuguan.ailink.data.local.AccountDao
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileDao
import com.jiuguan.ailink.data.local.AiProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置解析的回归保护。
 *
 * 旧实现 `findById ?: first()` 有两个隐患：
 * 1. 账号绑定的配置被停用后，会悄悄换成另一份（可能是过期的旧接口）；
 * 2. 配置被删除后，会悄悄换成任意一条，用户完全不知情。
 */
class AiRepositoryResolutionTest {

    private fun profile(
        id: String,
        enabled: Boolean = true,
        isDefault: Boolean = false,
        needsReview: Boolean = false,
        baseUrl: String = "https://api.example.com/v1",
        createdAt: Long = 1L
    ) = AiProfileEntity(
        id = id,
        name = id,
        baseUrl = baseUrl,
        model = "test-model",
        enabled = enabled,
        isDefault = isDefault,
        needsReview = needsReview,
        createdAt = createdAt
    )

    private fun repo(vararg profiles: AiProfileEntity): Pair<AiRepository, FakeAiProfileDao> {
        val dao = FakeAiProfileDao(profiles.toList())
        return AiRepository(dao, FakeAccountDao()) to dao
    }

    @Test
    fun `显式绑定且启用的配置会被使用`() = runBlocking {
        val (repository, _) = repo(profile("a"), profile("b"))
        assertEquals("b", repository.resolve("b")?.id)
    }

    @Test
    fun `绑定的配置被停用时不回退到其他配置`() = runBlocking {
        val (repository, _) = repo(
            profile("bound", enabled = false),
            profile("other", isDefault = true)
        )
        assertNull(repository.resolve("bound"))
    }

    @Test
    fun `绑定的配置被删除时不回退到第一条`() = runBlocking {
        val (repository, _) = repo(profile("a", isDefault = true), profile("b"))
        assertNull(repository.resolve("missing"))
    }

    @Test
    fun `未绑定账号使用启用的默认配置`() = runBlocking {
        val (repository, _) = repo(profile("a"), profile("b", isDefault = true))
        assertEquals("b", repository.resolve("")?.id)
    }

    @Test
    fun `默认配置被停用或待确认时不生效`() = runBlocking {
        val (disabled, _) = repo(profile("a", enabled = false, isDefault = true))
        assertNull(disabled.resolve(""))

        val (legacy, _) = repo(profile("a", isDefault = true, needsReview = true))
        assertNull(legacy.resolve(""))
    }

    @Test
    fun `停用默认配置会同时取消默认身份`() = runBlocking {
        val (repository, dao) = repo(profile("a", isDefault = true))
        repository.setEnabled("a", false)
        assertFalse(dao.snapshot().single().isDefault)
    }

    @Test
    fun `旧配置待确认时不可用`() = runBlocking {
        val (repository, _) = repo(profile("a", needsReview = true))
        assertNull(repository.resolve("a"))
        assertTrue(repository.legacyReviewCount() == 1)
    }
}

private class FakeAiProfileDao(initial: List<AiProfileEntity>) : AiProfileDao {

    private val state = MutableStateFlow(initial)

    private fun sorted(list: List<AiProfileEntity>) =
        list.sortedWith(compareByDescending<AiProfileEntity> { it.isDefault }.thenBy { it.createdAt })

    fun snapshot(): List<AiProfileEntity> = sorted(state.value)

    override fun observeAll(): Flow<List<AiProfileEntity>> = state.map(::sorted)

    override suspend fun listAll(): List<AiProfileEntity> = snapshot()

    override suspend fun findById(id: String): AiProfileEntity? =
        state.value.firstOrNull { it.id == id }

    override suspend fun first(): AiProfileEntity? = snapshot().firstOrNull()

    override suspend fun upsert(profile: AiProfileEntity) {
        state.value = state.value.filterNot { it.id == profile.id } + profile
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun count(): Int = state.value.size

    override suspend fun clearDefault() {
        state.value = state.value.map { it.copy(isDefault = false) }
    }

    override suspend fun clearDefaultOf(id: String) {
        state.value = state.value.map { if (it.id == id) it.copy(isDefault = false) else it }
    }

    override suspend fun markDefault(id: String) {
        state.value = state.value.map {
            when {
                it.id == id -> it.copy(isDefault = true, enabled = true, needsReview = false)
                else -> it.copy(isDefault = false)
            }
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        state.value = state.value.map {
            if (it.id == id) {
                it.copy(
                    enabled = enabled,
                    needsReview = if (enabled) false else it.needsReview
                )
            } else {
                it
            }
        }
    }

    override suspend fun recordTest(id: String, at: Long, ok: Boolean, message: String) {
        state.value = state.value.map {
            if (it.id == id) {
                it.copy(lastTestAt = at, lastTestOk = ok, lastTestMessage = message)
            } else {
                it
            }
        }
    }
}

private class FakeAccountDao : AccountDao {
    private val state = MutableStateFlow<List<AccountEntity>>(emptyList())

    override fun observeAll(): Flow<List<AccountEntity>> = state

    override suspend fun findById(id: String): AccountEntity? = state.value.firstOrNull { it.id == id }

    override suspend fun listEnabled(): List<AccountEntity> = state.value.filter { it.enabled }

    override suspend fun count(): Int = state.value.size

    override suspend fun upsert(account: AccountEntity) {
        state.value = state.value.filterNot { it.id == account.id } + account
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun markOnline(id: String, ts: Long) {
        state.value = state.value.map { if (it.id == id) it.copy(lastOnlineAt = ts) else it }
    }

    override suspend fun clearAiProfile(profileId: String) {
        state.value = state.value.map {
            if (it.aiProfileId == profileId) it.copy(aiProfileId = "") else it
        }
    }
}
