package com.nextmovie.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

class JarBytesTest {

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun miniJar(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            z.putNextEntry(ZipEntry("com/github/catvod/spider/Demo.class"))
            // 必须用高熵内容：全 0 会被 deflate 压到几百字节，触发生产侧「插件体积异常」护栏
            val payload = ByteArray(4096)
            java.util.Random(42L).nextBytes(payload)
            z.write(payload)
            z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `plain zip with image extension style md5`() {
        val jar = miniJar()
        val digest = md5(jar)
        val result = JarBytes.materialize(jar, digest)
        assertTrue(JarBytes.isZip(result.jar))
        assertEquals(digest, result.matchedDigest)
        assertFalse(result.extracted)
    }

    @Test
    fun `jpeg with appended zip uses tail and accepts full body md5`() {
        // minimal JPEG SOI + EOI + jar
        val jar = miniJar()
        val jpegHead = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()
        )
        val body = jpegHead + jar
        val result = JarBytes.materialize(body, md5(body))
        assertTrue(JarBytes.isZip(result.jar))
        assertTrue(result.extracted)
        assertEquals(jar.size, result.jar.size)
    }

    @Test
    fun `md5 of inner jar also accepted`() {
        val jar = miniJar()
        val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val body = jpegHead + jar
        val result = JarBytes.materialize(body, md5(jar))
        assertTrue(result.extracted)
        assertEquals(md5(jar), result.matchedDigest)
    }

    @Test(expected = JarBytes.InvalidJarException::class)
    fun `rejects pure jpeg without zip`() {
        val pure = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()) +
            ByteArray(2000) { 0 }
        JarBytes.materialize(pure, "")
    }
}
