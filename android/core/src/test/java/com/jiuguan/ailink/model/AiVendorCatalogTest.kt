package com.jiuguan.ailink.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiVendorCatalogTest {

    @Test
    fun `厂商目录非空且字段完整`() {
        assertTrue(AiVendorCatalog.vendors.size >= 6)
        AiVendorCatalog.vendors.forEach { vendor ->
            assertTrue("id 不能为空", vendor.id.isNotBlank())
            assertTrue("name 不能为空", vendor.name.isNotBlank())
            assertTrue("baseUrl 不能为空", vendor.baseUrl.isNotBlank())
            assertTrue(
                "${vendor.id} 至少要给一个常用模型或填写说明",
                vendor.models.isNotEmpty() || vendor.modelHint.isNotBlank()
            )
        }
    }

    @Test
    fun `厂商 id 唯一`() {
        val ids = AiVendorCatalog.vendors.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `https 与本地地址区分`() {
        AiVendorCatalog.vendors.forEach { vendor ->
            if (vendor.local) {
                assertTrue("${vendor.id} 本地模板应指向回环地址", vendor.baseUrl.startsWith("http://127.0.0.1"))
            } else {
                assertTrue("${vendor.id} 应使用 https", vendor.baseUrl.startsWith("https://"))
            }
        }
    }

    @Test
    fun `byId 命中与未命中`() {
        assertNotNull(AiVendorCatalog.byId("deepseek"))
        assertNull(AiVendorCatalog.byId("not-exist"))
        assertNull(AiVendorCatalog.byId(""))
    }

    @Test
    fun `matchByBaseUrl 忽略末尾斜杠与大小写`() {
        val vendor = AiVendorCatalog.matchByBaseUrl("  https://API.DeepSeek.com/v1/  ")
        assertEquals("deepseek", vendor?.id)
    }

    @Test
    fun `matchByBaseUrl 兼容别名与完整路径`() {
        assertEquals("deepseek", AiVendorCatalog.matchByBaseUrl("https://api.deepseek.com")?.id)
        assertEquals(
            "deepseek",
            AiVendorCatalog.matchByBaseUrl("https://api.deepseek.com/chat/completions")?.id
        )
        assertEquals(
            "zhipu",
            AiVendorCatalog.matchByBaseUrl("https://open.bigmodel.cn/api/paas/v4")?.id
        )
        assertEquals(
            "zhipu",
            AiVendorCatalog.matchByBaseUrl("https://open.bigmodel.cn/api/paas/v4/chat/completions")?.id
        )
        assertEquals("ark", AiVendorCatalog.matchByBaseUrl("https://ark.cn-beijing.volces.com/api/v3")?.id)
    }

    @Test
    fun `matchByBaseUrl 未知地址与空值返回 null`() {
        assertNull(AiVendorCatalog.matchByBaseUrl(""))
        assertNull(AiVendorCatalog.matchByBaseUrl("   "))
        assertNull(AiVendorCatalog.matchByBaseUrl("https://my-own-gateway.example.com/v1"))
    }

    @Test
    fun `默认模型取列表首项`() {
        val deepseek = AiVendorCatalog.byId("deepseek")
        assertEquals("deepseek-flash", deepseek?.defaultModelId)
        val ark = AiVendorCatalog.byId("ark")
        assertEquals("", ark?.defaultModelId)
    }
}
