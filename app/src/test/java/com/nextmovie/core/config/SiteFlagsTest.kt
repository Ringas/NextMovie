package com.nextmovie.core.config
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class SiteFlagsTest {
    @Test fun numericFlagsDoNotMakeNonSearchSourcesSearchable() {
        val site = Site.fromJson(JSONObject("""{"key":"menu","api":"csp_Test","type":3,"searchable":0,"quickSearch":"0","filter":1}"""), "")
        assertFalse(site.searchable)
        assertFalse(site.quickSearch)
        assertTrue(site.filterable)
    }
}
