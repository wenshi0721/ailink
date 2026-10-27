package com.jiuguan.ailink.data.repo

import com.jiuguan.ailink.core.SessionLog
import com.jiuguan.ailink.data.ai.ApiKeyCodec
import com.jiuguan.ailink.data.local.AccountDao
import com.jiuguan.ailink.data.local.AiProfileDao
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * AI 配置仓库。
 *
 * 两个不变量：
 * 1. 密钥只在内存中以明文存在，落库前统一走 [ApiKeyCodec]（Keystore AES-GCM）；
 * 2. 配置解析绝不静默回退到「第一条」——显式绑定 > 默认配置 > 无（内置模拟回复）。
 *    旧版本 `findById ?: first()` 会让停用/过期的旧接口在用户不知情时继续被调用。
 */
class AiRepository(
    private val dao: AiProfileDao,
    private val accountDao: AccountDao
) {

    fun observeProfiles(): Flow<List<AiProfileEntity>> = dao.observeAll()
        .map { list -> list.map(::decode) }
        .flowOn(Dispatchers.IO)

    // 解密走 Android Keystore，放到 IO 线程，避免在主线程做加密操作
    suspend fun list(): List<AiProfileEntity> = withContext(Dispatchers.IO) {
        dao.listAll().map(::decode)
    }

    suspend fun find(id: String): AiProfileEntity? = withContext(Dispatchers.IO) {
        dao.findById(id)?.let(::decode)
    }

    /**
     * 给运行中的账号解析可用配置。
     *
     * - 账号显式绑定了配置：必须存在、已启用、且不是待确认的旧配置，否则返回 null；
     * - 账号未绑定：使用全局默认配置（同样要求启用）；
     * - 都没有：返回 null，由调用方降级为内置模拟回复。
     */
    suspend fun resolve(profileId: String): AiProfileEntity? = withContext(Dispatchers.IO) {
        val all = dao.listAll()
        if (profileId.isNotBlank()) {
            val exact = all.firstOrNull { it.id == profileId } ?: return@withContext null
            if (!exact.usable) return@withContext null
            return@withContext decode(exact)
        }
        val fallback = all.firstOrNull { it.isDefault && it.usable } ?: return@withContext null
        decode(fallback)
    }

    /** 默认配置（界面展示用，要求启用）。 */
    suspend fun defaultProfile(): AiProfileEntity? =
        dao.listAll().firstOrNull { it.isDefault && it.usable }?.let(::decode)

    /**
     * 新建账号时的推荐配置：显式默认 > 第一个可用的远程配置 > 内置模拟回复。
     * 避免「用户已配好 DeepSeek，新账号却默认绑到内置模拟回复」。
     */
    suspend fun suggested(): AiProfileEntity? {
        val all = list()
        return all.firstOrNull { it.isDefault && it.usable }
            ?: all.firstOrNull { it.usable && !it.isLocalOnly }
            ?: all.firstOrNull { it.isLocalOnly && it.enabled }
            ?: all.firstOrNull { it.enabled && !it.needsReview }
    }

    suspend fun save(profile: AiProfileEntity) = withContext(Dispatchers.IO) {
        dao.upsert(encode(profile))
    }

    /** 删除配置时解除账号上的引用，避免留下悬空 id。 */
    suspend fun delete(id: String) {
        dao.delete(id)
        accountDao.clearAiProfile(id)
    }

    suspend fun count(): Int = dao.count()

    /** 旧版本迁移而来、尚未确认的配置数量。 */
    suspend fun legacyReviewCount(): Int = dao.listAll().count { it.needsReview }

    /** 设为默认，同时清除待确认标记（这是一次显式的用户确认）。 */
    suspend fun setDefault(id: String) {
        dao.clearDefault()
        dao.markDefault(id)
        SessionLog.add("ai", "默认 AI 配置已切换为 $id")
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        // 默认配置被停用后不应继续挂着「默认」身份，否则界面会显示「默认但不可用」
        if (!enabled) dao.clearDefaultOf(id)
        SessionLog.add("ai", "AI 配置 $id ${if (enabled) "已启用" else "已停用"}")
    }

    /** 取消默认身份，之后账号未绑定配置时回退到内置模拟回复。 */
    suspend fun clearDefault(id: String) {
        dao.clearDefaultOf(id)
    }

    /** 记录测试结论；仅对已保存的配置生效。 */
    suspend fun recordTest(id: String, ok: Boolean, message: String) {
        if (id.isBlank()) return
        dao.recordTest(id, System.currentTimeMillis(), ok, message.take(300))
    }

    /** 复制配置：新 id、名称加「副本」，不继承默认身份，避免出现两个默认。 */
    suspend fun duplicate(source: AiProfileEntity): AiProfileEntity = withContext(Dispatchers.IO) {
        val copy = source.copy(
            id = Ids.newId(),
            name = nextCopyName(source.name),
            isDefault = false,
            needsReview = false,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(encode(copy))
        copy
    }

    private fun nextCopyName(name: String): String {
        val base = name.ifBlank { "AI 配置" }.removeSuffix(" 副本")
        return "$base 副本"
    }

    private fun decode(profile: AiProfileEntity): AiProfileEntity {
        if (!ApiKeyCodec.isEncrypted(profile.apiKey)) return profile
        val plain = ApiKeyCodec.decrypt(profile.apiKey)
        if (plain.isEmpty()) {
            SessionLog.add("ai", "配置「${profile.name}」的密钥无法解密（系统密钥可能已重置），请重新填写")
        }
        return profile.copy(apiKey = plain)
    }

    private fun encode(profile: AiProfileEntity): AiProfileEntity =
        profile.copy(apiKey = ApiKeyCodec.encrypt(profile.apiKey))
}
