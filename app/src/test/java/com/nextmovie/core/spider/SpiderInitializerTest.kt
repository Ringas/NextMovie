package com.nextmovie.core.spider
import android.content.Context
import com.github.catvod.crawler.Spider
import org.junit.Assert.*
import org.junit.Test
class SpiderInitializerTest {
    class Legacy : Spider() { var called = false; override fun init(context: Context?) { called = true } }
    class Modern : Spider() { var value = "missing"; override fun init(context: Context?, extend: String?) { value = extend.orEmpty() } }
    @Test fun oneArgumentOverrideIsActuallyInvoked() {
        val spider = Legacy(); SpiderInitializer.initialize(spider, null, "config"); assertTrue(spider.called)
    }
    @Test fun twoArgumentOverrideReceivesItsConfiguration() {
        val spider = Modern(); SpiderInitializer.initialize(spider, null, "config"); assertEquals("config", spider.value)
    }
}
