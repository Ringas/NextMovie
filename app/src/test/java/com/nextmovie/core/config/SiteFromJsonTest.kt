package com.nextmovie.core.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteFromJsonTest {

    @Test
    fun `ext object is serialized`() {
        val o = JSONObject(
            """{"key":"k","name":"n","type":3,"api":"csp_X","ext":{"token":"a","n":1}}"""
        )
        val site = Site.fromJson(o, "https://cfg.test/x.json", "https://cfg.test/s.jar")
        assertTrue(site.ext.contains("token"))
        assertTrue(site.ext.contains("a"))
    }

    @Test
    fun `header singular and string json`() {
        val o = JSONObject(
            """{"key":"k","name":"n","type":1,"api":"https://a.test/api","header":{"User-Agent":"okhttp"}}"""
        )
        val site = Site.fromJson(o, "https://cfg.test/x.json")
        assertEquals("okhttp", site.headers["User-Agent"])
    }

    @Test
    fun `filterable key and timeout seconds`() {
        val o = JSONObject(
            """{"key":"k","name":"n","type":1,"api":"https://a.test/api","filterable":1,"timeout":30,"searchable":0}"""
        )
        val site = Site.fromJson(o, "https://cfg.test/x.json")
        assertTrue(site.filterable)
        assertEquals(30_000, site.timeoutMs)
        assertFalse(site.searchable)
    }

    @Test
    fun `timeout already in ms`() {
        val o = JSONObject(
            """{"key":"k","name":"n","type":1,"api":"https://a.test/api","timeout":15000}"""
        )
        assertEquals(15_000, Site.fromJson(o, "").timeoutMs)
    }

    @Test
    fun `site level jar overrides global`() {
        val o = JSONObject(
            """{"key":"k","name":"n","type":3,"api":"csp_A","jar":"https://x.test/a.jar;md5;aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        )
        val site = Site.fromJson(o, "", "https://x.test/global.jar")
        assertEquals("https://x.test/a.jar", site.jar)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", site.jarHash)
    }
}
