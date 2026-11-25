package com.nextmovie.core.source

import com.nextmovie.core.config.Site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceKindTest {

    private fun site(api: String, type: Int = 3, jar: String = ""): Site =
        Site(key = api, name = api, api = api, type = type, jar = jar)

    @Test
    fun `classifies cms jar js python catpaw adapter`() {
        assertEquals(SourceKind.CMS, SourceKind.of(site("https://a.com/api.php/provide/vod", type = 1)))
        assertEquals(SourceKind.JAR, SourceKind.of(site("csp_Demo", jar = "https://x/a.jar")))
        assertEquals(SourceKind.JS, SourceKind.of(site("https://a.com/x.js")))
        assertEquals(SourceKind.PYTHON, SourceKind.of(site("https://a.com/x.py")))
        assertEquals(SourceKind.CATPAW, SourceKind.of(site("node:demo")))
        assertEquals(SourceKind.ADAPTER, SourceKind.of(site("csp_XBPQ")))
        assertEquals(SourceKind.ADAPTER, SourceKind.of(site("adapter:demo")))
    }

    @Test
    fun `router matrix lists seven capabilities`() {
        val rows = SourceRouter().capabilityMatrix()
        assertEquals(7, rows.size)
        assertTrue(rows.first { it.kind == SourceKind.CMS }.ready)
        assertTrue(rows.first { it.kind == SourceKind.JAR }.ready)
        assertTrue(rows.first { it.kind == SourceKind.JS }.ready)
        assertFalse(rows.first { it.kind == SourceKind.PYTHON }.ready)
        assertFalse(rows.first { it.kind == SourceKind.CATPAW }.ready)
    }
}
