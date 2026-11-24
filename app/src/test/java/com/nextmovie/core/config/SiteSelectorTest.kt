package com.nextmovie.core.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteSelectorTest {

    private fun parse(json: String): List<Site> {
        val root = JSONObject(json)
        return ConfigBundle.fromJson(root, "https://example.com/tv/x.json", Site.spiderJar(root)).sites
    }

    private val aggregate = """
        {
          "spider": "https://example.com/tv/x.jpg;md5;4ce29ce27eeff6a73a230dd92d98ba0c",
          "sites": [
            {"key": "DouBan", "name": "免费分享", "type": 3, "api": "csp_DouDouGuard"},
            {"key": "push", "name": "推送", "type": 3, "api": "csp_AweSomeGuard"},
            {"key": "js", "name": "独播库", "type": 3, "api": "https://files.example.com/a.js"},
            {"key": "dytt", "name": "电影天堂", "type": 1, "api": "http://caiji.example.com/api.php/provide/vod"},
            {"key": "lz", "name": "量子", "type": 1, "api": "https://cj.example.com/api.php/provide/vod/"}
          ]
        }
    """.trimIndent()

    @Test
    fun `global spider jar is inherited and cleaned`() {
        val sites = parse(aggregate)
        val guard = sites.first { it.key == "DouBan" }
        assertEquals("https://example.com/tv/x.jpg", guard.jar)
        assertEquals("4ce29ce27eeff6a73a230dd92d98ba0c", guard.jarHash)
        assertTrue(guard.isPlayable)

        // 站点自己写了 jar 时不被全局值覆盖
        val own = parse(
            """{"spider":"https://a.com/g.jar;md5;x","sites":[{"key":"m","name":"商店","type":3,"api":"csp_Market","jar":"https://b.com/own.jar"}]}"""
        ).first()
        assertEquals("https://b.com/own.jar", own.jar)
    }

    @Test
    fun `js is available while jar-less guards and node stay blocked`() {
        val sites = parse(
            """{"sites":[
                {"key":"js","name":"JS 源","type":3,"api":"https://a.com/x.js"},
                {"key":"guard","name":"守卫","type":3,"api":"csp_XxxGuard"},
                {"key":"node","name":"Node 源","type":3,"api":"node:demo"}
            ]}"""
        )
        assertEquals(3, sites.size)
        // v0.5.0 起内置 QuickJS，JS 源可加载；缺 jar 的加壳源与 node 仍拦在入口
        assertNull(sites[0].unavailableReason)
        assertTrue(sites[0].isPlayable)
        assertTrue(sites[1].unavailableReason!!.contains("spider.jar"))
        assertTrue(sites[2].unavailableReason!!.contains("Node"))
        assertFalse(sites[1].isPlayable)
        assertFalse(sites[2].isPlayable)
    }

    @Test
    fun `defaults to first cms instead of guard shell`() {
        val sites = parse(aggregate)
        val picked = SiteSelector.pick(sites, savedKey = "", manual = false)
        assertEquals("dytt", picked?.key)
    }

    @Test
    fun `manual choice is respected when still playable`() {
        val sites = parse(aggregate)
        val picked = SiteSelector.pick(sites, savedKey = "lz", manual = true)
        assertEquals("lz", picked?.key)
    }

    @Test
    fun `manual choice falls back when site disappeared or unusable`() {
        val sites = parse(aggregate)
        assertEquals("dytt", SiteSelector.pick(sites, "missing", manual = true)?.key)
        // JS 源已经可加载，手动选它就该留住
        assertEquals("js", SiteSelector.pick(sites, "js", manual = true)?.key)
    }

    @Test
    fun `cms only config keeps first cms`() {
        val sites = parse(
            """{"sites":[
                {"key":"a","name":"A","type":1,"api":"https://a.com/api.php/provide/vod"},
                {"key":"b","name":"B","type":0,"api":"https://b.com/api.php/provide/vod/at/xml/"}
            ]}"""
        )
        assertEquals("a", SiteSelector.pick(sites, "", false)?.key)
    }

    @Test
    fun `all unusable still returns something to explain`() {
        val sites = parse("""{"sites":[{"key":"node","name":"Node","type":3,"api":"node:demo"}]}""")
        val picked = SiteSelector.pick(sites, "", false)
        assertEquals("node", picked?.key)
        assertFalse(picked!!.isPlayable)
    }

    @Test
    fun `spider jar helper keeps md5 spec and splits it`() {
        // 原样返回带 `;md5;hash` 的 spec，拆 url/hash 交给 splitJarSpec
        assertEquals(
            "https://a.com/x.jar;md5;abc",
            Site.spiderJar(JSONObject("""{"spider":"https://a.com/x.jar;md5;abc"}"""))
        )
        assertEquals("https://b.com/y.jar", Site.spiderJar(JSONObject("""{"spider":{"jar":"https://b.com/y.jar"}}""")))
        assertEquals("", Site.spiderJar(JSONObject("""{"sites":[]}""")))

        assertEquals("https://a.com/x.jpg" to "abc", Site.splitJarSpec("https://a.com/x.jpg;md5;abc"))
        assertEquals("https://a.com/x.jpg" to "", Site.splitJarSpec("https://a.com/x.jpg"))
    }

    @Test
    fun `empty site list returns null`() {
        assertNull(SiteSelector.pick(emptyList(), "k", true))
    }
}
