package com.nextmovie.core.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SiteHealthTest {

    private fun parse(json: String): List<Site> {
        val root = JSONObject(json)
        return ConfigBundle.fromJson(root, "https://example.com/tv/x.json", Site.spiderJar(root)).sites
    }

    @Test
    fun `audit splits cms plugin js and unsupported`() {
        val sites = parse(
            """
            {"spider":"https://example.com/tv/x.jpg;md5;4ce29ce27eeff6a73a230dd92d98ba0c","sites":[
              {"key":"a","name":"CMS","type":1,"api":"https://a.com/api.php/provide/vod"},
              {"key":"b","name":"CMS2","type":0,"api":"https://b.com/api.php/provide/vod/at/xml/"},
              {"key":"g1","name":"插件1","type":3,"api":"csp_One"},
              {"key":"g2","name":"插件2","type":3,"api":"csp_Two"},
              {"key":"j1","name":"JS","type":3,"api":"https://a.com/x.js"}
            ]}
            """.trimIndent()
        )
        val report = SiteHealth.audit(sites)
        assertEquals(5, report.total)
        assertEquals(2, report.ready)
        assertEquals(2, report.plugin)
        assertEquals(1, report.js)
        assertEquals(0, report.unsupported)
        assertEquals(40, report.usablePercent)
        // 插件包信息要能从站点里回溯出来，面板上直接展示 md5 是否启用
        assertEquals("https://example.com/tv/x.jpg", report.jarUrl)
        assertEquals("4ce29ce27eeff6a73a230dd92d98ba0c", report.jarHash)
    }

    @Test
    fun `node and python count as unsupported`() {
        val sites = parse(
            """{"sites":[
              {"key":"n","name":"Node","type":3,"api":"node:demo"},
              {"key":"p","name":"PY","type":3,"api":"https://a.com/x.py"}
            ]}"""
        )
        val report = SiteHealth.audit(sites)
        assertEquals(2, report.unsupported)
        assertEquals(0, report.ready)
    }
}
