package com.nextmovie.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePageResolverTest {

    @Test
    fun `识别分享页与直链`() {
        assertTrue(SharePageResolver.looksLikePage("https://x.com/share/abc"))
        assertFalse(SharePageResolver.looksLikePage("https://x.com/a/index.m3u8"))
        assertFalse(SharePageResolver.looksLikePage("https://x.com/a/b.mp4"))
    }

    @Test
    fun `fansheng 页里的相对 url 补全成绝对地址`() {
        val html = """
            <script>
              const vid = "abc";
              const url = "/20260630/46239_30f9addb/index.m3u8?sign=f8d4";
            </script>
        """.trimIndent()
        assertEquals(
            "https://svip.feifei-play.com/20260630/46239_30f9addb/index.m3u8?sign=f8d4",
            SharePageResolver.extract(html, "https://svip.feifei-play.com/share/abc")
        )
    }

    @Test
    fun `JSON 里的绝对地址可直接使用，斜杠转义会被还原`() {
        val html = """var player={"url":"https:\/\/cdn.example.com\/x\/index.m3u8"}"""
        assertEquals(
            "https://cdn.example.com/x/index.m3u8",
            SharePageResolver.extract(html, "https://site.example.com/share/1")
        )
    }

    @Test
    fun `纯文本页面解析不出地址时返回 null`() {
        assertNull(SharePageResolver.extract("<html><body>403 Forbidden</body></html>", "https://x.com/share/1"))
    }
}
