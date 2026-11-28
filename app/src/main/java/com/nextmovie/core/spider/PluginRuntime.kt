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
        return engines ?: Engines(
            JarSpiderLoader(context.applicationContext, Http()),
            JsSpiderLoader(
                Http(),
                java.io.File(context.applicationContext.filesDir, "js-cache"),
                // JS 插件的 local 要落盘，否则登录态 / 接口 token 一重启就丢
                context.applicationContext.getSharedPreferences("js_local", Context.MODE_PRIVATE)
            )
        ).also { engines = it }
    }
    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { engines?.jar?.release(); engines?.js?.release(); engines = null }
    }
}
