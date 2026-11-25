package com.nextmovie.core.spider.native

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GuardStringScannerTest {
    @Test
    fun extractsHttpsFromZip() {
        val f = File.createTempFile("guard", ".jar")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("config.txt"))
            z.write("hello\u0000https://api.example.com/v3/\u0000ABCDEF0123456789ABCDEF01".toByteArray())
            z.closeEntry()
        }
        val findings = GuardStringScanner.scan(f)
        assertTrue(findings.httpsUrls.any { it.contains("api.example.com") })
        f.delete()
    }
}
