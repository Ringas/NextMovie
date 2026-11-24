package com.nextmovie.core.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterUrlRulesTest {

    @Test
    fun `douban list upgrades to medium and normalises host`() {
        val raw = "https://img3.doubanio.com/view/photo/s_ratio_poster/public/p123.jpg?x=1"
        val out = PosterUrlRules.upgrade(raw, detail = false)
        assertEquals("https://img1.doubanio.com/view/photo/m_ratio_poster/public/p123.jpg", out)
    }

    @Test
    fun `douban detail upgrades to large`() {
        val raw = "http://img9.doubanio.com/view/photo/s_ratio_poster/public/p123.jpg"
        val out = PosterUrlRules.upgrade(raw, detail = true)
        assertEquals("https://img1.doubanio.com/view/photo/l_ratio_poster/public/p123.jpg", out)
    }

    @Test
    fun `douban spic becomes lpic on detail`() {
        val raw = "https://img1.doubanio.com/view/photo/spic/public/p9.jpg"
        assertEquals(
            "https://img1.doubanio.com/view/photo/lpic/public/p9.jpg",
            PosterUrlRules.upgrade(raw, detail = true)
        )
    }

    @Test
    fun `tmdb sizes are bumped`() {
        val raw = "https://image.tmdb.org/t/p/w342/abc.jpg"
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            PosterUrlRules.upgrade(raw, detail = false)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/abc.jpg",
            PosterUrlRules.upgrade(raw, detail = true)
        )
    }

    @Test
    fun `bilibili suffix is stripped`() {
        val raw = "https://i0.hdslb.com/bfs/bangumi/image/x.png@480w_270h_1c.webp"
        assertEquals(
            "https://i0.hdslb.com/bfs/bangumi/image/x.png",
            PosterUrlRules.upgrade(raw, detail = false)
        )
    }

    @Test
    fun `protocol relative and empty urls`() {
        assertEquals("", PosterUrlRules.upgrade("", detail = false))
        assertEquals(
            "https://img1.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg",
            PosterUrlRules.upgrade("//img3.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg", detail = false)
        )
    }

    @Test
    fun `referer only for protected hosts`() {
        assertEquals("https://movie.douban.com/", PosterUrlRules.refererFor("https://img1.doubanio.com/a.jpg"))
        assertEquals("https://www.bilibili.com/", PosterUrlRules.refererFor("https://i0.hdslb.com/a.png"))
        assertEquals(null, PosterUrlRules.refererFor("https://cdn.example.com/a.jpg"))
    }

    @Test
    fun `decent source art is detected`() {
        assertTrue(PosterUrlRules.looksDecent("https://img1.doubanio.com/view/photo/l_ratio_poster/public/p1.jpg"))
        assertTrue(PosterUrlRules.looksDecent("https://image.tmdb.org/t/p/w500/x.jpg"))
        assertTrue(PosterUrlRules.looksDecent("https://i0.hdslb.com/bfs/bangumi/image/x.png"))
        assertTrue(!PosterUrlRules.looksDecent("https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"))
        assertTrue(!PosterUrlRules.looksDecent("https://cdn.example.com/upload/1.jpg"))
    }

    @Test
    fun `upgrade is idempotent`() {
        val once = PosterUrlRules.upgrade("https://img3.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg", true)
        assertEquals(once, PosterUrlRules.upgrade(once, true))
        assertTrue(PosterUrlRules.looksSmall("https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"))
    }
}
