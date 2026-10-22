package com.jiuguan.ailink.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubUpdateClientTest {

    // ---- 版本比较 ----

    @Test
    fun `版本比较 忽略 v 前缀`() {
        assertEquals(0, GithubUpdateClient.compareVersions("v1.1.0", "1.1.0"))
    }

    @Test
    fun `版本比较 主版本号优先`() {
        assertTrue(GithubUpdateClient.compareVersions("1.1.0", "1.0.18") > 0)
        assertTrue(GithubUpdateClient.compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(GithubUpdateClient.compareVersions("1.0.18", "1.1.0") < 0)
    }

    @Test
    fun `版本比较 段数不同时缺失段按零`() {
        assertEquals(0, GithubUpdateClient.compareVersions("1.1", "1.1.0"))
        assertTrue(GithubUpdateClient.compareVersions("1.1.1", "1.1") > 0)
    }

    @Test
    fun `版本比较 相等返回零`() {
        assertEquals(0, GithubUpdateClient.compareVersions("1.1.0", "1.1.0"))
    }

    // ---- Release JSON 解析 ----

    @Test
    fun `解析标准 release 响应`() {
        val json = """
        {
          "tag_name": "v1.2.0",
          "name": "v1.2.0 · 新增自更新",
          "body": "## 更新内容",
          "assets": [
            {"name": "sources.zip", "browser_download_url": "https://github.com/x/s.zip", "size": 123},
            {"name": "ailink-1.2.0-release.apk", "browser_download_url": "https://github.com/x/app.apk", "size": 75212174}
          ]
        }
        """.trimIndent()

        val info = GithubUpdateClient.parseRelease(json)

        assertNotNull(info)
        assertEquals("1.2.0", info!!.version)
        assertEquals("v1.2.0", info.tagName)
        assertEquals("https://github.com/x/app.apk", info.apkUrl)
        assertEquals(75212174L, info.apkSizeBytes)
    }

    @Test
    fun `解析时优先选择非 debug 的 apk 附件`() {
        val json = """
        {
          "tag_name": "v1.2.0",
          "assets": [
            {"name": "app-debug.apk", "browser_download_url": "https://github.com/x/debug.apk", "size": 1},
            {"name": "app-release.apk", "browser_download_url": "https://github.com/x/release.apk", "size": 2}
          ]
        }
        """.trimIndent()

        val info = GithubUpdateClient.parseRelease(json)

        assertEquals("https://github.com/x/release.apk", info!!.apkUrl)
    }

    @Test
    fun `没有 apk 附件时返回 null`() {
        val json = """
        {"tag_name": "v1.2.0", "assets": [{"name": "notes.txt", "browser_download_url": "https://x/n.txt", "size": 1}]}
        """.trimIndent()
        assertNull(GithubUpdateClient.parseRelease(json))
    }

    @Test
    fun `非法 json 或空体返回 null`() {
        assertNull(GithubUpdateClient.parseRelease(""))
        assertNull(GithubUpdateClient.parseRelease("not-json"))
        assertNull(GithubUpdateClient.parseRelease("""{"name": "no-tag"}"""))
    }
}
