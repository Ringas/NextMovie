package com.nextmovie.core.spider

import com.nextmovie.core.config.Site
import org.junit.Assert.*
import org.junit.Test

class PluginIdentityTest {
    @Test fun transportPreservesOriginAndJarDigest() {
        val site = Site("same", "Source", "csp_Test", type = 3, jar = "https://example.test/plugin.jar", jarHash = "ab12", origin = "https://example.test/config.json", ext = "{\"a\":1}", headers = mapOf("Referer" to "https://example.test"))
        val restored = SpiderProtocol.siteFromTransport(with(SpiderProtocol) { site.toTransport() })
        assertEquals(site, restored)
        assertEquals(site.runtimeKey, restored.runtimeKey)
    }
    @Test fun changedConfigurationNeverReusesAPlugin() {
        val site = Site("same", "Source", "csp_Test", type = 3, origin = "https://a.test/config")
        assertNotEquals(site.runtimeKey, site.copy(origin = "https://b.test/config").runtimeKey)
        assertNotEquals(site.runtimeKey, site.copy(ext = "different").runtimeKey)
        assertNotEquals(site.runtimeKey, site.copy(jarHash = "new").runtimeKey)
    }
}
