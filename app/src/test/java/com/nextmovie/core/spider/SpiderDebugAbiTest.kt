package com.nextmovie.core.spider
import com.github.catvod.crawler.SpiderDebug
import org.junit.Assert.*
import org.junit.Test
class SpiderDebugAbiTest {
    @Test fun guardErrorLoggerHasExactJvmSignature() {
        assertEquals(Void.TYPE, SpiderDebug::class.java.getMethod("log", Throwable::class.java).returnType)
        assertEquals(Void.TYPE, SpiderDebug::class.java.getMethod("log", String::class.java).returnType)
    }
}
