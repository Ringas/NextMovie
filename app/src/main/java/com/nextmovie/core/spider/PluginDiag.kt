package com.nextmovie.core.spider

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件加载诊断：把「最近一次 csp_ 插件的加载结果」留一条记录。
 *
 * 为什么需要它：Guard 系插件只有在 ARM 真机上才可能载入原生库，模拟器无法替代验证。
 * 但让用户去抓 adb logcat 门槛太高，所以把关键结论（站点 / 插件类 / 成功或失败原因）
 * 写进 filesDir 下的一个纯文本文件：插件在 :spider 独立进程里加载，
 * 用 SharedPreferences 会在两个进程之间出现缓存不同步（主进程读不到新写入），
 * 落文件最省事也最可靠。
 */
object PluginDiag {
    private const val FILE_NAME = "plugin-diag.txt"

    private fun file(context: Context) = java.io.File(context.filesDir, FILE_NAME)

    fun record(context: Context, site: String, api: String, ok: Boolean, detail: String?) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val body = detail.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(" / ")
            .ifBlank { if (ok) "加载成功" else "无更多信息" }
        val text = (if (ok) "✓ " else "✗ ") + "$site ($api) · $ts\n$body"
        runCatching { file(context).writeText(text) }
        Log.i("NextMovieDiag", text.replace('\n', ' '))
    }

    fun saveInit(context: Context, text: String) {
        runCatching { java.io.File(context.filesDir, "plugin-init.txt").writeText(text.take(1500)) }
    }

    fun readInit(context: Context): String = runCatching {
        val f = java.io.File(context.filesDir, "plugin-init.txt")
        if (f.exists()) f.readText().trim() else ""
    }.getOrDefault("")

    fun read(context: Context): String = runCatching {
        val f = file(context)
        if (f.exists()) f.readText().trim() else ""
    }.getOrDefault("")
}
