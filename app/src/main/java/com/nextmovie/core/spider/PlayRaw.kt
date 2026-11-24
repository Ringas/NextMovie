package com.nextmovie.core.spider

import android.content.Context
import java.io.File

/** 最近一次插件 playerContent 的原始返回，出问题时直接展示给用户，省掉抓 logcat。 */
object PlayRaw {
    private const val NAME = "play-raw.txt"

    fun save(context: Context, raw: String) {
        runCatching { File(context.filesDir, NAME).writeText(raw.take(2000)) }
    }

    fun read(context: Context): String = runCatching {
        val f = File(context.filesDir, NAME)
        if (!f.exists()) "" else f.readText().trim()
            .replace('\n', ' ')
            .take(220)
    }.getOrDefault("")
}
