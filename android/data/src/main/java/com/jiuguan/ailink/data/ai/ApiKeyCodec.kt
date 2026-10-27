package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.core.SecretStore

/**
 * API Key 的落库编解码。
 *
 * 复用项目已有的 Keystore AES-GCM（[SecretStore]），不引入新依赖：
 * - 写入：`enc1:` + Base64(iv + 密文)；
 * - 读取：识别前缀后解密；无前缀的历史数据按明文处理，
 *   因此升级不需要改表，也不会让旧密钥突然失效；
 * - 解密失败（系统密钥被重置 / 备份还原）：返回空串并让界面提示重填，
 *   绝不把密文当密钥发出去。
 */
object ApiKeyCodec {

    private const val PREFIX = "enc1:"

    fun encrypt(plain: String): String {
        val value = plain.trim()
        if (value.isEmpty()) return ""
        val encrypted = SecretStore.encrypt(value) ?: return value
        return PREFIX + encrypted
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return SecretStore.decrypt(stored.removePrefix(PREFIX)).orEmpty()
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)
}
