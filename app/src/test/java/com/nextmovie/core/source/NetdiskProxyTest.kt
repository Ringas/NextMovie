
package com.nextmovie.core.source

import com.nextmovie.core.source.netdisk.NetdiskProvider
import com.nextmovie.core.source.netdisk.NetdiskProxy
import org.junit.Assert.*
import org.junit.Test

class NetdiskProxyTest {
    @Test fun detectQuark() {
        assertEquals(NetdiskProvider.QUARK, NetdiskProxy.detect("https://drive-s.quark.cn/1.mp4"))
    }
    @Test fun mergeDoesNotOverride() {
        val h = NetdiskProxy.mergePlayHeaders(
            "https://drive-s.quark.cn/1.mp4",
            mapOf("User-Agent" to "custom")
        )
        assertEquals("custom", h["User-Agent"])
    }
    @Test fun pluginProxyUrl() {
        assertTrue(NetdiskProxy.isPluginProxyUrl("http://127.0.0.1:9978/proxy?do=play"))
    }
}
