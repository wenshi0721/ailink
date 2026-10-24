package com.jiuguan.ailink.engine.container

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 反检测开关的读写回归保护。
 *
 * 这段逻辑会直接改写容器里的 NapCat 配置，写坏了等于把 QQ 运行时弄挂，
 * 所以固定住三件事：只改指定字段、其余键原样保留、首次改写留备份。
 */
class NapcatConfigTest {

    private lateinit var runtimeDir: File
    private lateinit var configDir: File

    private val sample = """
        {"fileLog":false,"consoleLog":true,"fileLogLevel":"debug","consoleLogLevel":"info",
         "packetBackend":"auto","packetServer":"","o3HookMode":1,
         "bypass":{"hook":false,"window":false,"module":false,"process":false,"container":false,"js":false},
         "autoTimeSync":true}
    """.trimIndent()

    @Before
    fun setUp() {
        runtimeDir = Files.createTempDirectory("ailink-rt").toFile()
        configDir = File(
            runtimeDir,
            "rootfs/opt/QQ/resources/app/app_launcher/napcat/config"
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        runtimeDir.deleteRecursively()
    }

    private fun writeConfig(name: String = "napcat_320994831.json") =
        File(configDir, name).apply { writeText(sample) }

    @Test
    fun `开启后 o3HookMode 为 0 且 bypass 六项全开`() {
        writeConfig()

        val state = NapcatConfig.setHardened(runtimeDir, "320994831", enabled = true).getOrThrow()

        assertEquals(0, state.o3HookMode)
        assertEquals(6, state.bypassOn)
        assertTrue(state.hardened)

        val text = File(configDir, "napcat_320994831.json").readText()
        assertTrue(text.contains("\"o3HookMode\": 0"))
        assertTrue(text.contains("\"hook\": true"))
    }

    @Test
    fun `还原后回到 o3HookMode 1 且 bypass 全关`() {
        writeConfig()
        NapcatConfig.setHardened(runtimeDir, "320994831", enabled = true).getOrThrow()

        val state = NapcatConfig.setHardened(runtimeDir, "320994831", enabled = false).getOrThrow()

        assertEquals(1, state.o3HookMode)
        assertEquals(0, state.bypassOn)
        assertFalse(state.hardened)
    }

    @Test
    fun `改写不会丢掉其它配置项`() {
        writeConfig()

        NapcatConfig.setHardened(runtimeDir, "320994831", enabled = true).getOrThrow()

        val text = File(configDir, "napcat_320994831.json").readText()
        assertTrue(text.contains("\"packetBackend\": \"auto\""))
        assertTrue(text.contains("\"autoTimeSync\": true"))
        assertTrue(text.contains("\"consoleLog\": true"))
    }

    @Test
    fun `首次改写会留下备份`() {
        writeConfig()

        NapcatConfig.setHardened(runtimeDir, "320994831", enabled = true).getOrThrow()

        val backup = File(configDir, "napcat_320994831.json.bak")
        assertTrue(backup.isFile)
        assertTrue(backup.readText().contains("\"o3HookMode\":1"))
    }

    @Test
    fun `没有按号配置时退回全局配置`() {
        writeConfig("napcat.json")

        val state = NapcatConfig.setHardened(runtimeDir, "320994831", enabled = true).getOrThrow()

        assertTrue(state.file.endsWith("napcat.json"))
        assertEquals(0, state.o3HookMode)
    }

    @Test
    fun `配置文件不存在时返回 null 且写入失败`() {
        assertNull(NapcatConfig.read(runtimeDir, "320994831"))

        val result = NapcatConfig.setHardened(runtimeDir, "320994831", enabled = true)
        assertTrue(result.isFailure)
    }

    @Test
    fun `损坏的 JSON 不会被覆盖`() {
        val file = File(configDir, "napcat_320994831.json").apply { writeText("{not json") }

        val result = NapcatConfig.setHardened(runtimeDir, "320994831", enabled = true)

        assertTrue(result.isFailure)
        assertEquals("{not json", file.readText())
    }
}
