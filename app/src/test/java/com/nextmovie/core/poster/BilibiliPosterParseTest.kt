package com.nextmovie.core.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliPosterParseTest {

    private val sample = """
    {"code":0,"message":"OK","data":{"result":[
      {"type":"media_ft","title":"<em class=\"keyword\">流浪地球2</em>","org_title":"流浪地球2",
       "pubtime":1674316800,"media_score":{"score":9.8},"season_type_name":"电影",
       "areas":"中国大陆","styles":"科幻/冒险/灾难",
       "cover":"https://i0.hdslb.com/bfs/bangumi/image/aaa.png",
       "eps":[{"cover":"http://i0.hdslb.com/bfs/archive/bbb.png"}],
       "desc":"太阳即将毁灭，人类在地球表面建造推进器。……为了拯救地球，他们再次挺身而出。"},
      {"type":"media_ft","title":"<em class=\"keyword\">流浪地球</em>","org_title":"流浪地球",
       "pubtime":1546272000,"cover":"//i0.hdslb.com/bfs/bangumi/image/ccc.png","desc":""}
    ]}}
    """.trimIndent()

    @Test
    fun `parses title cover backdrop overview and year`() {
        val hits = BilibiliPoster.parse(sample)
        assertEquals(2, hits.size)
        val first = hits[0]
        assertEquals("流浪地球2", first.title)
        assertEquals("https://i0.hdslb.com/bfs/bangumi/image/aaa.png", first.poster)
        assertEquals("http://i0.hdslb.com/bfs/archive/bbb.png", first.backdrop)
        assertEquals("2023", first.year)
        assertTrue(first.overview.startsWith("太阳即将毁灭"))
        assertTrue(!first.overview.endsWith("。"))
    }

    @Test
    fun `protocol relative cover becomes https`() {
        val hits = BilibiliPoster.parse(sample)
        assertEquals("https://i0.hdslb.com/bfs/bangumi/image/ccc.png", hits[1].poster)
    }

    @Test
    fun `exact match is accepted`() {
        val hits = BilibiliPoster.parse(sample)
        val hit = BilibiliPoster.pick(hits, "流浪地球2", "2023")
        assertNotNull(hit)
        assertEquals("流浪地球2", hit!!.title)
    }

    @Test
    fun `sequel conflict is rejected`() {
        val hits = BilibiliPoster.parse(sample)
        val onlySequel = hits.filter { it.title == "流浪地球2" }
        assertNull(BilibiliPoster.pick(onlySequel, "流浪地球", "2023"))
    }

    @Test
    fun `unknown title is rejected`() {
        val hits = BilibiliPoster.parse(sample)
        assertNull(BilibiliPoster.pick(hits, "三体", "2027"))
    }

    @Test
    fun `broken payload returns empty`() {
        assertTrue(BilibiliPoster.parse("not json").isEmpty())
        assertTrue(BilibiliPoster.parse("""{"code":-412,"message":"risk"}""").isEmpty())
    }
}
