package com.nextmovie.core.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实抓取样本回归三种「非常规」配置包装：
 *  - 肥猫：`2423` 十六进制 + AES-CBC（key `$#...#$`，尾部 13 字节 IV）
 *  - 饭太硬：100×100 JPEG 尾部追加 `xxxxxxxx**` + Base64，解出来还是 JSONC
 * 样本来自公开配置入口，体积小，仅用于格式兼容测试，不含任何内容逻辑。
 */
class ConfigDecoderRealSampleTest {

    private fun sample(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/config/$name")) { "missing $name" }.use { it.readBytes() }

    @Test
    fun `2423 配置能解出 spider 与站点`() {
        val text = ConfigDecoder.decode(sample("feimao_2423.bin"))
        assertTrue("应解出 JSON 对象", text.trimStart().startsWith("{"))
        val root = JSONObject(ConfigDecoder.sanitizeJson(text))
        assertTrue(root.has("spider"))
        assertTrue(root.getJSONArray("sites").length() > 20)
    }

    @Test
    fun `JPEG 尾部配置能解出站点`() {
        val text = ConfigDecoder.decode(sample("fanty_jpeg.bin"))
        assertTrue("JPEG 尾部应还原成 JSON", text.trimStart().startsWith("{"))
        val root = JSONObject(ConfigDecoder.sanitizeJson(text))
        assertTrue(root.getJSONArray("sites").length() > 30)
        assertTrue(root.optString("spider").startsWith("http"))
    }

    @Test
    fun `JSONC 清洗保留字符串里的双斜杠`() {
        val raw = """
            {
              // 行注释
              "a": "http://example.com/x", /* 块注释 */
              "b": [1, 2,],
            }
        """.trimIndent()
        val cleaned = ConfigDecoder.sanitizeJson(raw)
        val obj = JSONObject(cleaned)
        assertEquals("http://example.com/x", obj.getString("a"))
        assertEquals(2, obj.getJSONArray("b").length())
        assertFalse(cleaned.contains("行注释"))
    }
}
