package com.nextmovie.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class ConfigDecoderTest {

    @Test
    fun `strips utf8 bom`() {
        val input = "\uFEFF{\"sites\":[]}"
        assertEquals("{\"sites\":[]}", ConfigDecoder.decodeText(input))
    }

    @Test
    fun `decodes base64 wrapper`() {
        val json = "{\"sites\":[{\"key\":\"a\",\"name\":\"A\",\"api\":\"https://x\"}]}"
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val input = "abcd1234**" + encoded
        assertEquals(json, ConfigDecoder.decodeText(input))
    }

    @Test
    fun `decodes gzip body`() {
        val json = "{\"sites\":[]}"
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toByteArray()) }
        assertEquals(json, ConfigDecoder.decode(out.toByteArray()))
    }

    @Test
    fun `keeps plain json untouched`() {
        val json = "{\"sites\":[]}"
        assertEquals(json, ConfigDecoder.decodeText(json))
    }

    @Test
    fun `rejects odd length hex`() {
        try {
            ConfigDecoder.hexToBytes("abc")
            assertTrue(false)
        } catch (e: ConfigDecoder.DecodeException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
