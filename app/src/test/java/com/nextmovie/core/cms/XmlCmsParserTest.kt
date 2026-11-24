package com.nextmovie.core.cms

import org.junit.Assert.*
import org.junit.Test

class XmlCmsParserTest {
    @Test fun xmlDetailsKeepEpisodesAndCdata() {
        val details = XmlCmsParser.details("""<rss><list><video><id>42</id><name><![CDATA[A & B]]></name><pic>https://example.test/poster.jpg</pic><year>2026</year><dl><dd flag="hls"><![CDATA[第一集${'$'}https://example.test/1.m3u8#第二集${'$'}https://example.test/2.m3u8]]></dd><dd flag="mp4">正片${'$'}https://example.test/a.mp4</dd></dl></video></list></rss>""")
        assertEquals("42", details.single().vod.id)
        assertEquals("A & B", details.single().vod.name)
        assertEquals(2, details.single().lines.size)
        assertEquals("https://example.test/2.m3u8", details.single().lines[0].episodes[1].url)
    }
    @Test fun externalEntityNeverExpands() {
        val xml = """<!DOCTYPE rss [<!ENTITY ext SYSTEM "file:///etc/passwd">]><rss><video><name>&ext;</name></video></rss>"""
        val result = runCatching { XmlCmsParser.details(xml) }
        assertTrue(result.isFailure || result.getOrThrow().none { it.vod.name.contains("root:") })
    }
}
