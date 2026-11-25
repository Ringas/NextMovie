package com.nextmovie.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机后就把当前源准备好（下载插件 jar、起 :spider 里的插件实例、预热首页）。
 *
 * 插件源的首次初始化要 10–25 秒，且只在 `:spider` 进程里做一次。电视盒子开机后
 * 通常要过一阵用户才会打开应用，这段时间正好用来把这笔开销付掉，用户点进应用时
 * 插件实例已经在内存里，首页/详情直接是秒级。
 */
object BootWarm {

    fun run(context: Context, reason: String) {
        val app = context.applicationContext
        // 先把 :spider 拉起来并以"已启动"的方式持有，避免预热结束后进程被回收，
        // 那样用户开机后第一次打开又要重付一次初始化。
        runCatching {
            app.startService(android.content.Intent(app, com.nextmovie.core.spider.SpiderService::class.java))
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val started = System.nanoTime()
                val repo = AppRepository(app)
                if (!repo.restoreCachedConfig()) return@runCatching
                repo.prefetchSpiderJar(reason)
                repo.prewarmCurrentSite(reason)
                android.util.Log.i(
                    "NextMovieTiming",
                    "boot-warm queued ${(System.nanoTime() - started) / 1_000_000}ms reason=$reason"
                )
            }.onFailure { android.util.Log.w("NextMovie", "boot-warm failed: ${it.message}") }
        }
    }
}
