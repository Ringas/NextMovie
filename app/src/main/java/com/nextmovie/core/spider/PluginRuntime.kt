package com.nextmovie.core.spider

import android.content.Context
import com.nextmovie.core.net.Http

/** Shared only inside :spider, so account UI uses the exact same loaded plugin and login state. */
object PluginRuntime {
    data class Engines(val jar: JarSpiderLoader, val js: JsSpiderLoader)
    private var engines: Engines? = null
    private var users = 0
    @Synchronized fun acquire(context: Context): Engines {
        users++
        return engines ?: Engines(JarSpiderLoader(context.applicationContext, Http()), JsSpiderLoader(Http())).also { engines = it }
    }
    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { engines?.jar?.release(); engines?.js?.release(); engines = null }
    }
}
