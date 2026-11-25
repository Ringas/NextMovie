package com.nextmovie.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsAsciiTest {

    @Test
    fun `chinese host becomes punycode`() {
        val out = Urls.ascii("http://www.饭太硬.cc/tv")
        assertTrue(out.startsWith("http://www."))
        assertTrue(out.contains("xn--"))
        assertTrue(out.endsWith("/tv"))
        assertFalse(out.any { it.code > 127 })
    }

    @Test
    fun `ascii url unchanged`() {
        assertEquals(
            "https://szyyds.cn/tv/x.json",
            Urls.ascii("https://szyyds.cn/tv/x.json")
        )
    }

    @Test
    fun `feimao style domain`() {
        val out = Urls.ascii("http://肥猫.net/")
        assertTrue(out.startsWith("http://xn--"))
        assertTrue(out.endsWith(".net/"))
    }
}
