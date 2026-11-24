package com.nextmovie.core.spider

import android.content.Context
import com.github.catvod.crawler.Spider

/** Respect both CatVod init signatures; invoking an inherited stub skips plugin setup. */
object SpiderInitializer {
    fun initialize(spider: Spider, context: Context?, ext: String) {
        val two = spider.javaClass.getMethod("init", Context::class.java, String::class.java)
        val one = spider.javaClass.getMethod("init", Context::class.java)
        if (two.declaringClass == Spider::class.java && one.declaringClass != Spider::class.java) {
            spider.init(context)
        } else {
            spider.init(context, ext)
        }
    }
}
